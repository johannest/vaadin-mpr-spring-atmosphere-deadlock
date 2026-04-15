package org.vaadin.mprdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Spring Boot entry point.
 * <p>
 * Extends {@link SpringBootServletInitializer} so the application works both:
 * <ul>
 *   <li>Embedded (mvn spring-boot:run) — for quick local development</li>
 *   <li>WAR deployed to WebSphere — for deadlock reproduction</li>
 * </ul>
 * <p>
 * {@code @ServletComponentScan} picks up the MPR legacy servlet from
 * {@code com.vaadin.mpr} (needed for Vaadin 7 compatibility layer).
 */
@SpringBootApplication
@ServletComponentScan({"com.vaadin.mpr", "org.vaadin.mprdemo"})
public class Application extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
