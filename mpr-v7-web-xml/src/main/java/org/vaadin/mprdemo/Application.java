package org.vaadin.mprdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Spring Boot entry point.
 * <p>
 * Extends {@link SpringBootServletInitializer} so the application works both:
 * <ul>
 *   <li>Embedded ({@code mvn spring-boot:run}) — for quick local development</li>
 *   <li>WAR deployed to WebSphere — for deadlock reproduction</li>
 * </ul>
 * <p>
 * <b>Important for WebSphere:</b> The {@link #configure} override is required.
 * Without it WebSphere's SCI mechanism cannot discover which class bootstraps
 * the Spring ApplicationContext, resulting in:
 * <pre>
 *   Error 500: java.lang.IllegalStateException:
 *   No WebApplicationContext found: no ContextLoaderListener or DispatcherServlet registered?
 * </pre>
 * <p>
 * {@code @ServletComponentScan} picks up the MPR legacy servlet from
 * {@code com.vaadin.mpr} (needed for Vaadin 7 compatibility layer).
 */
@SpringBootApplication
@ServletComponentScan({"com.vaadin.mpr", "org.vaadin.mprdemo"})
public class Application extends SpringBootServletInitializer {

    /**
     * WAR deployment entry point. Called by the servlet container (WebSphere)
     * via {@code SpringServletContainerInitializer} (Servlet 3.0+ SCI).
     * <p>
     * Explicitly registers this class as the configuration source so that
     * WebSphere can find it regardless of classloader scanning order.
     */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(Application.class);
    }

    /** Embedded-server entry point ({@code mvn spring-boot:run}). */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
