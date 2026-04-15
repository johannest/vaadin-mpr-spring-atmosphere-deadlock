#!/bin/bash
# =============================================================================
# Generates a self-signed RSA key pair for the SAML2 Service Provider (SP).
#
# The generated files are placed into src/main/resources/credentials/ so they
# are included on the classpath when the application starts.
#
# Usage:
#   cd mpr-v7-web-xml
#   bash saml2/generate-sp-credentials.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$PROJECT_DIR/src/main/resources/credentials"

mkdir -p "$OUT_DIR"

echo "Generating SP signing key pair..."
openssl req -newkey rsa:2048 \
  -nodes \
  -keyout "$OUT_DIR/sp-key.pem" \
  -x509 \
  -days 3650 \
  -out "$OUT_DIR/sp-cert.pem" \
  -subj "/CN=mprdemo-sp/O=Vaadin/C=FI"

echo ""
echo "Generated:"
echo "  Private key : $OUT_DIR/sp-key.pem"
echo "  Certificate : $OUT_DIR/sp-cert.pem"
echo ""
echo "Certificate details:"
openssl x509 -in "$OUT_DIR/sp-cert.pem" -noout -subject -dates
echo ""
echo "Next steps:"
echo "  1. Start Keycloak:  cd saml2 && docker compose up -d"
echo "  2. Configure Keycloak: bash saml2/keycloak-setup.sh"
echo "  3. Run the app:  mvn spring-boot:run -Dspring-boot.run.profiles=saml2"
