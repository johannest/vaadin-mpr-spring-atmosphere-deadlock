# Deadlock Analysis #2: Same Lock-Ordering Cycle, New Code Path

## Executive Summary

The `AtomicBoolean.compareAndSet` fix applied in Vaadin 14.14.2 **works as designed** — it
correctly eliminates the disconnect-vs-disconnect TOCTOU race on the `disconnecting`
flag. However, the fix only guarded `disconnect()`. The **underlying lock-ordering
cycle** between `AtmospherePushConnection.lock` and WebSphere's `HttpSession` lock
was *not* eliminated, because `resource.close()` is still called while holding
`synchronized(lock)`.

The customer's new deadlock is the **same cycle**, manifested through a **different
caller on the HttpSession side**:

| | Original deadlock (14.14.1) | New deadlock (14.14.2) |
|---|---|---|
| **Thread A** | `disconnect()` → `resource.close()` → blocked on HttpSession lock | **same** — `disconnect()` → `resource.close()` → blocked on HttpSession lock |
| **Thread B — entry** | `com.vaadin.**flow**.server.VaadinSession.valueUnbound` | `com.vaadin.**server**.VaadinSession.valueUnbound` (Vaadin 7!) |
| **Thread B — blocked at** | `AtmospherePushConnection.**disconnect**()` → `synchronized(lock)` | `AtmospherePushConnection.**push**()` → `synchronized(lock)` |

Thread B is now blocked inside `push()`, not `disconnect()`. The `AtomicBoolean` gate
is only on `disconnect()`, so it cannot prevent this. Both `disconnect()` and `push()`
share the **same** `synchronized(lock)` monitor, and that monitor is still held across
a call (`resource.close()`) that re-enters the container's HttpSession lock.

**Conclusion:** Fix 1a alone was insufficient. Fix 1b (move `resource.close()` outside
`synchronized(lock)`) is now required — it is the only change that breaks the
lock-ordering cycle structurally, regardless of which Flow API re-enters the
push connection while HttpSession lock is held.

---

## Environment

Unchanged from the original deadlock, except:

| Component | Version |
|---|---|
| Vaadin Flow | **14.14.2** (with applied Fix 1a) |
| `AtmospherePushConnection` | Modified to use `AtomicBoolean disconnecting` + `compareAndSet` gate |

All other environment details (WebSphere 9.0.5.25, MPR 2.3.0, Vaadin 7.7.41,
Spring Security SAML2 logout) are unchanged.

---

## What the 14.14.2 Fix Actually Changed

Comparing the shipped `AtmospherePushConnection.java` against the original:

1. Field replaced: `private volatile boolean disconnecting;` → `private AtomicBoolean disconnecting = new AtomicBoolean(false);`
2. `disconnect()` now gates entry with `if (!disconnecting.compareAndSet(false, true)) return;` **before** `synchronized(lock)`. Only one thread can pass this gate at a time.
3. `push()` reads the flag via `disconnecting.get()` (same semantics as the volatile read it replaced).
4. `readObject()` reinitialises `disconnecting = new AtomicBoolean(false)`.

What the fix did **not** change:

- `resource.close()` is still called **inside** `synchronized(lock)` (new line 363).
- `push()` still enters `synchronized(lock)` at line 197 when the connection is "alive and not disconnecting" — there is **no** corresponding gate on `push()`.

### Minor latent bug noticed in the applied code (not the current deadlock)

In the applied fix, the early `return` inside `synchronized(lock)` when `!isConnected() || resource == null` does **not** reset `disconnecting` to `false`:

```java
synchronized (lock) {
    if (!isConnected() || resource == null) {
        getLogger().debug("Disconnection already happened, ignoring request");
        return;                        // <-- disconnecting is still true here
    }
    try {
        ...
    } finally {
        disconnecting.set(false);
    }
}
```

The recommended version had `disconnecting.set(false);` before that `return`. If this branch is ever taken, the flag is stuck at `true` — all subsequent `disconnect()` calls CAS-fail, and `push()` permanently treats the connection as "disconnecting".

