# Deadlock Analysis: Vaadin MPR + Flow on IBM WebSphere 9.0.5.25

## Environment

| Component | Version |
|---|---|
| IBM WebSphere | 9.0.5.25 |
| Vaadin Flow | 14.14.1 (flow-server 2.13.1) |
| Vaadin 7 (legacy) | 7.7.41 |
| Vaadin Multiplatform Runtime (MPR) | 2.3.0 |
| Spring Security | (with SAML2 logout) |

## Summary

A classic lock-ordering deadlock occurs between two locks:

1. **AtmospherePushConnection's internal `lock`** (`synchronized (lock)` in `AtmospherePushConnection.java:324`)
2. **WebSphere's internal HttpSession lock** (implicit synchronization inside `HttpSessionImpl`)

Thread A acquires them in order `AtmospherePushConnection.lock` -> `HttpSession lock`.
Thread B acquires them in reverse order `HttpSession lock` -> `AtmospherePushConnection.lock`.

Both threads operate on the **same** `AtmospherePushConnection` instance — Thread A reaches it via the MPR `MprPushConnection` wrapper, and Thread B reaches it via Flow's `UIInternals`.

## Detailed Thread Analysis

### Thread A — Legacy Vaadin 7 UI Detach (async disconnect thread)

**Trigger:** The legacy Vaadin 7 UI is being detached. Vaadin 7's `UI.setSession(null)` (`UI.java:446`) intentionally spawns a **new thread** to disconnect the push connection without holding the VaadinSession lock. This is a deliberate deadlock-avoidance pattern (see Vaadin 7 tickets [#18436](https://dev.vaadin.com/ticket/18436) and [#16919](https://dev.vaadin.com/ticket/16919)).

**Call chain:**

```
Thread.run()                                          // New thread spawned at UI.java:469
  UI$3.run()                                          // UI.java:479 — calls setPushConnection(null)
    AbstractMprUI.setPushConnection()                 // AbstractMprUI.java:96 — delegates to super
      UI.setPushConnection(null)                      // UI.java:1604 — calls pushConnection.disconnect()
        MprPushConnection.disconnect()                // MprPushConnection.java:57 — delegates to Flow
          AtmospherePushConnection.disconnect()       // AtmospherePushConnection.java:324
            synchronized (lock) {                     // *** ACQUIRES AtmospherePushConnection.lock ***
              resource.close()                        // AtmospherePushConnection.java:358
                AtmosphereResourceImpl.close()
                  AtmosphereResourceImpl.cancel()
                    SessionTimeoutSupport.restoreTimeout()
                      SessionTimeoutSupport.get()
                        HttpSessionFacade.getAttribute()
                          SessionData.getAttribute()
                            HttpSessionImpl.getAttribute()   // *** BLOCKED — waiting for HttpSession lock ***
```

**Locks held:** AtmospherePushConnection's internal `lock`
**Waiting for:** WebSphere's HttpSession lock (inside `HttpSessionImpl.getAttribute`)

### Thread B — Spring Security SAML Logout / Session Invalidation

**Trigger:** A SAML2 logout response is processed by Spring Security. The `SecurityContextLogoutHandler` calls `HttpSession.invalidate()`, which causes WebSphere to fire session destruction callbacks while holding its internal HttpSession lock.

**Call chain:**

```
WebSphere HTTP request handling thread
  Spring Security filter chain
    LogoutFilter.doFilter()
      SecurityContextLogoutHandler.logout()
        HttpSessionFacade.invalidate()                       // *** ACQUIRES HttpSession lock (WebSphere internal) ***
          SessionData.invalidate()
            MemorySession.invalidate()
              StoreCallback.sessionInvalidated()
                SessionEventDispatcher.sessionDestroyed()
                  HttpSessionObserver.sessionDestroyed()
                    VaadinSession.valueUnbound()              // VaadinSession.java:206
                      VaadinService.fireSessionDestroy()      // VaadinService.java:601
                        session.access(() -> { ... })         // Enqueues task
                          VaadinService.accessSession()       // VaadinService.java:1970
                            ensureAccessQueuePurged()         // tryLock succeeds — acquires VaadinSession lock
                              session.unlock()
                                runPendingAccessTasks()        // VaadinService.java:2038
                                  // Runs the lambda from fireSessionDestroy:
                                  ui.accessSynchronously()    // UI.java:428
                                    session.lock()            // Reentrant — succeeds
                                      session.removeUI(ui)    // VaadinSession.java:611
                                        ui.getInternals().setSession(null)  // UIInternals.java:408
                                          setPushConnection(null)
                                            this.pushConnection.disconnect()  // UIInternals.java:456
                                              AtmospherePushConnection.disconnect()
                                                synchronized (lock) {    // *** BLOCKED — waiting for AtmospherePushConnection.lock ***
```

