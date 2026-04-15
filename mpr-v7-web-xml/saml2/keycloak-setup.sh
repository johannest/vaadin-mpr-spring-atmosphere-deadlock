#!/bin/bash
# =============================================================================
# Configures Keycloak as a SAML2 IdP for the MPR deadlock reproducer.
#
# Prerequisites:
#   - Keycloak running (docker compose up -d)
#   - SP credentials generated (bash saml2/generate-sp-credentials.sh)
#
# This script uses the Keycloak Admin REST API to:
#   1. Create the "mprdemo" realm
#   2. Create a SAML2 client matching the Spring Security SP configuration
#   3. Create a test user (testuser / testpass)
#
# Usage:
#   bash saml2/keycloak-setup.sh
# =============================================================================
set -euo pipefail

KC_URL="http://localhost:8180"
KC_ADMIN="admin"
KC_PASS="admin"
REALM="mprdemo"

# The SP entity ID and endpoints must match what Spring Security generates
# with context-path=/mprdemo and registration-id=keycloak
APP_BASE_URL="http://localhost:8080/mprdemo"
SP_ENTITY_ID="${APP_BASE_URL}/saml2/service-provider-metadata/keycloak"
SP_ACS_URL="${APP_BASE_URL}/login/saml2/sso/keycloak"
SP_SLO_URL="${APP_BASE_URL}/logout/saml2/slo"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SP_CERT_FILE="$PROJECT_DIR/src/main/resources/credentials/sp-cert.pem"

# ---- Helpers ----
wait_for_keycloak() {
    echo "Waiting for Keycloak to be ready..."
    for i in $(seq 1 60); do
        if curl -sf "${KC_URL}/health/ready" > /dev/null 2>&1; then
            echo "Keycloak is ready."
            return 0
        fi
        sleep 2
    done
    echo "ERROR: Keycloak did not become ready within 120 seconds."
    exit 1
}

get_admin_token() {
    curl -sf -X POST "${KC_URL}/realms/master/protocol/openid-connect/token" \
        -d "client_id=admin-cli" \
        -d "username=${KC_ADMIN}" \
        -d "password=${KC_PASS}" \
        -d "grant_type=password" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

kc_api() {
    local method="$1"
    local path="$2"
    shift 2
    curl -sf -X "$method" "${KC_URL}/admin/realms${path}" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        "$@"
}

# ---- Main ----
wait_for_keycloak

echo "Obtaining admin token..."
TOKEN=$(get_admin_token)

# 1. Create realm
echo "Creating realm '${REALM}'..."
kc_api POST "" -d "{
    \"realm\": \"${REALM}\",
    \"enabled\": true,
    \"sslRequired\": \"none\"
}" 2>/dev/null || echo "  (realm may already exist, continuing)"

# Refresh token for the new realm
TOKEN=$(get_admin_token)

# 2. Read SP certificate (strip PEM headers, join lines)
if [ ! -f "$SP_CERT_FILE" ]; then
    echo "ERROR: SP certificate not found at $SP_CERT_FILE"
    echo "Run: bash saml2/generate-sp-credentials.sh"
    exit 1
fi
SP_CERT_PEM=$(grep -v '^\-\-\-\-\-' "$SP_CERT_FILE" | tr -d '\n')

# 3. Create SAML2 client
echo "Creating SAML2 client..."
CLIENT_PAYLOAD=$(cat <<ENDJSON
{
    "clientId": "${SP_ENTITY_ID}",
    "name": "MPR Demo SP",
    "description": "Vaadin MPR deadlock reproducer",
    "enabled": true,
    "protocol": "saml",
    "rootUrl": "${APP_BASE_URL}",
    "baseUrl": "/",
    "redirectUris": ["${APP_BASE_URL}/*"],
    "adminUrl": "${SP_ACS_URL}",
    "attributes": {
        "saml_assertion_consumer_url_post": "${SP_ACS_URL}",
        "saml_single_logout_service_url_post": "${SP_SLO_URL}",
        "saml_single_logout_service_url_redirect": "${SP_SLO_URL}",
        "saml.authnstatement": "true",
        "saml.server.signature": "true",
        "saml.client.signature": "true",
        "saml.signature.algorithm": "RSA_SHA256",
        "saml.force.post.binding": "true",
        "saml_force_name_id_format": "true",
        "saml_name_id_format": "username",
        "saml.signing.certificate": "${SP_CERT_PEM}"
    },
    "frontchannelLogout": true,
    "fullScopeAllowed": true
}
ENDJSON
)

kc_api POST "/${REALM}/clients" -d "$CLIENT_PAYLOAD" 2>/dev/null \
    || echo "  (client may already exist, continuing)"

# 4. Create test user
echo "Creating test user (testuser / testpass)..."
kc_api POST "/${REALM}/users" -d '{
    "username": "testuser",
    "enabled": true,
    "firstName": "Test",
    "lastName": "User",
    "email": "testuser@example.com",
    "emailVerified": true,
    "credentials": [{
        "type": "password",
        "value": "testpass",
        "temporary": false
    }]
}' 2>/dev/null || echo "  (user may already exist, continuing)"

echo ""
echo "=== Keycloak setup complete ==="
echo ""
echo "IdP metadata:  ${KC_URL}/realms/${REALM}/protocol/saml/descriptor"
echo "Admin console: ${KC_URL}/admin/master/console/#/${REALM}"
echo "Test user:     testuser / testpass"
echo ""
echo "Run the app with SAML2:"
echo "  mvn spring-boot:run -Dspring-boot.run.profiles=saml2"
echo ""
echo "Then open: ${APP_BASE_URL}/"