This is not the cause of the current deadlock (Thread A is clearly past this check — it is at `resource.close()`), but it should be fixed alongside the main change.

---

## Thread Analysis (14.14.2)

### Thread A — unchanged path, same line semantics

```
at com/ibm/ws/session/http/HttpSessionImpl.getAttribute(HttpSessionImpl.java:189)
at com/ibm/ws/session/SessionData.getSessionValue(SessionData.java:307)
at com/ibm/ws/session/SessionData.getAttribute(SessionData.java:163)
at com/ibm/ws/session/HttpSessionFacade.getAttribute(HttpSessionFacade.java:139)
at org/atmosphere/cpr/SessionTimeoutSupport.get(SessionTimeoutSupport.java:73)
at org/atmosphere/cpr/SessionTimeoutSupport.restoreTimeout(SessionTimeoutSupport.java:59)
at org/atmosphere/cpr/SessionTimeoutSupport.restoreTimeout(SessionTimeoutSupport.java:69)
at org/atmosphere/cpr/AtmosphereResourceImpl.cancel(AtmosphereResourceImpl.java:818)
at org/atmosphere/cpr/AtmosphereResourceImpl.close(AtmosphereResourceImpl.java:901)
at com/vaadin/flow/server/communication/AtmospherePushConnection.disconnect(AtmospherePushConnection.java:363)  ← resource.close()
at com/vaadin/mpr/core/MprPushConnection$$Lambda$1686.accept
at java/util/Optional.ifPresent
at com/vaadin/mpr/core/MprPushConnection.disconnect(MprPushConnection.java:57)
at com/vaadin/ui/UI.setPushConnection(UI.java:1604)
at com/vaadin/mpr/core/AbstractMprUI.setPushConnection(AbstractMprUI.java:96)
at com/vaadin/ui/UI$3.run(UI.java:479)
at java/lang/Thread.run(Thread.java:785)
```

- Line 363 in the fixed file is `resource.close();` — the call made inside `synchronized(lock)`.
- Thread A therefore **successfully passed** the `compareAndSet(false, true)` gate (set `disconnecting = true`), **acquired** `synchronized(lock)`, waited out `outgoingMessage`, and is now blocked inside `resource.close()` → `AtmosphereResourceImpl.cancel` → `SessionTimeoutSupport.restoreTimeout` → `HttpSessionFacade.getAttribute` → **WebSphere's HttpSession monitor**.
- **Holds:** `AtmospherePushConnection.lock`
- **Wants:** WebSphere HttpSession lock (held by Thread B)

Thread A is the same Vaadin 7 async detach thread (`UI$3.run()` from `UI.java:479`) as before.

### Thread B — different path, blocked in `push()` not `disconnect()`

```
at com/vaadin/flow/server/communication/AtmospherePushConnection.push(AtmospherePushConnection.java:197)  ← synchronized(lock) in push()
at com/vaadin/flow/server/communication/AtmospherePushConnection.push(AtmospherePushConnection.java:172)
at com/vaadin/flow/component/UI.push(UI.java:683)
at com/vaadin/flow/server/VaadinSession.unlock(VaadinSession.java:709)    ← Flow's push loop at end of unlock()
at com/vaadin/flow/server/VaadinService.ensureAccessQueuePurged(VaadinService.java:2003)
at com/vaadin/flow/server/VaadinService.accessSession(VaadinService.java:1970)
at com/vaadin/mpr/core/MprServletService.accessSession(MprServletService.java:163)   ← MPR bridge
at com/vaadin/server/VaadinSession.access(VaadinSession.java:1543)        ← Vaadin 7 VaadinSession
at com/vaadin/server/VaadinService.fireSessionDestroy(VaadinService.java:454)  ← Vaadin 7 VaadinService
at com/vaadin/server/VaadinSession.valueUnbound(VaadinSession.java:327)   ← Vaadin 7 HttpSessionBindingListener
at com/ibm/ws/session/http/HttpSessionObserver.sessionDestroyed(HttpSessionObserver.java:223)
at com/ibm/ws/session/SessionEventDispatcher.sessionDestroyed(SessionEventDispatcher.java:160)
at com/ibm/ws/session/StoreCallback.sessionInvalidated(StoreCallback.java:126)
at com/ibm/ws/session/store/memory/MemorySession.invalidate(MemorySession.java:226)
at com/ibm/ws/session/http/HttpSessionImpl.invalidate(HttpSessionImpl.java:303)
at com/ibm/ws/session/SessionData.invalidate(SessionData.java:247)
at com/ibm/ws/session/HttpSessionFacade.invalidate(HttpSessionFacade.java:200)
at org/springframework/security/web/authentication/logout/SecurityContextLogoutHandler.logout(SecurityContextLogoutHandler.java:69)
... Spring Security SAML2 logout filter chain (unchanged) ...
```

