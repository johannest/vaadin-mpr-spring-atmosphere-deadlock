# Recommended Fixes for the Deadlock

## Fix Overview

The deadlock involves two locks acquired in opposite order:
- **AtmospherePushConnection's internal `lock`** (Java `synchronized` block)
- **WebSphere's internal HttpSession lock** (implicit, inside `HttpSessionImpl`)

Breaking either side of the cycle resolves the deadlock. Below are three options, ordered by recommendation priority.

---

## Existing Mitigation: the `disconnecting` Flag

`AtmospherePushConnection.disconnect()` already contains a guard intended to prevent this deadlock — a `volatile boolean disconnecting` field checked at the top of the method before the `synchronized (lock)` block. The code comment explicitly states:

> *"This also prevents potential deadlocks if the container acquires locks during operations on HTTP session, as closing the AtmosphereResource may cause HTTP session access"*

**This mitigation is insufficient.** It has a classic TOCTOU (time-of-check-time-of-use) race condition:

- The flag is **read** outside `synchronized(lock)`.
- The flag is **set to `true`** inside `synchronized(lock)`, after the lock is acquired and the `isConnected()` check passes.

There is a window — between a thread acquiring the lock and executing `disconnecting = true` — during which a second thread can read the flag as `false`, proceed past the check, and then block on `synchronized(lock)`:

```
Thread A:  if (disconnecting) → false     // passes check
Thread A:  synchronized(lock) { ...       // acquires lock
Thread B:  if (disconnecting) → false     // passes check (flag not yet set!)
Thread A:      disconnecting = true;      // now set — too late for Thread B
Thread A:      resource.close() → getAttribute() → BLOCKED (HttpSession lock)
Thread B:  synchronized(lock) → BLOCKED (held by Thread A)
                    ↓ DEADLOCK ↓
```

The window is narrow (~nanoseconds: a few field reads between lock acquisition and the volatile write), so the flag makes the deadlock **orders of magnitude less likely**. But it cannot eliminate it — given enough production logouts, the window is eventually hit.

Replacing the `volatile boolean` with an `AtomicBoolean` and using `compareAndSet(false, true)` before the `synchronized` block **eliminates the TOCTOU and is sufficient to prevent this deadlock** — the second thread returns immediately from `disconnect()`, which lets `HttpSession.invalidate()` complete and release the HttpSession lock, unblocking the first thread's `resource.close()` → `getAttribute()`. See Fix 1a below.

Moving `resource.close()` outside `synchronized(lock)` is a recommended **additional** improvement (defense in depth) that eliminates the lock-ordering dependency structurally. See Fix 1b below.

See `DEADLOCK_ANALYSIS.md` § "Existing Mitigation: the `disconnecting` Flag (TOCTOU Race)" for the detailed timeline and analysis.

---

## Fix 1 (Recommended): Fix `AtmospherePushConnection.disconnect()`

**Component:** Vaadin Flow (`flow-server`)
**File:** `com/vaadin/flow/server/communication/AtmospherePushConnection.java`

### Current code (lines 312-367):

```java
@Override
public void disconnect() {
    if (disconnecting) {
        getLogger().debug("Disconnection already in progress, ignoring request");
        return;
    }

    synchronized (lock) {
        if (!isConnected() || resource == null) {
            getLogger().debug("Disconnection already happened, ignoring request");
            return;
        }
        try {
            disconnecting = true;
            if (resource.isResumed()) {
                connectionLost();
                return;
            }
            if (outgoingMessage != null) {
                try {
                    outgoingMessage.get(1000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    getLogger().info("Timeout waiting for messages to be sent...", e);
                } catch (Exception e) {
                    getLogger().info("Error waiting for messages to be sent...", e);
                }
                outgoingMessage = null;
            }
            try {
                resource.close();    // <-- This calls into HttpSession
            } catch (IOException e) {
                getLogger().info("Error when closing push connection", e);
            }
            connectionLost();
        } finally {
            disconnecting = false;
        }
    }
}
```

