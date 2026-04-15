package org.vaadin.mprdemo;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.PushConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test servlet that triggers the deadlock between
 * {@code AtmospherePushConnection.lock} and WebSphere's internal
 * {@code HttpSession} lock.
 * <p>
 * <b>Prerequisites:</b> The user must be logged in and have navigated to the
 * main page so that a Vaadin session with an active push connection exists.
 * <p>
 * <b>Three modes:</b>
 * <dl>
 *   <dt>{@code ?mode=forced} (default)</dt>
 *   <dd>Creates two threads that race for the deadlock:
 *     <ul>
 *       <li><b>Thread A</b>: calls {@code pushConnection.disconnect()} which
 *           enters {@code synchronized(lock)} then calls
 *           {@code resource.close()} &rarr;
 *           {@code HttpSession.getAttribute()} &mdash; blocked on WebSphere
 *           because Thread B holds the HttpSession lock.</li>
 *       <li><b>Thread B</b>: calls {@code httpSession.invalidate()} which
 *           acquires the HttpSession lock, fires session-destruction callbacks,
 *           eventually reaches {@code AtmospherePushConnection.disconnect()}
 *           &rarr; {@code synchronized(lock)} &mdash; blocked because
 *           Thread A holds it.</li>
 *     </ul>
 *   </dd>
 *
 *   <dt>{@code ?mode=natural}</dt>
 *   <dd>Simply invalidates the HTTP session on a separate thread, relying on
 *       Vaadin 7's built-in async detach thread (spawned by
 *       {@code UI.setSession(null)}) to create the race naturally. This is
 *       the exact code path that triggers the deadlock in production.</dd>
 *
 *   <dt>{@code ?mode=status}</dt>
 *   <dd>Checks the JVM for existing deadlocks via {@code ThreadMXBean} and
 *       lists any threads from previous test runs that are still alive.</dd>
 * </dl>
 * <p>
 * <b>Parameters:</b>
 * <ul>
 *   <li>{@code timeout} &mdash; deadlock detection timeout in ms (default: 15000)</li>
 *   <li>{@code delay} &mdash; ms Thread A waits before calling disconnect,
 *       giving Thread B time to acquire the HttpSession lock (default: 5)</li>
 * </ul>
 * <p>
 * <b>WARNING:</b> A successful deadlock permanently blocks two threads. The
 * only recovery is restarting the application server.
 *
 * @see <a href="../../../../../../DEADLOCK_ANALYSIS.md">DEADLOCK_ANALYSIS.md</a>
 */