Several things are different compared to the original Thread B:

1. **Different `valueUnbound` fires.** The original showed `com.vaadin.**flow**.server.VaadinSession.valueUnbound` (Flow line 206). The new dump shows `com.vaadin.**server**.VaadinSession.valueUnbound` (Vaadin 7 line 327). In an MPR application, the HTTP session holds **both** a Flow `VaadinSession` and a legacy Vaadin 7 `VaadinSession` as bound attributes. WebSphere fires `valueUnbound` on **every** `HttpSessionBindingListener` during `invalidate()`. In the new dump we captured the Vaadin 7 callback; the Flow callback either ran first (no deadlock thanks to the AtomicBoolean gate) or runs later in the sequence.

2. **Cross-legacy bridging.** Vaadin 7's `fireSessionDestroy(454)` calls Vaadin 7's `VaadinSession.access(1543)`. In an MPR application, MPR replaces the service with `MprServletService` whose `accessSession(163)` delegates to Flow's `VaadinService.accessSession(1970)`. So a Vaadin-7-level session destruction ends up acquiring the Flow VaadinSession lock and running the Flow access queue.

3. **Blocked inside Flow's `unlock()` push loop.** `VaadinService.accessSession(1970)` calls `ensureAccessQueuePurged(2003)`, which calls `VaadinSession.unlock()`. Flow's `unlock()` does two things at its tail: run any remaining access tasks, then iterate UIs with push enabled and call `ui.push()` to flush accumulated UIDL changes. Line 709 is that push loop.

4. **Blocks at `synchronized(lock)` in `push()`, not `disconnect()`.** Line 197 of the fixed file is `synchronized (lock) {` **inside `push(boolean async)`**, in the `else` branch taken when `disconnecting.get() == false && isConnected() == true`. Thread B read `disconnecting == false` at line 185, passed the guard at line 186, and is now waiting for the lock — held by Thread A.

- **Holds:** WebSphere HttpSession lock (from `invalidate()` — never released until the entire callback chain returns) + Flow `VaadinSession` ReentrantLock.
- **Wants:** `AtmospherePushConnection.lock`.

---

## The Lock-Ordering Cycle (Unchanged)

```
    Thread A (MPR async detach)               Thread B (SAML logout → Vaadin 7 valueUnbound → MPR bridge → Flow push loop)
    --------------------------------          -----------------------------------------------------------------------------
    HOLDS: AtmospherePushConnection.lock      HOLDS: HttpSession lock (WebSphere) + Flow VaadinSession lock
         |  disconnect()                            |  valueUnbound → fireSessionDestroy (Vaadin 7)
         |  → resource.close()                      |  → session.access() → MprServletService.accessSession
         |  → AtmosphereResourceImpl.cancel         |  → Flow VaadinService.accessSession
         |  → SessionTimeoutSupport.restoreTimeout  |  → ensureAccessQueuePurged → VaadinSession.unlock
         |  → HttpSessionFacade.getAttribute        |  → push loop → UI.push()
         v                                          v
    WANTS: HttpSession lock                    WANTS: AtmospherePushConnection.lock
         |                                          |
         +------------------> DEADLOCK <------------+
```

