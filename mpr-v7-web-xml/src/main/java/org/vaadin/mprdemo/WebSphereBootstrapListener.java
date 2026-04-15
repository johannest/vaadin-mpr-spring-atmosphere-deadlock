package org.vaadin.mprdemo;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;

/**
 * Fallback bootstrap listener for IBM WebSphere Traditional.
 * <p>
 * On standard Servlet 3.0+ containers, Spring Boot is bootstrapped via the
 * {@link javax.servlet.ServletContainerInitializer} (SCI) mechanism: the
 * container discovers {@code SpringServletContainerInitializer} from
 * {@code spring-web.jar}'s {@code META-INF/services/} file, which in turn
 * finds the {@link Application} class (a {@code WebApplicationInitializer})
 * and calls its {@code onStartup()} method.
 * <p>
 * <b>WebSphere Traditional often fails to scan JARs inside
 * {@code WEB-INF/lib/} for SCI entries</b>, especially under the default
 * PARENT_FIRST classloader policy.  When this happens, the Spring
 * {@code ApplicationContext} is never created and every request fails with:
 * <pre>
 *   No WebApplicationContext found: no ContextLoaderListener
 *   or DispatcherServlet registered?
 * </pre>
 * <p>
 * This listener is declared in {@code web.xml} (which WebSphere always
 * processes reliably).  It checks whether the Spring context was already
 * created by the SCI mechanism.  If not, it calls
 * {@link Application#onStartup(ServletContext)} directly as a fallback.
 * If the SCI did run, this listener is a no-op.
 */
public class WebSphereBootstrapListener implements ServletContextListener {

    private static final Logger log =
            LoggerFactory.getLogger(WebSphereBootstrapListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext sc = sce.getServletContext();

        // Check if the SCI mechanism already created the Spring context
        Object existing = sc.getAttribute(
                WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
        if (existing != null) {
            log.info("Spring ApplicationContext already initialized via SCI "
                    + "— WebSphere fallback listener is not needed");
            return;
        }

        log.warn("Spring ApplicationContext NOT found after container "
                + "initialization — the Servlet 3.0+ SCI mechanism did not "
                + "run.  Bootstrapping Spring Boot explicitly via this "
                + "listener.  To fix the root cause, set the application "
                + "classloader to PARENT_LAST in the WebSphere admin console.");

        try {
            // This calls SpringBootServletInitializer.onStartup() which:
            //   1. Calls configure(builder) → builder.sources(Application.class)
            //   2. Creates the AnnotationConfigServletWebServerApplicationContext
            //   3. Registers it as the ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE
            //   4. Auto-configures Spring Security, Vaadin, etc.
            new Application().onStartup(sc);
        } catch (ServletException e) {
            throw new RuntimeException(
                    "Failed to bootstrap Spring Boot context on WebSphere. "
                    + "Ensure the classloader is set to PARENT_LAST and that "
                    + "all Spring Boot dependencies are in WEB-INF/lib/.", e);
        }

        log.info("Spring ApplicationContext bootstrapped successfully "
                + "via WebSphere fallback listener");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Cleanup is handled by SpringBootContextLoaderListener which was
        // registered by SpringBootServletInitializer.onStartup()
    }
}
