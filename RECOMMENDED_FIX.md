# Recommended Fixes for the Deadlock

## Fix Overview

The deadlock involves two locks acquired in opposite order:
- **AtmospherePushConnection's internal `lock`** (Java `synchronized` block)
- **WebSphere's internal HttpSession lock** (implicit, inside `HttpSessionImpl`)

Breaking either side of the cycle resolves the deadlock. Below are three options, ordered by recommendation priority.

---

## Fix 1 (Recommended): Move `resource.close()` outside `synchronized` in `AtmospherePushConnection.disconnect()`

**Component:** Vaadin Flow (`flow-server`)
**File:** `com/vaadin/flow/server/communication/AtmospherePushConnection.java`

This is the most targeted fix. The `resource.close()` call at line 358 invokes Atmosphere's shutdown path, which calls back into `HttpSession.getAttribute()` via `SessionTimeoutSupport`. On WebSphere, this acquires the HttpSession lock. Moving this call outside the `synchronized (lock)` block breaks the `AtmospherePushConnection.lock` -> `HttpSession lock` dependency.

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

### Proposed fix:

```java
@Override
public void disconnect() {
    if (disconnecting) {
        getLogger().debug("Disconnection already in progress, ignoring request");
        return;
    }

    AtmosphereResource resourceToClose = null;

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
            // outside the synchronized block. This avoids a deadlock
            // with containers (e.g., WebSphere) that synchronize on the
            // HttpSession during both getAttribute() and invalidate(),
            // since Atmosphere's close path calls
            // SessionTimeoutSupport.restoreTimeout() which accesses
            // the HttpSession.
            resourceToClose = resource;
            connectionLost(); // Sets resource = null, state = DISCONNECTED
        } finally {
            disconnecting = false;
        }
    }

    // Close the Atmosphere resource outside the synchronized block.
    // At this point, internal state is already DISCONNECTED and
    // resource is null, so concurrent calls to disconnect() or
    // push() will see the connection as already closed.
    if (resourceToClose != null) {
        try {
            resourceToClose.close();
        } catch (IOException e) {
            getLogger().info("Error when closing push connection", e);
        }
    }
}
```

### Why this is safe

- `connectionLost()` sets `resource = null` and `state = DISCONNECTED` inside the synchronized block, so concurrent `push()` and `disconnect()` calls will see the connection as already closed.
- The `disconnecting` volatile flag provides a fast non-blocking guard at the top.
- The captured `resourceToClose` local variable is only visible to this thread.
- Even if `resource.close()` fails or blocks, the `AtmospherePushConnection` is already in a clean DISCONNECTED state.
- The `push()` method checks `disconnecting || !isConnected()` before entering its synchronized block, so no messages will be sent to the closing resource.

### Considerations

- `resource.close()` is now called without the lock. If Atmosphere's `close()` is not itself thread-safe, there could be issues. However, Atmosphere's `AtmosphereResourceImpl.close()` has its own internal guards and is designed to be called from arbitrary threads (e.g., timeout handlers), so this should be safe.
- The `outgoingMessage.get()` wait still happens inside the lock. If this is a concern, it could also be moved outside, but it has a 1-second timeout and does not interact with the HttpSession, so it is not part of the deadlock cycle.

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

| Fix | Scope | Breaks cycle from | Sufficient alone? | Risk |
|---|---|---|---|---|
| Fix 1 | Flow | Thread A side | Yes | Low — local refactor, no API change |
| Fix 2 | Flow | Thread B side | Yes | Low — mirrors proven Vaadin 7 pattern |
| Fix 3 | MPR | Thread A side | Yes* | Low — isolated to MPR adapter |

\* Fix 3 is sufficient because Thread A is the only path that creates the `AtmospherePushConnection.lock` -> `HttpSession lock` dependency. If Thread A no longer holds `AtmospherePushConnection.lock` when calling into the HttpSession, the cycle is broken.

**Recommended approach:** Apply **Fix 1** as the primary fix in Flow, as it addresses the root cause (holding a lock during an external callback) and protects all users, not just MPR users. Apply **Fix 3** as a stopgap if a Flow release is not immediately available.

Fix 2 is recommended as defense-in-depth: it makes Flow consistent with Vaadin 7's established pattern and protects against similar deadlocks with other locks that Atmosphere or the container may hold.
