# SAML2 IdP Setup for Deadlock Reproduction

This directory contains everything needed to set up a SAML2 Identity Provider
(Keycloak) for testing the Spring Security SAML2 logout path that triggers
the deadlock on WebSphere.

## Important: SAML2 is Optional for the Deadlock

**The deadlock is triggered by `HttpSession.invalidate()` — not by SAML2 itself.**

The default form-login mode (`user`/`user`) triggers the exact same deadlock
because `SecurityContextLogoutHandler.logout()` calls `HttpSession.invalidate()`
regardless of the authentication mechanism.

Use SAML2 only if you need the exact same stack trace as the customer's
Thread B (with `Saml2LogoutRequestFilter` and `Saml2LogoutResponseFilter` in
the filter chain).

## Quick Start (Form Login — No IdP Required)

```bash
# Build and run with embedded Tomcat (for development/verification)
cd mpr-v7-web-xml
mvn spring-boot:run

# Open http://localhost:8080/mprdemo/
# Login: user / user
# Click "Logout" to trigger HttpSession.invalidate()
```

> **Note:** The deadlock only occurs on WebSphere, not on embedded Tomcat.
> Use form login to verify the app works, then deploy the WAR to WebSphere.

## SAML2 Setup with Keycloak

### Prerequisites

- Docker and Docker Compose
- Java 11+ (required by OpenSAML 4 / spring-security-saml2-service-provider)

### Step 1: Generate SP Credentials

```bash
cd mpr-v7-web-xml
bash saml2/generate-sp-credentials.sh
```

This creates `src/main/resources/credentials/sp-key.pem` and `sp-cert.pem`.

### Step 2: Start Keycloak

```bash
cd saml2
docker compose up -d

# Wait for Keycloak to start (~30 seconds)
# Admin console: http://localhost:8180  (admin / admin)
```

### Step 3: Configure Keycloak

```bash
cd mpr-v7-web-xml
bash saml2/keycloak-setup.sh
```

This creates:
- Realm: `mprdemo`
- SAML2 client configured for the SP
- Test user: `testuser` / `testpass`

### Step 4: Run with SAML2 Profile

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=saml2
```

Open http://localhost:8080/mprdemo/ — you'll be redirected to Keycloak for login.

### Step 5: Deploy to WebSphere

```bash
# Build WAR
mvn clean package -DskipTests

# Or build EAR
bash build-ear.sh

# Deploy to WebSphere and test logout
```

## How the SAML2 Logout Path Matches Thread B

The customer's Thread B stack trace (simplified):

```
SecurityContextLogoutHandler.logout()          ← calls HttpSession.invalidate()
  CompositeLogoutHandler.logout()
    LogoutFilter.doFilter()
      Saml2LogoutResponseFilter.doFilterInternal()  ← SAML2 filter (pass-through)
        Saml2LogoutRequestFilter.doFilterInternal()  ← SAML2 filter (pass-through)
          FilterChainProxy$VirtualFilterChain.doFilter()
            Spring Security filter chain...
```

With the `saml2` profile active, the Spring Security filter chain includes
`Saml2LogoutRequestFilter` and `Saml2LogoutResponseFilter`, matching this
stack trace exactly.

## URLs Reference

| Endpoint | URL |
|---|---|
| App | http://localhost:8080/mprdemo/ |
| Logout | http://localhost:8080/mprdemo/logout |
| SAML2 metadata | http://localhost:8080/mprdemo/saml2/service-provider-metadata/keycloak |
| SAML2 SLO | http://localhost:8080/mprdemo/logout/saml2/slo |
| Keycloak admin | http://localhost:8180 |
| Keycloak IdP metadata | http://localhost:8180/realms/mprdemo/protocol/saml/descriptor |

## Cleanup

```bash
cd saml2
docker compose down -v
```
