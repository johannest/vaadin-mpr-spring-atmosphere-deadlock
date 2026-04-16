package org.vaadin.mprdemo;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Spring Security configuration for the deadlock reproducer.
 * <p>
 * Supports two modes (controlled by Spring profiles):
 * <ul>
 *   <li><b>Default (no profile)</b>: Form-based login with in-memory users.
 *       Logout calls {@code HttpSession.invalidate()} which triggers the
 *       deadlock on WebSphere.</li>
 *   <li><b>"saml2" profile</b>: SAML2 login via Keycloak IdP. Logout goes
 *       through SAML2 Single Logout (SLO) which also invalidates the
 *       HttpSession, matching the customer's exact Thread B stack trace.</li>
 * </ul>
 * <p>
 * <b>The deadlock mechanism is identical in both modes</b> — it is
 * {@code SecurityContextLogoutHandler.logout()} calling
 * {@code HttpSession.invalidate()} that triggers the deadlock, regardless
 * of whether authentication uses form login or SAML2.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Injected only when the "saml2" profile is active (see
     * {@link Saml2Config}). Null otherwise.
     */
    @Autowired(required = false)
    private RelyingPartyRegistrationRepository relyingPartyRegistrations;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // --- Vaadin compatibility ------------------------------------------
        // Vaadin has its own CSRF protection; disable Spring's to avoid
        // double-token issues.
        http.csrf().disable();

        // Allow iframes from same origin (Vaadin uses iframes internally).
        http.headers().frameOptions().sameOrigin();

        // --- Authorization -------------------------------------------------
        http.authorizeRequests()
                // Vaadin static resources
                .antMatchers(
                        "/VAADIN/**",
                        "/frontend/**",
                        "/frontend-es5/**",
                        "/frontend-es6/**",
                        "/icons/**",
                        "/sw.js",
                        "/manifest.webmanifest",
                        "/offline.html",
                        "/sw-runtime-resources-precache.js"
                ).permitAll()
                // Vaadin internal requests (UIDL, heartbeat, push)
                .requestMatchers(SecurityConfig::isVaadinInternalRequest)
                .permitAll()
                // Login page
                .antMatchers("/login", "/login/**").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated();

        // --- Logout (THE DEADLOCK TRIGGER) ---------------------------------
        // SecurityContextLogoutHandler.logout() calls
        // HttpSession.invalidate() which on WebSphere acquires the
        // HttpSession lock and then tries to acquire the
        // AtmospherePushConnection lock → DEADLOCK with Thread A.
        http.logout(logout -> logout
                .invalidateHttpSession(true)       // <-- the critical line
                .logoutSuccessUrl("/login?logout")
                .permitAll());

        // --- Authentication ------------------------------------------------
        if (relyingPartyRegistrations != null) {
            // SAML2 mode: Saml2LogoutRequestFilter + Saml2LogoutResponseFilter
            // will appear in the filter chain, matching the customer's stack trace.
            http.saml2Login(withDefaults());
            http.saml2Logout(withDefaults());
        } else {
            // Form login mode: simpler, no external IdP required.
            // Triggers the same deadlock because the same
            // SecurityContextLogoutHandler is invoked on logout.
            http.formLogin(form -> form
                    .loginPage("/login")
                    .permitAll());
        }

        return http.build();
    }

    /**
     * Completely bypass the Spring Security filter chain for the deadlock
     * test endpoint.  This avoids any interference from security filters
     * during session invalidation (the test endpoint invalidates the
     * HttpSession as part of the deadlock reproduction).
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().antMatchers("/deadlock-test");
    }

    /**
     * In-memory user for form-login mode. Not used in SAML2 mode.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        @SuppressWarnings("deprecation")
        UserDetails user = User.withDefaultPasswordEncoder()
                .username("user")
                .password("user")
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Detects Vaadin's internal framework requests (UIDL, heartbeat, push)
     * so they can bypass Spring Security authentication.
     */
    private static boolean isVaadinInternalRequest(HttpServletRequest request) {
        String vaadinRequestType = request.getParameter("v-r");
        return vaadinRequestType != null;
    }
}
