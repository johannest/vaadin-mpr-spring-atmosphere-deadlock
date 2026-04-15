# Replication Guide: Deadlock on IBM WebSphere

## Prerequisites

- Docker installed and running
- IBM WebSphere 9.0.5.x Docker image (e.g., `ibmcom/websphere-traditional:9.0.5.x-ubi`)
- A Vaadin 14 + MPR application with:
  - Vaadin Flow 14.14.x (flow-server 2.13.x)
  - Vaadin MPR 2.3.x with a legacy Vaadin 7 UI
  - Push enabled (WebSocket or long polling)
  - Spring Security with session-invalidating logout (e.g., SAML2, or basic form logout)

## Step 1: Set Up WebSphere Docker Container

```bash
# Pull the WebSphere traditional image
docker pull ibmcom/websphere-traditional:9.0.5.25-ubi

# Start a container with admin console access
docker run -d \
  --name websphere-deadlock-test \
  -p 9443:9443 \
  -p 9043:9043 \
  -p 9080:9080 \
  ibmcom/websphere-traditional:9.0.5.25-ubi

# Wait for WebSphere to start (check logs)
docker logs -f websphere-deadlock-test
# Wait until you see "Server [...] open for e-business"

# Retrieve the admin password
docker exec websphere-deadlock-test cat /tmp/PASSWORD
```

Access the admin console at `https://localhost:9043/ibm/console` (user: `wsadmin`).

## Step 2: Build a Minimal Reproducer Application

Create a minimal Vaadin 14 + MPR application with the following characteristics:

### 2.1 Maven dependencies (key ones)

```xml
<!-- Vaadin 14 BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.vaadin</groupId>
            <artifactId>vaadin-bom</artifactId>
            <version>14.14.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Flow / Vaadin 14 -->
    <dependency>
        <groupId>com.vaadin</groupId>
        <artifactId>vaadin-spring-boot-starter</artifactId>
    </dependency>

    <!-- MPR -->
    <dependency>
        <groupId>com.vaadin</groupId>
        <artifactId>mpr-v7</artifactId>
        <version>2.3.0</version>
    </dependency>

    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
</dependencies>
```

### 2.2 Enable Push

In your Flow configuration (e.g., AppShellConfigurator or servlet init parameter):

```java
@Push(transport = Transport.WEBSOCKET_XHR)
public class Application implements AppShellConfigurator {
    // ...
}
```

### 2.3 Create a Legacy Vaadin 7 UI via MPR

```java
public class LegacyUI extends AbstractMprUI {
    @Override
    protected void init(VaadinRequest request) {
        super.init(request);
        // Add some legacy component that will be visible
        setContent(new com.vaadin.ui.Label("Legacy Vaadin 7 UI"));
    }
}
```

### 2.4 Spring Security with Session Invalidation on Logout

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            )
            .logout(logout -> logout
                // This is the key: Spring's default logout handler
                // calls HttpSession.invalidate()
                .invalidateHttpSession(true)
                .logoutSuccessUrl("/login?logout")
            );
        return http.build();
    }
}
```

### 2.5 Package as WAR for WebSphere

Ensure your `pom.xml` produces a WAR:

```xml
<packaging>war</packaging>
```

And extend `SpringBootServletInitializer`:

```java
public class Application extends SpringBootServletInitializer
        implements AppShellConfigurator {
    // ...
}
```

## Step 3: Deploy to WebSphere

```bash
# Copy WAR to container
docker cp target/your-app.war websphere-deadlock-test:/tmp/

# Deploy via admin console at https://localhost:9043/ibm/console
# Applications -> New Application -> New Enterprise Application
# Select /tmp/your-app.war and follow the wizard

# Or deploy via wsadmin scripting:
docker exec -it websphere-deadlock-test /opt/IBM/WebSphere/AppServer/bin/wsadmin.sh \
  -lang jython \
  -c "AdminApp.install('/tmp/your-app.war', ['-appname', 'deadlock-test', '-contextroot', '/app'])"