**Locks held:** WebSphere's HttpSession lock + VaadinSession lock (ReentrantLock)
**Waiting for:** AtmospherePushConnection's internal `lock`

## Lock Ordering Diagram

```
    Thread A                              Thread B
    --------                              --------
    HOLDS: AtmospherePushConnection.lock  HOLDS: HttpSession lock (WebSphere)
           |                                     |
           |  resource.close()                   |  VaadinSession.valueUnbound()
           |  -> Atmosphere                      |  -> fireSessionDestroy()
           |  -> SessionTimeoutSupport           |  -> removeUI() -> setSession(null)
           |  -> HttpSession.getAttribute()      |  -> setPushConnection(null)
           v                                     v
    WANTS: HttpSession lock (WebSphere)   WANTS: AtmospherePushConnection.lock
           |                                     |
           +------------> DEADLOCK <-------------+
```

## Root Causes

### 1. `AtmospherePushConnection.disconnect()` calls `resource.close()` inside `synchronized (lock)`

The `disconnect()` method in `AtmospherePushConnection.java:312-367` holds its internal `lock` while calling `resource.close()` at line 358. The Atmosphere framework's close path reaches back into the servlet container via `SessionTimeoutSupport.restoreTimeout()` -> `HttpSession.getAttribute()`. On WebSphere, this implicitly acquires the HttpSession's internal lock.

This creates a hidden lock dependency: `AtmospherePushConnection.lock` -> `HttpSession lock`.

### 2. Flow's `UIInternals.setSession(null)` disconnects push synchronously

Unlike Vaadin 7's `UI.setSession(null)` which spawns a dedicated thread for push disconnection (UI.java:469-481), Flow's `UIInternals.setSession(null)` calls `setPushConnection(null)` synchronously at line 408. When this code runs inside a session invalidation callback (where WebSphere already holds the HttpSession lock), it creates the reverse lock dependency: `HttpSession lock` -> `AtmospherePushConnection.lock`.

### 3. WebSphere synchronizes on HttpSession during both `getAttribute()` and `invalidate()`

This is container-specific behavior. WebSphere's `HttpSessionImpl` uses internal synchronization for session attribute access and invalidation, including during the destruction callbacks. Most other servlet containers (e.g., Tomcat) do not exhibit this behavior, which is why this deadlock is WebSphere-specific.

### 4. MPR bridges two disconnect paths to the same `AtmospherePushConnection`

The MPR `MprPushConnection` is a wrapper that delegates to the Flow `AtmospherePushConnection`. When the legacy Vaadin 7 UI detaches, it disconnects through `MprPushConnection` (Thread A). When the Flow session is destroyed, it disconnects through `UIInternals.setPushConnection(null)` (Thread B). Both paths converge on the same `AtmospherePushConnection` instance, creating a race for its `lock`.

## Why Vaadin 7's Deadlock Avoidance is Insufficient

Vaadin 7 already recognized the risk of deadlocks during push disconnection. The comment at `UI.java:471-478` reads:

> *"This intentionally does disconnect without locking the VaadinSession to avoid deadlocks where the server uses a lock for the websocket connection"*

However, this avoidance only prevents a `VaadinSession lock` -> `websocket lock` deadlock. In the MPR scenario on WebSphere, the deadlock involves `AtmospherePushConnection.lock` and `HttpSession lock` — neither of which is the VaadinSession lock. The async thread pattern avoids one deadlock but does not prevent this different one.

## Existing Mitigation: the `disconnecting` Flag (TOCTOU Race)