@WebServlet(urlPatterns = "/deadlock-test", asyncSupported = true)
public class DeadlockTriggerServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger log =
            LoggerFactory.getLogger(DeadlockTriggerServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        resp.setContentType("text/plain; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");
        PrintWriter out = resp.getWriter();

        String mode = req.getParameter("mode");
        if (mode == null) mode = "forced";

        long timeout = parseLong(req.getParameter("timeout"), 15_000);
        long delay   = parseLong(req.getParameter("delay"), 5);

        out.println("========================================================");
        out.println("  MPR + WebSphere Deadlock Reproduction Test");
        out.println("========================================================");
        out.println("Mode           : " + mode);
        out.println("Timeout        : " + timeout + " ms");
        out.println("Thread-A delay : " + delay + " ms");
        out.println("Server         : " + req.getServletContext().getServerInfo());
        out.println("Session ID     : "
                + (req.getSession(false) != null
                   ? req.getSession(false).getId() : "none"));
        out.println();

        switch (mode) {
            case "forced":
                runForcedMode(req, resp, out, timeout, delay);
                break;
            case "natural":
                runNaturalMode(req, resp, out, timeout);
                break;
            case "status":
                runStatusCheck(out);
                break;
            default:
                resp.setStatus(400);
                printUsage(out);
        }

        out.flush();
    }

    /* ------------------------------------------------------------------ */
    /*  Forced mode                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Forced mode: creates two threads that reproduce the exact deadlock
     * scenario described in the analysis document.
     * <p>
     * Thread B starts first and calls {@code httpSession.invalidate()}.
     * On WebSphere this acquires the HttpSession's internal lock and then
     * processes destruction callbacks (a long call chain through
     * VaadinSession / UIInternals).
     * <p>
     * Thread A starts a few milliseconds later and calls
     * {@code pushConnection.disconnect()} which enters
     * {@code synchronized(lock)} on the AtmospherePushConnection, then
     * calls {@code resource.close()} which reaches
     * {@code HttpSession.getAttribute()}.  On WebSphere, that blocks
     * because Thread B holds the HttpSession lock.
     * <p>
     * Meanwhile Thread B's callback chain reaches
     * {@code AtmospherePushConnection.disconnect()} and tries to enter
     * {@code synchronized(lock)} — blocked because Thread A holds it.
     * <p>
     * Result: classic lock-ordering deadlock.
     */
    private void runForcedMode(HttpServletRequest req,
                               HttpServletResponse resp,
                               PrintWriter out,
                               long timeout,
                               long threadADelay) throws IOException {

        // --- Validate preconditions ----------------------------------------
        HttpSession httpSession = req.getSession(false);
        if (httpSession == null) {
            resp.setStatus(400);
            out.println("ERROR: No HTTP session.");
            out.println("Log in first, then navigate to the main page.");
            return;
        }

        VaadinSession vaadinSession = findVaadinSession(httpSession);
        if (vaadinSession == null) {
            resp.setStatus(400);
            out.println("ERROR: No VaadinSession found in HTTP session.");
            out.println("Navigate to the main application page (the one with");
            out.println("the Vaadin 7 legacy component) so that a VaadinSession");
            out.println("and push connection are created.");
            return;
        }

        PushConnection pushConnection = null;
        int uiId = -1;
        String pcClass = "?";

        vaadinSession.lock();
        try {
            for (UI ui : vaadinSession.getUIs()) {
                PushConnection pc = ui.getInternals().getPushConnection();
                if (pc != null && pc.isConnected()) {
                    pushConnection = pc;
                    uiId = ui.getUIId();
                    pcClass = pc.getClass().getSimpleName();
                    break;
                }
            }
        } finally {
            vaadinSession.unlock();
        }

        if (pushConnection == null) {
            resp.setStatus(400);
            out.println("ERROR: No active PushConnection found.");
            out.println("Make sure the main page is open in a browser tab");
            out.println("(the push WebSocket/long-poll must be connected).");
            return;
        }

        out.println("VaadinSession  : found");
        out.println("PushConnection : " + pcClass + " (UI #" + uiId + ")");
        out.println();
        out.println("---- Starting deadlock race ----");
        out.println();
        out.println("Thread A (push disconnect):");
        out.println("  pushConnection.disconnect()");
        out.println("    -> synchronized(lock)              [ACQUIRES]");
        out.println("    -> resource.close()");
        out.println("    -> HttpSession.getAttribute()      [BLOCKED on WebSphere]");
        out.println();
        out.println("Thread B (session invalidate):");
        out.println("  httpSession.invalidate()");
        out.println("    -> HttpSession lock                [ACQUIRES]");
        out.println("    -> VaadinSession.valueUnbound()");
        out.println("    -> ... -> AtmospherePushConnection.disconnect()");
        out.println("    -> synchronized(lock)              [BLOCKED]");
        out.println();

        // --- Create and start the two threads ------------------------------
        final PushConnection pc = pushConnection;
        final long delayMs = threadADelay;
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                // Give Thread B time to call invalidate() and acquire
                // the HttpSession lock before we enter synchronized(lock).
                Thread.sleep(delayMs);
                log.warn("[DEADLOCK-TEST] Thread A: calling "
                        + "pushConnection.disconnect()");
                pc.disconnect();
                log.info("[DEADLOCK-TEST] Thread A: disconnect() returned "
                        + "normally (no deadlock)");
            } catch (Throwable t) {
                errA.set(t);
                log.error("[DEADLOCK-TEST] Thread A: exception", t);
            }
        }, "DEADLOCK-TEST-A-push-disconnect");

        Thread threadB = new Thread(() -> {
            try {
                log.warn("[DEADLOCK-TEST] Thread B: calling "
                        + "httpSession.invalidate()");
                httpSession.invalidate();
                log.info("[DEADLOCK-TEST] Thread B: invalidate() returned "
                        + "normally (no deadlock)");
            } catch (Throwable t) {
                errB.set(t);
                log.error("[DEADLOCK-TEST] Thread B: exception", t);
            }
        }, "DEADLOCK-TEST-B-session-invalidate");

        long t0 = System.currentTimeMillis();

        // Thread B first — it needs to grab the HttpSession lock.
        threadB.start();
        // Tiny sleep so Thread B is scheduled and enters invalidate().
        sleepQuietly(2);
        // Thread A second — it should enter synchronized(lock) while
        // Thread B is still inside the callback chain.
        threadA.start();

        // --- Wait for both with timeout ------------------------------------
        try {
            threadA.join(timeout);
            // Use remaining time for Thread B
            long remaining = timeout - (System.currentTimeMillis() - t0);
            if (remaining > 0) {
                threadB.join(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsed = System.currentTimeMillis() - t0;
        boolean aAlive = threadA.isAlive();
        boolean bAlive = threadB.isAlive();
        Thread.State aState = threadA.getState();
        Thread.State bState = threadB.getState();

        out.println("---- Results (after " + elapsed + " ms) ----");
        out.println();
        out.println("Thread A: alive=" + aAlive + "  state=" + aState);
        out.println("Thread B: alive=" + bAlive + "  state=" + bState);
        if (errA.get() != null) out.println("Thread A error: " + errA.get());
        if (errB.get() != null) out.println("Thread B error: " + errB.get());
        out.println();

        // --- Interpret results ---------------------------------------------
        if (aAlive && bAlive) {
            resp.setStatus(500);
            out.println("****************************************************");
            out.println("*           DEADLOCK DETECTED                      *");
            out.println("****************************************************");
            out.println();
            out.println("Both threads are permanently blocked.");
            out.println("Lock ordering inversion confirmed:");
            out.println("  Thread A HOLDS: AtmospherePushConnection.lock");
            out.println("  Thread A WANTS: HttpSession lock (getAttribute)");
            out.println("  Thread B HOLDS: HttpSession lock (invalidate)");
            out.println("  Thread B WANTS: AtmospherePushConnection.lock");
            out.println();
            dumpThreadStacks(out, threadA, threadB);
            detectDeadlockViaJMX(out);
            out.println();
            out.println("WARNING: These threads are permanently deadlocked.");
            out.println("Restart the application server to recover.");
        } else if (aAlive || bAlive) {
            resp.setStatus(500);
            out.println("PARTIAL HANG — one thread is stuck.");
            out.println("This may indicate a deadlock with a non-test thread.");
            out.println();
            dumpThreadStacks(out, threadA, threadB);
            detectDeadlockViaJMX(out);
        } else {
            resp.setStatus(200);
            out.println("No deadlock detected — both threads completed.");
            out.println();
            out.println("The race window was missed: Thread B's callback chain");
            out.println("reached AtmospherePushConnection.disconnect() before");
            out.println("Thread A could enter synchronized(lock), or vice versa.");
            out.println();
            out.println("On WebSphere, try adjusting the delay parameter:");
            out.println("  /deadlock-test?delay=1   (shorter — Thread A faster)");
            out.println("  /deadlock-test?delay=10  (longer  — Thread A slower)");
            out.println();
            out.println("Note: On Tomcat/Jetty this test will NEVER deadlock");
            out.println("because they don't synchronize on HttpSession.");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Natural mode                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Natural mode: invalidates the HTTP session on a separate thread,
     * relying on the actual application code paths to create the race.
     * <p>
     * During {@code httpSession.invalidate()} on WebSphere:
     * <ol>
     *   <li>WebSphere holds the HttpSession lock</li>
     *   <li>Fires {@code VaadinSession.valueUnbound()}</li>
     *   <li>{@code fireSessionDestroy()} iterates UIs</li>
     *   <li>For the V7 UI: {@code UI.setSession(null)} spawns an async
     *       thread that calls {@code MprPushConnection.disconnect()}</li>
     *   <li>For the Flow UI: {@code UIInternals.setSession(null)} calls
     *       {@code AtmospherePushConnection.disconnect()} synchronously</li>
     * </ol>
     * Steps 4 and 5 race against each other on the same
     * {@code AtmospherePushConnection.lock}, creating the deadlock when the
     * V7 async thread (step 4) enters the lock before the Flow sync path
     * (step 5) does.
     */
    private void runNaturalMode(HttpServletRequest req,
                                HttpServletResponse resp,
                                PrintWriter out,
                                long timeout) throws IOException {

        HttpSession httpSession = req.getSession(false);
        if (httpSession == null) {
            resp.setStatus(400);
            out.println("ERROR: No HTTP session. Log in first.");
            return;
        }

        VaadinSession vs = findVaadinSession(httpSession);
        if (vs == null) {
            resp.setStatus(400);
            out.println("ERROR: No VaadinSession. Navigate to the main page first.");
            return;
        }

        boolean hasPush = false;
        vs.lock();
        try {
            for (UI ui : vs.getUIs()) {
                PushConnection pc = ui.getInternals().getPushConnection();
                if (pc != null && pc.isConnected()) {
                    hasPush = true;
                    break;
                }
            }
        } finally {
            vs.unlock();
        }

        if (!hasPush) {
            resp.setStatus(400);
            out.println("ERROR: No active push connection.");
            out.println("Open the main page in a browser tab first.");
            return;
        }

        out.println("Calling httpSession.invalidate() on a separate thread...");
        out.println("This triggers both the V7 async detach (Thread A) and the");
        out.println("Flow sync push disconnect (Thread B) through the normal");
        out.println("application code paths.");
        out.println();

        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread invalidator = new Thread(() -> {
            try {
                log.warn("[DEADLOCK-TEST] Natural mode: calling "
                        + "httpSession.invalidate()");
                httpSession.invalidate();
                log.info("[DEADLOCK-TEST] Natural mode: invalidate() returned");
            } catch (Throwable t) {
                err.set(t);
                log.error("[DEADLOCK-TEST] Natural mode: exception", t);
            }
        }, "DEADLOCK-TEST-natural-invalidator");

        long t0 = System.currentTimeMillis();
        invalidator.start();

        try {
            invalidator.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsed = System.currentTimeMillis() - t0;

        if (invalidator.isAlive()) {
            resp.setStatus(500);
            out.println("****************************************************");
            out.println("*           DEADLOCK DETECTED (natural)            *");
            out.println("****************************************************");
            out.println();
            out.println("httpSession.invalidate() has been blocked for "
                    + elapsed + " ms.");
            out.println("The V7 async detach thread and the Flow sync");
            out.println("disconnect path are deadlocked.");
            out.println();
            dumpThreadStacks(out, invalidator);
            dumpDeadlockTestThreads(out);
            detectDeadlockViaJMX(out);
            out.println();
            out.println("WARNING: Threads are permanently deadlocked.");
            out.println("Restart the application server to recover.");
        } else {
            resp.setStatus(200);
            out.println("Completed in " + elapsed + " ms — no deadlock.");
            if (err.get() != null) {
                out.println("Exception: " + err.get());
            }
            out.println();
            out.println("The natural race window is narrow. In production,");
            out.println("it occurs under load when thread scheduling causes");
            out.println("the V7 async detach thread to run before the Flow");
            out.println("sync path completes.");
            out.println();
            out.println("Try the forced mode for deterministic reproduction:");
            out.println("  /deadlock-test?mode=forced");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Status check                                                       */
    /* ------------------------------------------------------------------ */

    private void runStatusCheck(PrintWriter out) {
        out.println("---- JVM Deadlock Detection ----");
        out.println();
        detectDeadlockViaJMX(out);
        out.println();

        out.println("---- Active DEADLOCK-TEST Threads ----");
        out.println();
        boolean found = dumpDeadlockTestThreads(out);
        if (!found) {
            out.println("(none)");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Finds the VaadinSession stored in the HttpSession.
     * The attribute name pattern varies by servlet name, so we iterate all
     * attributes and check the type.
     */
    private VaadinSession findVaadinSession(HttpSession httpSession) {
        try {
            Enumeration<String> attrs = httpSession.getAttributeNames();
            while (attrs.hasMoreElements()) {
                String name = attrs.nextElement();
                Object value = httpSession.getAttribute(name);
                if (value instanceof VaadinSession) {
                    return (VaadinSession) value;
                }
            }
        } catch (IllegalStateException e) {
            // Session already invalidated
            log.debug("Session already invalidated while searching for "
                    + "VaadinSession", e);
        }
        return null;
    }

    /**
     * Prints stack traces of the given threads.
     */
    private void dumpThreadStacks(PrintWriter out, Thread... threads) {
        out.println("---- Thread Stack Traces ----");
        for (Thread t : threads) {
            if (t == null) continue;
            out.println();
            out.println("\"" + t.getName() + "\"  state=" + t.getState()
                    + "  alive=" + t.isAlive());
            for (StackTraceElement ste : t.getStackTrace()) {
                out.println("    at " + ste);
            }
        }
        out.println();
    }

    /**
     * Finds and dumps all threads whose name starts with "DEADLOCK-TEST".
     *
     * @return true if any were found
     */
    private boolean dumpDeadlockTestThreads(PrintWriter out) {
        boolean found = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("DEADLOCK-TEST")) {
                found = true;
                out.println("\"" + t.getName() + "\"  state=" + t.getState()
                        + "  alive=" + t.isAlive());
                for (StackTraceElement ste : t.getStackTrace()) {
                    out.println("    at " + ste);
                }
                out.println();
            }
        }
        return found;
    }

    /**
     * Uses the JVM's {@link ThreadMXBean} to detect deadlocks.
     * <p>
     * {@code findDeadlockedThreads()} detects both monitor-based
     * ({@code synchronized}) and owned-synchronizer-based
     * ({@code ReentrantLock}) deadlocks.
     * <p>
     * Note: WebSphere may use internal locks that the JVM cannot track,
     * in which case this method won't detect the deadlock even though it
     * exists.
     */
    private void detectDeadlockViaJMX(PrintWriter out) {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();

        long[] deadlockedIds = mxBean.findDeadlockedThreads();
        if (deadlockedIds == null) {
            // Fall back to monitor-only detection
            deadlockedIds = mxBean.findMonitorDeadlockedThreads();
        }

        if (deadlockedIds != null && deadlockedIds.length > 0) {
            out.println("JVM DETECTED DEADLOCK — " + deadlockedIds.length
                    + " thread(s):");
            out.println();
            ThreadInfo[] infos =
                    mxBean.getThreadInfo(deadlockedIds, true, true);
            for (ThreadInfo info : infos) {
                if (info != null) {
                    out.println(info.toString());
                }
            }
        } else {
            out.println("ThreadMXBean: no deadlock detected by JVM.");
            out.println("(WebSphere internal locks may not be visible to JMX —");
            out.println(" check thread states and stack traces manually.)");
        }
    }

    private void printUsage(PrintWriter out) {
        out.println("Usage:");
        out.println("  /deadlock-test                  forced mode (default)");
        out.println("  /deadlock-test?mode=natural     natural mode");
        out.println("  /deadlock-test?mode=status      check existing deadlocks");
        out.println();
        out.println("Parameters:");
        out.println("  delay=N     Thread-A delay in ms (default: 5)");
        out.println("  timeout=N   deadlock timeout in ms (default: 15000)");
    }

    private static long parseLong(String s, long defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