The cycle is identical to the original. Only Thread B's entry path on the way into `AtmospherePushConnection.lock` is different — **the Flow API it calls on that connection changed from `disconnect()` to `push()`**.

---

## Why the AtomicBoolean Fix Didn't Help Here

Fix 1a serialized **concurrent `disconnect()` calls** — exactly one thread can pass `compareAndSet(false, true)`, the rest return immediately. This correctly breaks the *original* deadlock, where both threads were in `disconnect()`:

- In the original dump, Thread B was at `UIInternals.setPushConnection(null)` → `AtmospherePushConnection.disconnect()` → `synchronized(lock)` (line 324 in 14.14.1).
- With Fix 1a, if that same Thread B now reaches `disconnect()` after Thread A has already CAS-set `disconnecting=true`, it returns immediately — no `synchronized(lock)` attempt, no block, `HttpSession.invalidate()` proceeds, HttpSession lock is released, Thread A's `resource.close()` unblocks. **That path is genuinely fixed.**

But Fix 1a does not protect against **disconnect-vs-push** concurrency:

- `push()` is called from Flow for ordinary reasons (flushing UIDL, push loop at the end of `unlock()`, etc.). It is *not* a one-shot operation and cannot be gated with a "one thread only" CAS.
- `push()` shares the *same* monitor `synchronized(lock)` with `disconnect()`. They compete for it.
- `push()` reads `disconnecting.get()` outside the monitor; between that read and the `synchronized(lock)` entry, `disconnect()` can win the CAS and grab the lock. This is a **second TOCTOU** — structurally identical to the original, just on a different method.

The bigger problem is that even "fixing" the TOCTOU in `push()` (say, with a tryLock-or-defer pattern) would not address the real issue. The real issue is **the lock-ordering cycle itself**, and that cycle exists as long as any method that holds `AtmospherePushConnection.lock` also reaches into HttpSession. Today it is `disconnect()` via `resource.close()`. Tomorrow it could be any other method that ends up calling into Atmosphere's cancellation/cleanup paths.

---

## Detailed Timeline of the New Deadlock

All `disconnecting` values in the table below refer to the `AtomicBoolean`:

| Time | Thread A (MPR async detach — `UI$3.run`) | Thread B (Vaadin 7 `valueUnbound` → Flow `unlock` → `UI.push`) | `disconnecting` | Locks held |
|------|-----------------------------------------|-----------------------------------------------------------------|-----------------|------------|
| t₀ | (about to start) | SAML logout filter → `HttpSession.invalidate()` → **acquires HttpSession lock** | `false` | B: HttpSession |
| t₁ | | Vaadin 7 `valueUnbound` → `fireSessionDestroy(454)` → `session.access(1543)` | `false` | B: HttpSession |
| t₂ | | MPR bridge → Flow `accessSession(1970)` → **acquires Flow VaadinSession lock** | `false` | B: HttpSession + Flow VS |
| t₃ | | Runs access queue → starts `VaadinSession.unlock(709)` push loop | `false` | B: HttpSession + Flow VS |
| t₄ | | Calls `UI.push(683)` → `AtmospherePushConnection.push(172)` → `push(184)` | `false` | B: HttpSession + Flow VS |
| t₅ | | **Reads `disconnecting.get() → false`** at line 185 | `false` | B: HttpSession + Flow VS |
| t₆ | | Passes `isConnected()` check, enters `else` branch | `false` | B: HttpSession + Flow VS |
| t₇ | Enters `disconnect()`, CAS succeeds, `disconnecting = true` | | **`true`** | B: HttpSession + Flow VS |
| t₈ | Enters `synchronized(lock)` ✓ | | `true` | A: PushConn.lock; B: HttpSession + Flow VS |
| t₉ | | Reaches line 197 `synchronized(lock)` → **BLOCKED** (A holds it) | `true` | same |
| t₁₀ | Calls `resource.close()` (line 363) | | `true` | same |
| t₁₁ | → `AtmosphereResourceImpl.cancel` → `SessionTimeoutSupport.restoreTimeout` → `HttpSession.getAttribute()` → **BLOCKED** (B holds HttpSession) | | `true` | same |
| | **DEADLOCK** | **DEADLOCK** | | |

