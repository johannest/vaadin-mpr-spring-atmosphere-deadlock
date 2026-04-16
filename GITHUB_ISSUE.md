# AtmospherePushConnection.disconnect() deadlocks with containers that synchronize on HttpSession

## Description

`AtmospherePushConnection.disconnect()` holds its internal `synchronized(lock)` while calling `resource.close()`, which reaches `HttpSession.getAttribute()` through Atmosphere's `SessionTimeoutSupport.restoreTimeout()`. On containers that synchronize on the HttpSession internally (IBM WebSphere), this creates a lock-ordering deadlock when a concurrent `HttpSession.invalidate()` triggers a second `disconnect()` call through the session destruction callback chain.

The existing `volatile boolean disconnecting` guard was added to prevent this (the comment says so), but it has a TOCTOU race condition that makes it ineffective under load.

## Environment

- **Vaadin Flow** 2.13.1 (Vaadin 14.14.1) — but the code is unchanged in current versions
- **IBM WebSphere Traditional** 9.0.5.25
- **Vaadin MPR** 2.3.0 with Vaadin 7.7.41 legacy UI
- **Spring Security** (SAML2 logout calls `HttpSession.invalidate()`)

The deadlock is container-specific: WebSphere synchronizes on `HttpSessionImpl` during both `getAttribute()` and `invalidate()`. Tomcat/Jetty do not, so they are not affected.

## Deadlock mechanism

Two threads call `AtmospherePushConnection.disconnect()` on the **same instance**, acquiring two locks in opposite order:

```
Thread A (push disconnect):
  AtmospherePushConnection.disconnect()
    synchronized(lock)                        ← ACQUIRES
      resource.close()
        → Atmosphere SessionTimeoutSupport.restoreTimeout()
        → HttpSession.getAttribute()          ← BLOCKED (HttpSession lock held by B)

Thread B (session invalidation callback chain):
  HttpSession.invalidate()                    ← ACQUIRES HttpSession lock
    → VaadinSession.valueUnbound()
    → VaadinService.fireSessionDestroy()
    → VaadinSession.removeUI()
    → UIInternals.setSession(null)
    → setPushConnection(null)
    → AtmospherePushConnection.disconnect()
      synchronized(lock)                      ← BLOCKED (held by A)

                        DEADLOCK
```

Thread A holds `AtmospherePushConnection.lock`, wants HttpSession lock.
Thread B holds HttpSession lock, wants `AtmospherePushConnection.lock`.

In the MPR scenario, Thread A is spawned by Vaadin 7's `UI.setSession(null)` (which intentionally uses an async thread for a different deadlock avoidance — see [#18436](https://dev.vaadin.com/ticket/18436)), while Thread B continues synchronously through Flow's `UIInternals.setSession(null)`. Both are triggered by the same `HttpSession.invalidate()` during logout.

## Why the existing `disconnecting` flag doesn't prevent it

The `disconnect()` method already has a guard ([source](https://github.com/vaadin/flow/blob/main/flow-server/src/main/java/com/vaadin/flow/server/communication/AtmospherePushConnection.java)):

```java
public void disconnect() {
    if (disconnecting) {           // CHECK  — outside synchronized
        return;
    }
    synchronized (lock) {          // ACQUIRE LOCK
        // ...
        try {
            disconnecting = true;  // SET FLAG — inside synchronized
            // ...
            resource.close();      // → HttpSession.getAttribute()
        } finally {
            disconnecting = false;
        }
    }
}
```

The comment on this check explicitly references the deadlock scenario:

> *"This also prevents potential deadlocks if the container acquires locks during operations on HTTP session, as closing the AtmosphereResource may cause HTTP session access"*

However, this is a **TOCTOU (time-of-check-time-of-use) race**: the flag is read *outside* the `synchronized` block but written *inside* it. There is a window between acquiring the lock and setting the flag where a second thread reads `false` and proceeds to block on `synchronized(lock)`:

```
Thread A:  if (disconnecting) → false         // passes check
Thread A:  synchronized(lock) {               // acquires lock
Thread B:  if (disconnecting) → false         // passes check (flag not yet set!)
Thread A:      disconnecting = true;          // set — too late for Thread B
Thread A:      resource.close() → BLOCKED     // waiting for HttpSession lock
Thread B:  synchronized(lock) → BLOCKED       // waiting for Thread A's lock
                         DEADLOCK
```

The window is narrow (~nanoseconds: two field reads between lock acquisition and the volatile write), so the flag makes the deadlock very unlikely per individual attempt. But under production load (thousands of SAML logouts/day), the window is eventually hit.

## Proposed fix (minimal)

Replace `volatile boolean disconnecting` with `AtomicBoolean` and use `compareAndSet` as the entry gate — **this alone is sufficient to prevent the deadlock**:

```java
// Field:
private final AtomicBoolean disconnecting = new AtomicBoolean(false);

// Method:
@Override
public void disconnect() {
    if (!disconnecting.compareAndSet(false, true)) {
        getLogger().debug("Disconnection already in progress, ignoring request");
        return;
    }
    synchronized (lock) {
        // ... rest of method unchanged ...
        try {
            // (no longer need "disconnecting = true" here — already set above)
            // ...
            resource.close();
            connectionLost();
        } finally {
            disconnecting.set(false);
        }
    }
}
```

### Why this is sufficient

With `compareAndSet`, exactly one thread passes the gate. The deadlock is broken because the second thread never reaches `synchronized(lock)`:

1. **Thread A** wins `compareAndSet` → enters `synchronized(lock)` → `resource.close()` → `getAttribute()` → **blocked** (HttpSession lock held by B)
2. **Thread B** loses `compareAndSet` → **returns immediately** → `invalidate()` callback chain continues → `invalidate()` completes → **releases HttpSession lock**
3. **Thread A** unblocks → completes normally

The key: Thread B returning from `disconnect()` allows `HttpSession.invalidate()` to finish and release the HttpSession lock. Thread A then proceeds without contention. No deadlock.

### Additional changes

- `push()` reads `disconnecting` — change `if (disconnecting || !isConnected())` to `if (disconnecting.get() || !isConnected())` (same volatile-read semantics).
- `readObject()` should reinitialize: `disconnecting = new AtomicBoolean(false);`

## Recommended additional improvement

Moving `resource.close()` outside `synchronized(lock)` is not required for correctness, but is recommended as defense in depth:

- Eliminates the lock-ordering dependency structurally — protects against future code paths that might bypass the `compareAndSet` gate
- Reduces lock holding time — `resource.close()` may involve network I/O
- Protects against other container-internal locks in Atmosphere's close path

```java
@Override
public void disconnect() {
    if (!disconnecting.compareAndSet(false, true)) {
        return;
    }
    AtmosphereResource resourceToClose = null;
    try {
        synchronized (lock) {
            if (!isConnected() || resource == null) { return; }
            // ... wait for outgoing messages ...
            resourceToClose = resource;
            connectionLost(); // sets resource=null, state=DISCONNECTED
        }
    } finally {
        if (resourceToClose == null) disconnecting.set(false);
    }
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