docker exec -it websphere-deadlock-test /opt/IBM/WebSphere/AppServer/bin/wsadmin.sh \
  -lang jython \
  -c "AdminConfig.save()"
```

Start the application from the admin console.

## Step 4: Reproduce the Deadlock

The deadlock requires two concurrent operations:

1. **A legacy Vaadin 7 UI being detached** (triggers Thread A — async push disconnect)
2. **The HTTP session being invalidated** (triggers Thread B — session destroy chain)

### Approach A: Manual Reproduction

1. Open the application in a browser and log in. Navigate to a view that renders the legacy Vaadin 7 UI.
2. Verify push is active (check browser dev tools Network tab for a WebSocket or long-polling connection).
3. Open a **second browser tab** to the same session (same session cookie).
4. In one tab, trigger a logout (which calls `HttpSession.invalidate()`).
5. The race condition occurs when the legacy UI's detach (Thread A) and the session invalidation (Thread B) overlap.

**Manual reproduction is unreliable** because the window for the race is small. Use the programmatic approach below.

### Approach B: Programmatic Reproduction with Controlled Timing

Add a test endpoint or a custom filter that creates a controlled race:

```java
/**
 * Test-only servlet filter that creates a controlled race condition
 * to reproduce the deadlock. Remove after testing.
 *
 * The filter intercepts requests to /trigger-deadlock-test and:
 * 1. Spawns a thread that triggers the legacy UI detach path
 * 2. After a short delay, invalidates the HTTP session
 */