Key ordering points:

- **t₅ → t₇**: Thread B reads `disconnecting` as `false` *before* Thread A sets it. This is what enables Thread B to proceed into the `else` branch. The `AtomicBoolean` write by Thread A happens afterwards but is useless — Thread B has already committed to entering the `synchronized(lock)` block.
- **t₈ before t₉**: Thread A's `synchronized(lock)` acquisition wins over Thread B's. If Thread B had won the monitor first, `push()` would have completed quickly and released the lock; Thread A would have acquired it afterwards, reached `resource.close()`, and at that point Thread B would still be holding the HttpSession lock (it's still inside `invalidate()`), so Thread A would block on the HttpSession lock — but Thread B is no longer waiting on anything, and would eventually return, release HttpSession lock, and unblock Thread A. **No deadlock in that ordering.**
- **t₁₀ → t₁₁**: Thread A must release the monitor before Thread B can acquire it. But A cannot release it until `resource.close()` returns. And `resource.close()` cannot return until HttpSession lock is released. And HttpSession lock cannot be released until Thread B finishes `invalidate()` (which needs A to release the monitor). **Cycle closed.**

The critical timing window is **t₅ → t₈**: Thread B is between "read `disconnecting`" and "enter `synchronized(lock)`" while Thread A slips in and takes the lock. This is the same *shape* of TOCTOU as the original, just on a different method.

---

## Why Both `valueUnbound` Callbacks Matter

In an MPR application, the HTTP session contains **two** `VaadinSession`-typed attributes:

1. A `com.vaadin.flow.server.VaadinSession` (Flow 14).
2. A `com.vaadin.server.VaadinSession` (Vaadin 7, kept alive by MPR).

Both implement `HttpSessionBindingListener`. When WebSphere invalidates the HTTP session, it iterates the attributes and fires `valueUnbound` on each. The **order depends on the internal iteration order** of WebSphere's `SessionData` attribute map — it is not specified by the servlet API.

- If **Flow's `valueUnbound` runs first**: it calls `fireSessionDestroy` → `removeUI` → `UIInternals.setSession(null)` → `setPushConnection(null)` → `AtmospherePushConnection.disconnect()`. With Fix 1a, if Thread A has already CAS'd, this returns immediately. If Thread A has not yet CAS'd, Flow's valueUnbound wins the CAS — but it's running on the same thread that holds HttpSession lock, so it will itself call `resource.close()` → `getAttribute()` → deadlock with *itself*. Actually this cannot reach deadlock because a thread can always reacquire a lock it already holds — **but only if the lock is a reentrant Java lock**. WebSphere's `HttpSessionImpl` uses a `synchronized` block on the same instance, which **is** reentrant for the same thread. So in this case Thread B's own call to `getAttribute()` would reenter HttpSession's monitor successfully. No deadlock here either.

- If **Vaadin 7's `valueUnbound` runs first** (the case captured in the new dump): it calls `fireSessionDestroy` → `session.access()` → *Flow* accessSession → `unlock()` push loop → `UI.push()`. There is **no** `disconnect()` call in this path; it is a regular UIDL push. If a concurrent Thread A (from a *different* thread — the MPR V7 async detach thread spawned at `UI.java:479`) is inside `disconnect()` holding `AtmospherePushConnection.lock` and waiting on the HttpSession lock, Thread B's `push()` waits on the push connection lock → **deadlock**.

So the new deadlock requires:
1. Vaadin 7 `valueUnbound` to run (or to run *before* releasing HttpSession).
2. Vaadin 7's `fireSessionDestroy` to schedule/run Flow-side work via MPR.
3. That work to reach `UI.push()` via `VaadinSession.unlock()`'s push loop.
4. Thread A (Vaadin 7 async detach, spawned by `UI.setSession(null)` at `UI.java:479`) to be running concurrently and to have entered `synchronized(lock)` inside `disconnect()`.

Thread A's existence is itself caused by session invalidation: Vaadin 7's destruction path calls `UI.setSession(null)`, which spawns the `UI$3` thread. Thread A and Thread B are **siblings** triggered by the same `invalidate()`. The race is inherent to the architecture.

### The two paths reach the same `AtmospherePushConnection` instance

Confirmed from the decompiled `MprPushConnection.java`:

```java
public void push() {
    if (this.flowUI.isClosing()) { /* log and skip */ }
    else {
        getFlowPushConnection().filter(PushConnection::isConnected).ifPresent(PushConnection::push);
    }
}
public void disconnect() {
    getFlowPushConnection().filter(PushConnection::isConnected).ifPresent(PushConnection::disconnect);
}
private Optional<PushConnection> getFlowPushConnection() {
    return Optional.ofNullable(flowUI).map(UI::getInternals).map(UIInternals::getPushConnection);
}
```

`MprPushConnection` is a thin wrapper that delegates to the Flow UI's internal `AtmospherePushConnection`. For a given paired (V7-UI, Flow-UI) there is **one** `AtmospherePushConnection` — the Vaadin 7 UI's `MprPushConnection` and the Flow UI itself both point at it.

- Thread A reaches that instance via `MprPushConnection.disconnect()` → `AtmospherePushConnection.disconnect()`.
- Thread B reaches the **same** instance by calling `UI.push()` on the **Flow** UI, bypassing `MprPushConnection` entirely. Flow's `UI.push` talks directly to its `AtmospherePushConnection`.

Note that `MprPushConnection.push()` has an `isClosing()` guard that could in principle short-circuit pushes on a closing UI. That guard is **not** on the Thread B path — Flow's `UI.push` → `AtmospherePushConnection.push` never goes through `MprPushConnection`, so `isClosing()` is never consulted. Even if it were, relying on it would not be robust: it's a best-effort check, not synchronised with `disconnect()`. The only sound fix is structural (Fix 1b).

---

## What Makes This Fix-Resistant Without Fix 1b

The lock-ordering cycle has two legs. To break it, you need to break *either* leg:

### Leg 1 — forward: `AtmospherePushConnection.lock` → `HttpSession` lock

Held in `disconnect()` at `resource.close()`. **Still present in 14.14.2.**

Breakable by:
- **Fix 1b** — move `resource.close()` outside `synchronized(lock)`. (Structural, clean.)
- Making `resource.close()` itself not touch `HttpSession`. (Requires changing Atmosphere / upstream.)
- Pre-detaching the HttpSession from `AtmosphereResource` before `close()`. (Possible but fiddly.)

### Leg 2 — reverse: `HttpSession` lock → `AtmospherePushConnection.lock`

Held whenever a session-destruction callback acquires `AtmospherePushConnection.lock`. This can happen via **many** callers in the MPR+Flow stack:

- `UIInternals.setPushConnection(null)` → `disconnect()`. ← fixed by 1a (on Flow side).
- Flow's `VaadinSession.unlock()` push loop → `UI.push()` → `AtmospherePushConnection.push()`. ← **NEW deadlock**, not fixed.
- Any code path that pushes or disconnects a connected `AtmospherePushConnection` from inside a session-invalidation callback. There could be others.

Breakable only by either:
- Never calling into `AtmospherePushConnection` from inside an `invalidate()` callback (very invasive — you would have to change how MPR bridges V7 session destruction into Flow, and how Flow's `unlock()` behaves when called transitively from `invalidate()`).
- Or, breaking **Leg 1** — so the forward direction doesn't exist and the cycle is impossible regardless of how many callers take the reverse leg.

Leg 1 is a single, well-defined, local change. Leg 2 has many callers and they are not all controllable (`unlock()`'s push loop is core Flow behaviour invoked from everywhere). **The only maintainable fix is to break Leg 1, which is what Fix 1b does.**

---

## Recommended Fix (Now Mandatory)

Apply **Fix 1b from RECOMMENDED_FIX.md** on top of the 14.14.2 Fix 1a.

The key change is to capture `resource` into a local, mark the connection as disconnected inside the lock, then `close()` the resource **outside** `synchronized(lock)`:

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
                    getLogger().info("Timeout waiting for messages...", e);
                } catch (Exception e) {
                    getLogger().info("Error waiting for messages...", e);
                }
                outgoingMessage = null;
            }
            // Capture the resource ref and clear internal state under the lock,
            // but DEFER the actual close() to outside the lock.
            resourceToClose = resource;
            connectionLost();       // sets resource=null, state=DISCONNECTED
        }
    } finally {
        if (resourceToClose == null) {
            disconnecting.set(false);
        }
    }

    if (resourceToClose != null) {
        try {
            resourceToClose.close();   // NOW outside synchronized(lock) — no lock-order cycle
        } catch (IOException e) {
            getLogger().info("Error when closing push connection", e);
        } finally {
            disconnecting.set(false);
        }
    }
}
```

### Why this resolves the new deadlock

- Thread A enters `disconnect()`, CAS succeeds, enters `synchronized(lock)`, captures `resourceToClose`, calls `connectionLost()` (sets `resource = null`, `state = DISCONNECTED`), **releases the lock**.
- Thread A now calls `resourceToClose.close()` → `HttpSession.getAttribute()` → blocked on HttpSession lock (still held by Thread B).
- Thread B's `push()` can now **acquire** `AtmospherePushConnection.lock` (no one holds it). It re-reads state: `isConnected()` is false because `connectionLost()` has set the state to DISCONNECTED. `push()` sees the connection is no longer connected and takes the "disconnected" branch (sets state to PUSH_PENDING/RESPONSE_PENDING). It releases the lock and returns.
- Thread B continues up its stack: `UI.push()` returns, `VaadinSession.unlock()` finishes its push loop, `ensureAccessQueuePurged` returns, Vaadin 7's `session.access()` returns, `fireSessionDestroy(454)` returns, `valueUnbound` returns. WebSphere's `invalidate()` continues processing, eventually **releases the HttpSession lock**.
- Thread A's `getAttribute()` call unblocks, `resource.close()` completes, `disconnect()` returns.

No circular wait is possible because **Thread A never holds `AtmospherePushConnection.lock` while waiting on the HttpSession lock**. Leg 1 is broken.

### Additional fix — reset `disconnecting` on the "already disconnected" early return

While making Fix 1b, also fix the latent bug:

```java
if (!isConnected() || resource == null) {
    getLogger().debug("Disconnection already happened, ignoring request");
    disconnecting.set(false);    // <-- add this
    return;
}
```

Without this, a race between two `disconnect()` callers where the first one completes while the second is waiting on the monitor would leave `disconnecting == true` forever.

### Concurrency safety of Fix 1b

- `connectionLost()` runs inside the lock and sets `resource = null` + `state = DISCONNECTED`. Any concurrent `push()` that subsequently acquires the lock sees the disconnected state and takes the short path. Any subsequent `disconnect()` enters and returns at the `!isConnected() || resource == null` check.
- `outgoingMessage.get(1000ms)` stays inside the lock. It has its own timeout, does not touch HttpSession, and is not part of any known lock cycle.
- `resourceToClose.close()` is called without locks, from a single thread (the one that won the CAS). Atmosphere's `AtmosphereResourceImpl.close()` is itself thread-safe against its own concurrent callers (it has internal `isClosed`/cancel guards), and is routinely called from timeout threads, so calling it unlocked is supported.
- `push()` will not race with `close()` on the captured `resource` local, because we set `this.resource = null` before exiting the lock. The `resourceToClose` reference is only visible to Thread A.

---

## Complementary / Alternative Fixes

Fix 1b is sufficient on its own. Evaluating the other options from `RECOMMENDED_FIX.md` against this new deadlock:

| Fix | Helps with the new deadlock? | Notes |
|---|---|---|
| **Fix 1a** (already applied) | Partially — only disconnect-vs-disconnect. | Keep it; it closes the original path cleanly. |
| **Fix 1b** (move `close()` outside lock) | **Yes — eliminates Leg 1 of the cycle.** | Required. |
| **Fix 2** (async push disconnect in `UIInternals.setSession`) | **No.** Thread B in the new dump is in `unlock()`'s push loop, not `UIInternals.setSession`. | Still a good defence-in-depth for the original Flow path, but does not address the new case. |
| **Fix 3** (async disconnect in `MprPushConnection`) | **No.** Thread A is already on a dedicated async thread (`UI$3.run`). Wrapping it in yet another thread only shifts the locking onto the new thread; `resource.close()` is still inside `synchronized(lock)` on whichever thread executes it. | Do not pursue. |

**Recommended disposition:**

1. Apply **Fix 1b** in flow-server and release as 14.14.3 (or the equivalent Flow 2.13.x patch).
2. Also apply the `disconnecting.set(false)` reset on the early-disconnect return (latent bug).
3. Keep Fix 1a (already shipped).
4. Consider Fix 2 as a separate, longer-term defence-in-depth for consistency with the Vaadin 7 async detach pattern — but it is not required for this customer's deadlock.

---

## Validation Suggestions

Before shipping, verify Fix 1b with targeted tests / reasoning:

1. **Unit test: concurrent disconnect + push.** Simulate one thread inside `disconnect()` between lock release and `resource.close()`, and another thread calling `push()`. Confirm `push()` sees disconnected state and returns without blocking.
2. **Replication on WebSphere.** Re-run `reproduce-deadlock.sh` / `DeadlockTriggerServlet` on WebSphere 9.0.5.25 with the customer's exact stack. The forced mode in `DeadlockTriggerServlet` currently only races `disconnect()` vs `invalidate()`; extend it to also race `push()` (explicit `ui.push()` call while `disconnect()` is in progress) to prove the new case is reproducible pre-fix and gone post-fix.
3. **MPR integration smoke test.** Exercise the full MPR logout flow (legacy UI detach + Flow UI detach + SAML2 logout) under load and confirm no deadlocks after the fix.
4. **Check for other call sites holding `AtmospherePushConnection.lock` across a container callback.** Grep flow-server for `synchronized (lock)` in `AtmospherePushConnection` and follow any callee that could re-enter HttpSession (or any other shared container lock). After Fix 1b the only code paths inside the synchronised block are in-memory operations and `outgoingMessage.get(1000ms)` — none of those touch HttpSession — which makes Leg 1 structurally absent.

---

## Summary for the GitHub Issue Update

> **Update (14.14.2):** The `AtomicBoolean` gate on `disconnect()` prevents the disconnect-vs-disconnect race but does not break the lock-ordering cycle between `AtmospherePushConnection.lock` and the WebSphere `HttpSession` lock. In the new customer thread dumps, Thread B is now blocked inside `AtmospherePushConnection.push()` (line 197) reached from `VaadinSession.unlock()`'s push loop, which is invoked transitively from Vaadin 7's `valueUnbound` via the MPR service bridge. The monitor is the same one `disconnect()` uses, so the same deadlock occurs — just through `push()` instead of `disconnect()`. The `AtomicBoolean` fix does not apply to `push()`, and cannot, because `push()` must legitimately acquire the monitor to send UIDL.
>
> The correct fix is **Fix 1b**: move `resource.close()` **outside** `synchronized(lock)` in `disconnect()`. This structurally removes the forward leg of the lock-ordering cycle and is robust against any future caller that reaches `AtmospherePushConnection` from within a session-invalidation callback.