### Fix 1a (Minimal — fixes the deadlock): Replace `volatile boolean` with `AtomicBoolean`

This is the smallest possible change that fully prevents the deadlock.

Replace `volatile boolean disconnecting` with `AtomicBoolean` and use `compareAndSet` as the entry gate:

**Field change:**

```java
// Before:
private volatile boolean disconnecting;

// After:
private final AtomicBoolean disconnecting = new AtomicBoolean(false);
```

*(Add `import java.util.concurrent.atomic.AtomicBoolean;`)*

**Method change:**

```java
@Override
public void disconnect() {
    // Atomically claim the right to disconnect. Only one thread can
    // pass this gate — eliminates the TOCTOU race that existed when
    // the volatile boolean was checked outside synchronized(lock) but
    // set inside it.
    if (!disconnecting.compareAndSet(false, true)) {
        getLogger().debug("Disconnection already in progress, ignoring request");
        return;
    }

    synchronized (lock) {
        if (!isConnected() || resource == null) {
            getLogger().debug("Disconnection already happened, ignoring request");
            disconnecting.set(false);
            return;
        }
        try {
            if (resource.isResumed()) {
                connectionLost();
                return;
            }
            if (outgoingMessage != null) {
                try {
                    outgoingMessage.get(1000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    getLogger().info(
                            "Timeout waiting for messages to be sent to client before disconnect",
                            e);
                } catch (Exception e) {
                    getLogger().info(
                            "Error waiting for messages to be sent to client before disconnect",
                            e);
                }
                outgoingMessage = null;
            }
            try {
                resource.close();
            } catch (IOException e) {
                getLogger().info("Error when closing push connection", e);
            }
            connectionLost();
        } finally {
            disconnecting.set(false);
        }
    }
}
```

#### Why this is sufficient

With `compareAndSet`, exactly one thread passes the gate. The second thread returns immediately from `disconnect()`. This breaks the deadlock because:

1. **Thread A** wins the `compareAndSet` → enters `synchronized(lock)` → calls `resource.close()` → `HttpSession.getAttribute()` → **blocked** (Thread B holds HttpSession lock)
2. **Thread B** loses the `compareAndSet` → **returns immediately** from `disconnect()` → continues the `invalidate()` callback chain → `invalidate()` completes → **releases HttpSession lock**
3. **Thread A** unblocks → `getAttribute()` succeeds → disconnect completes normally

The key insight: Thread B returning from `disconnect()` allows the `HttpSession.invalidate()` call to finish and release the HttpSession lock. Thread A was only blocked because Thread B held that lock — once released, Thread A proceeds without contention.

#### Additional changes required

- `push()` reads `disconnecting` — its `if (disconnecting || !isConnected())` check needs to change to `if (disconnecting.get() || !isConnected())`. Same `volatile`-read semantics.
- `readObject()` (deserialization) should reinitialize: `disconnecting = new AtomicBoolean(false);`

### Fix 1b (Additional improvement): Move `resource.close()` outside `synchronized`

This is not required for correctness (Fix 1a alone prevents the deadlock), but is recommended as defense in depth:

- **Eliminates the lock-ordering dependency structurally** — even if a future code path bypasses the `compareAndSet` gate (subclass, reflection, refactor), the lock ordering cannot deadlock.
- **Reduces lock holding time** — `resource.close()` may involve network I/O (closing a WebSocket); holding the internal lock during that time blocks all concurrent `push()` calls unnecessarily.
- **Protects against other container locks** — there may be other internal locks that Atmosphere's close path interacts with, not just the HttpSession lock.

**Method change** (building on Fix 1a):

