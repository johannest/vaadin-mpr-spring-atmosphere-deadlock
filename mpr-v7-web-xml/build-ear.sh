#!/bin/bash
#
# Builds the WAR and wraps it into an EAR for WebSphere deployment.
#
# Usage:
#   ./build-ear.sh              # dev mode
#   ./build-ear.sh -Pproduction # production mode (bundled frontend)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

WAR_NAME="mprdemo-1.0-SNAPSHOT.war"
EAR_NAME="mprdemo.ear"
CONTEXT_ROOT="/mprdemo"

# Pass any extra Maven arguments (e.g., -Pproduction)
echo "=== Building WAR ==="
mvn clean package -DskipTests -Pproduction "$@"

WAR_PATH="target/${WAR_NAME}"
if [ ! -f "$WAR_PATH" ]; then
    echo "ERROR: WAR not found at $WAR_PATH"
    exit 1
fi

echo "=== Building EAR ==="
EAR_DIR="target/ear-staging"
rm -rf "$EAR_DIR"
mkdir -p "$EAR_DIR/META-INF"

# Copy WAR
cp "$WAR_PATH" "$EAR_DIR/"

# Generate application.xml
cat > "$EAR_DIR/META-INF/application.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<application xmlns="http://xmlns.jcp.org/xml/ns/javaee"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
             http://xmlns.jcp.org/xml/ns/javaee/application_7.xsd"
             version="7">
    <display-name>MPR Deadlock Repro</display-name>
    <module>
        <web>
            <web-uri>${WAR_NAME}</web-uri>
            <context-root>${CONTEXT_ROOT}</context-root>
        </web>
    </module>
</application>
EOF

# Build the EAR
cd "$EAR_DIR"
jar cf "../${EAR_NAME}" META-INF/application.xml "$WAR_NAME"
cd "$SCRIPT_DIR"

EAR_PATH="target/${EAR_NAME}"
EAR_SIZE=$(du -h "$EAR_PATH" | cut -f1)
echo ""
echo "=== Done ==="
echo "WAR: target/${WAR_NAME}"
echo "EAR: ${EAR_PATH} (${EAR_SIZE})"
echo ""
echo "Deploy with:"
echo "  docker cp ${EAR_PATH} <container>:/path/to/dropins/"