@WebFilter("/trigger-deadlock-test")
public class DeadlockTestFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpSession httpSession = httpReq.getSession(false);

        if (httpSession == null) {
            ((HttpServletResponse) response).sendError(400, "No session");
            return;
        }

        // Find the VaadinSession
        // (attribute name pattern: com.vaadin.server.VaadinSession.<servletName>)
        VaadinSession vaadinSession = null;
        java.util.Enumeration<String> attrs = httpSession.getAttributeNames();
        while (attrs.hasMoreElements()) {
            String name = attrs.nextElement();
            Object value = httpSession.getAttribute(name);
            if (value instanceof VaadinSession) {
                vaadinSession = (VaadinSession) value;
                break;
            }
        }

        if (vaadinSession == null) {
            ((HttpServletResponse) response).sendError(400, "No VaadinSession");
            return;
        }

        final VaadinSession vs = vaadinSession;

        // Thread simulating the legacy UI detach path (Thread A).
        // This mimics what Vaadin 7 UI.setSession(null) does at UI.java:469
        Thread threadA = new Thread(() -> {
            try {
                // Small delay to let Thread B start
                Thread.sleep(50);
                vs.lock();
                try {
                    for (UI ui : vs.getUIs()) {
                        // Trigger push disconnect through MPR chain
                        PushConnection pc = ui.getInternals().getPushConnection();
                        if (pc != null && pc.isConnected()) {
                            // This will enter AtmospherePushConnection.synchronized(lock)
                            // then call resource.close() -> HttpSession.getAttribute()
                            pc.disconnect();
                        }
                    }
                } finally {
                    vs.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "deadlock-test-threadA");

        // Thread B: invalidate the session (triggers WebSphere's
        // HttpSession lock -> VaadinSession.valueUnbound -> ... ->
        // AtmospherePushConnection.disconnect)
        Thread threadB = new Thread(() -> {
            try {
                // Start invalidation
                httpSession.invalidate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "deadlock-test-threadB");

        // Start both threads to create the race
        threadA.start();
        threadB.start();

        // Wait with timeout to detect deadlock
        try {
            threadA.join(10000);
            threadB.join(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean deadlocked = threadA.isAlive() || threadB.isAlive();

        HttpServletResponse httpResp = (HttpServletResponse) response;
        httpResp.setContentType("text/plain");
        if (deadlocked) {
            httpResp.setStatus(500);
            httpResp.getWriter().write("DEADLOCK DETECTED - threads still alive after 10s\n");
            httpResp.getWriter().write("Thread A alive: " + threadA.isAlive() + "\n");
            httpResp.getWriter().write("Thread B alive: " + threadB.isAlive() + "\n");
        } else {
            httpResp.setStatus(200);
            httpResp.getWriter().write("No deadlock detected in this attempt.\n");
            httpResp.getWriter().write("Try multiple times — the race window is small.\n");
        }
    }
}
```

Then hit `https://localhost:9443/app/trigger-deadlock-test` repeatedly (e.g., with a loop):

```bash
for i in $(seq 1 50); do
  echo "Attempt $i:"
  curl -k -b cookies.txt -c cookies.txt https://localhost:9443/app/trigger-deadlock-test
  echo ""
  # Re-login if session was invalidated
  curl -k -c cookies.txt -d "username=user&password=pass" https://localhost:9443/app/login
done
```

### Approach C: Thread Dump Analysis (Post-Mortem)

If you cannot trigger the deadlock on demand, configure WebSphere to take periodic thread dumps and wait for it to occur naturally during logout operations:

```bash
# Take a thread dump of the WebSphere JVM
docker exec websphere-deadlock-test \
  /opt/IBM/WebSphere/AppServer/java/8.0/bin/jcmd 1 Thread.print -l > threaddump.txt

# Or use kill -3 to trigger a dump to stderr:
docker exec websphere-deadlock-test kill -3 $(docker exec websphere-deadlock-test pgrep -f java)
docker logs websphere-deadlock-test 2>&1 | grep -A 200 "Full thread dump"
```

Look for threads in `BLOCKED` state waiting on each other:
- One thread at `AtmospherePushConnection.disconnect` (line 324 — `synchronized (lock)`)
- Another thread at `HttpSessionImpl.getAttribute` (inside `resource.close()`)

## Step 5: Verify the Deadlock

### JVM deadlock detection

The JVM can automatically detect monitor-based deadlocks. Use `jstack` or `jcmd`:

```bash
# Get the PID of the Java process inside the container
docker exec websphere-deadlock-test pgrep -f java

# Take thread dump with deadlock detection
docker exec websphere-deadlock-test \
  /opt/IBM/WebSphere/AppServer/java/8.0/bin/jstack -l <PID>
```

Look for a section like:
```
Found one Java-level deadlock:
=============================
"deadlock-test-threadA":
  waiting to lock monitor 0x... (a com.ibm.ws.session.http.HttpSessionImpl)
  which is held by "deadlock-test-threadB"
"deadlock-test-threadB":
  waiting to lock monitor 0x... (a java.lang.Object)
  which is held by "deadlock-test-threadA"
```

**Note:** WebSphere's internal HttpSession lock may be implemented as a `synchronized` block on the `HttpSessionImpl` instance itself, or as a `ReentrantLock`. JVM deadlock detection only detects `synchronized`-based deadlocks automatically. If WebSphere uses `ReentrantLock`, you will need to inspect thread states manually.

### Manual detection

If the JVM doesn't report a deadlock (e.g., mixed lock types), look for both threads in `BLOCKED` or `WAITING` state in the thread dump, cross-reference the lock objects, and verify the circular dependency.

## Key Observations for Reproduction

1. **WebSphere-specific:** This deadlock does NOT reproduce on Tomcat or Jetty because they do not synchronize on the HttpSession during `getAttribute()` and `invalidate()`. You must use WebSphere.

2. **Push must be active:** The `AtmospherePushConnection` must be connected (a WebSocket or long-poll connection must be established).

3. **MPR must be active:** The `MprPushConnection` wrapper must be in place (a legacy Vaadin 7 UI must be loaded in the session).

4. **Concurrent session invalidation:** The session must be invalidated (e.g., by Spring Security logout, or by session timeout) while a push disconnect is in progress.

5. **Timing-sensitive:** The race window is the overlap between `AtmospherePushConnection.disconnect()` entering its `synchronized (lock)` block and the session invalidation callback reaching `UIInternals.setPushConnection(null)`. Running the test in a loop increases the chance of hitting it.