```java
@Override
public void disconnect() {
    if (!disconnecting.compareAndSet(false, true)) {
        getLogger().debug("Disconnection already in progress, ignoring request");
        return;
    }

    AtmosphereResource resourceToClose = null;

    try {
        synchronized (lock) {
            if (!isConnected() || resource == null) {
                getLogger().debug("Disconnection already happened, ignoring request");
                return;
            }
            if (resource.isResumed()) {
                connectionLost();
                return;
            }
            if (outgoingMessage != null) {
                try {
                    outgoingMessage.get(1000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    getLogger().info(
                            "Timeout waiting for messages to be sent to client before disconnect",
                            e);
                } catch (Exception e) {
                    getLogger().info(
                            "Error waiting for messages to be sent to client before disconnect",
                            e);
                }
                outgoingMessage = null;
            }
            // Capture the resource reference and update internal state
            // while holding the lock, but defer the actual close to
            // outside the synchronized block.
            resourceToClose = resource;
            connectionLost(); // Sets resource = null, state = DISCONNECTED
        }
    } finally {
        if (resourceToClose == null) {
            disconnecting.set(false);
        }
    }

    // Close the Atmosphere resource outside the synchronized block.
    if (resourceToClose != null) {
        try {
            resourceToClose.close();
        } catch (IOException e) {
            getLogger().info("Error when closing push connection", e);
        } finally {
            disconnecting.set(false);
        }
    }
}
```

#### Safety

- `connectionLost()` sets `resource = null` and `state = DISCONNECTED` inside the lock, so concurrent `push()` and `disconnect()` calls see the connection as closed.
- The captured `resourceToClose` local is only visible to this thread.
- `resource.close()` is called without the lock. Atmosphere's `AtmosphereResourceImpl.close()` has its own internal guards and is designed to be called from arbitrary threads (e.g., timeout handlers).
- The `outgoingMessage.get()` wait stays inside the lock. It has a 1-second timeout and does not interact with the HttpSession, so it is not part of the deadlock cycle.

---

## Fix 2 (Complementary): Async push disconnect in Flow's `UIInternals.setSession(null)`

**Component:** Vaadin Flow (`flow-server`)
**File:** `com/vaadin/flow/component/internal/UIInternals.java`

This mirrors the pattern already used in Vaadin 7's `UI.setSession(null)` (UI.java:469-481), where push disconnection runs on a separate thread to avoid deadlocks with container-held locks.

### Current code (lines 397-410):

```java
if (session == null) {
    try {
        ui.getElement().getNode().setParent(null);
    } catch (IllegalStateException e) {
        getLogger().warn("Error detaching closed UI {} ", ui.getUIId(), e);
    }
    // Disable push when the UI is detached. Otherwise the
    // push connection and possibly VaadinSession will live on.
    ui.getPushConfiguration().setPushMode(PushMode.DISABLED);
    setPushConnection(null);   // <-- synchronous disconnect
}
this.session = session;
```

### Proposed fix:

```java
if (session == null) {
    try {
        ui.getElement().getNode().setParent(null);
    } catch (IllegalStateException e) {
        getLogger().warn("Error detaching closed UI {} ", ui.getUIId(), e);
    }
    // Disable push when the UI is detached. Otherwise the
    // push connection and possibly VaadinSession will live on.
    ui.getPushConfiguration().setPushMode(PushMode.DISABLED);

    // Disconnect push on a separate thread to avoid deadlocks with
    // containers (e.g., WebSphere) that hold internal locks on the
    // HttpSession during session invalidation callbacks.
    // This mirrors the pattern used in Vaadin 7 UI.setSession()
    // (see https://dev.vaadin.com/ticket/18436).
    PushConnection pushConnectionToDisconnect = this.pushConnection;
    this.pushConnection = null;
    if (pushConnectionToDisconnect != null
            && pushConnectionToDisconnect.isConnected()) {
        new Thread(() -> {
            try {
                pushConnectionToDisconnect.disconnect();
            } catch (Exception e) {
                getLogger().warn("Error disconnecting push for UI {}",
                        ui.getUIId(), e);
            }
        }, "vaadin-push-disconnect-" + ui.getUIId()).start();
    }
}
this.session = session;
```

### Why this helps

This breaks the deadlock from Thread B's side: the `AtmospherePushConnection.disconnect()` call no longer happens on the thread that holds the HttpSession lock. Even without Fix 1, this alone prevents Thread B from ever waiting on `AtmospherePushConnection.lock` while holding the HttpSession lock.