`AtmospherePushConnection.disconnect()` already contains a mitigation for this exact deadlock — a `volatile boolean disconnecting` flag checked at the top of the method:

```java
public void disconnect() {
    // "This also prevents potential deadlocks if the container acquires
    //  locks during operations on HTTP session, as closing the
    //  AtmosphereResource may cause HTTP session access"
    if (disconnecting) {               // ← CHECK  (outside synchronized)
        return;
    }

    synchronized (lock) {              // ← ACQUIRE LOCK
        // ...
        try {
            disconnecting = true;      // ← SET FLAG (inside synchronized)
            // ...
            resource.close();          // ← calls HttpSession.getAttribute()
        } finally {
            disconnecting = false;
        }
    }
}
```

The comment shows the Vaadin developers were aware of the deadlock risk and intended this flag to prevent it. **The flag does dramatically narrow the race window, but it cannot eliminate it** because of a classic check-then-act (TOCTOU) race condition:

1. The flag is **read** outside `synchronized(lock)`.
2. The flag is **written** inside `synchronized(lock)`, after the lock is acquired.

This means there is a window — between a thread acquiring `synchronized(lock)` and setting `disconnecting = true` — during which another thread can read the flag as `false` and proceed to block on `synchronized(lock)`.

### Concrete TOCTOU timeline for the deadlock

| Time | Thread A (push disconnect) | Thread B (session invalidate) | `disconnecting` |
|------|---------------------------|-------------------------------|-----------------|
| t₁ | Reads `disconnecting` → `false` | *(inside `httpSession.invalidate()`, holds HttpSession lock, processing callbacks)* | `false` |
| t₂ | Enters `synchronized(lock)` ✓ | | `false` |
| t₃ | | Reads `disconnecting` → **`false`** *(Thread A hasn't set it yet)* | `false` |
| t₄ | Sets `disconnecting = true` | | **`true`** |
| t₅ | Calls `resource.close()` → `getAttribute()` → **BLOCKED** *(HttpSession lock held by B)* | Enters `synchronized(lock)` → **BLOCKED** *(held by A)* | `true` |
| | **DEADLOCK** | **DEADLOCK** | |

At **t₃**, Thread B reads `disconnecting` after Thread A has acquired the lock (t₂) but before Thread A has set the flag (t₄). The `volatile` keyword guarantees visibility of writes, but Thread B's read at t₃ precedes Thread A's write at t₄ in real time, so Thread B sees `false`.

### How narrow is the window?

Between t₂ (lock acquired) and t₄ (flag set), the code executes only:

```java
synchronized (lock) {                              // t₂
    if (!isConnected() || resource == null) { ... } // ~two field reads
    try {
        disconnecting = true;                      // t₄
```

This is on the order of **nanoseconds**. The flag therefore reduces the deadlock probability by orders of magnitude compared to having no guard at all. But "extremely unlikely per attempt" still means "inevitable over enough attempts in production" — particularly on busy WebSphere servers with many concurrent SAML logouts.

### Why it still happens in the customer's environment

- **Volume:** Thousands of SAML logouts per day × nanosecond window = eventual occurrence.
- **IBM J9 JVM:** WebSphere's JVM and thread scheduler may have different timing characteristics than HotSpot, subtly widening the window.
- **Multi-socket NUMA servers:** A `volatile` write on one CPU socket may take additional nanoseconds to become visible on another socket's L1 cache, enlarging the window.

### Correct fix

Replacing the `volatile boolean` with `AtomicBoolean.compareAndSet(false, true)` **before** the `synchronized` block eliminates the TOCTOU entirely — exactly one thread can ever pass the gate. This alone is sufficient to prevent the deadlock: the second thread returns immediately from `disconnect()`, allowing `HttpSession.invalidate()` to complete and release the HttpSession lock, after which the first thread's `resource.close()` → `getAttribute()` proceeds without contention.

Moving `resource.close()` outside `synchronized(lock)` is a recommended additional improvement (defense in depth) — it eliminates the lock-ordering dependency structurally, reduces lock holding time, and protects against any future code path that might bypass the `compareAndSet` gate. But it is not required for correctness.

See `RECOMMENDED_FIX.md` for the detailed fix proposals.