### Considerations

- Spawning a thread is slightly more expensive but matches the established Vaadin 7 pattern.
- The disconnect is no longer guaranteed to complete before `setSession()` returns. This should be acceptable since the UI is being destroyed anyway.
- `this.pushConnection` is set to `null` synchronously so the `setPushConnection()` contract is maintained.

---

## Fix 3 (MPR-only workaround): Async disconnect in `MprPushConnection`

**Component:** Vaadin MPR (`mpr-core`)
**File:** `com/vaadin/mpr/core/MprPushConnection.java`

If patching Flow is not immediately feasible, an MPR-side workaround can break the deadlock from Thread A's side.

### Current code (lines 54-58):

```java
@Override
public void disconnect() {
    getFlowPushConnection().filter(PushConnection::isConnected)
            .ifPresent(PushConnection::disconnect);
}
```

### Proposed fix:

```java
@Override
public void disconnect() {
    getFlowPushConnection().filter(PushConnection::isConnected)
            .ifPresent(pc -> {
                // Run disconnect asynchronously to avoid deadlocks with
                // containers (e.g., WebSphere) that synchronize on the
                // HttpSession. The Vaadin 7 legacy UI calls this method
                // from a dedicated thread (UI.java:469) without the
                // VaadinSession lock, but AtmospherePushConnection's
                // internal lock + Atmosphere's HttpSession access can
                // still deadlock with concurrent session invalidation.
                // Spawning another thread ensures the Atmosphere close
                // path never runs while holding locks from the calling
                // context.
                new Thread(() -> {
                    try {
                        pc.disconnect();
                    } catch (Exception e) {
                        LoggerFactory.getLogger(MprPushConnection.class)
                                .warn("Error during async push disconnect", e);
                    }
                }, "mpr-push-disconnect").start();
            });
}
```

### Limitations

This only addresses Thread A's path. Thread B (Flow `UIInternals.setSession(null)`) still disconnects synchronously and would need Fix 1 or Fix 2 to be fully resolved. However, since Thread A is the only thread that holds `AtmospherePushConnection.lock` while trying to acquire the HttpSession lock, this fix alone is sufficient to break the cycle.

---

## Recommendation

| Fix | Scope | What it does | Sufficient alone? | Risk |
|---|---|---|---|---|
| **Fix 1a** | Flow | `AtomicBoolean.compareAndSet` gate | **Yes** | **Minimal** — one field + one method change |
| Fix 1b | Flow | Also moves `resource.close()` outside lock | Yes | Low — local refactor, defense in depth |
| Fix 2 | Flow | Async push disconnect in `UIInternals` | Yes | Low — mirrors proven Vaadin 7 pattern |
| Fix 3 | MPR | Async disconnect in `MprPushConnection` | Yes* | Low — isolated to MPR adapter |

\* Fix 3 is sufficient because Thread A is the only path that creates the `AtmospherePushConnection.lock` -> `HttpSession lock` dependency. If Thread A no longer holds `AtmospherePushConnection.lock` when calling into the HttpSession, the cycle is broken.

**Recommended approach:**

1. **Fix 1a** as the primary fix — the smallest change that fully prevents the deadlock. Replacing `volatile boolean` with `AtomicBoolean.compareAndSet()` ensures exactly one thread enters the disconnect path; the second thread returns immediately, letting `HttpSession.invalidate()` complete and release the HttpSession lock.

2. **Fix 1b** as an additional improvement — moving `resource.close()` outside `synchronized(lock)` eliminates the lock-ordering dependency structurally, reduces lock holding time, and protects against future code paths that might bypass the `compareAndSet` gate.

3. **Fix 3** as a stopgap if a Flow release is not immediately available.

Fix 2 is recommended as further defense-in-depth: it makes Flow consistent with Vaadin 7's established pattern and protects against similar deadlocks with other locks that Atmosphere or the container may hold.
