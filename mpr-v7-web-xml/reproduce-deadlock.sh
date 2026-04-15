#!/usr/bin/env bash
# ===========================================================================
#  reproduce-deadlock.sh — Automated deadlock reproduction driver
#
#  This script coordinates the deadlock reproduction test for the
#  MPR + WebSphere push-disconnect deadlock.
#
#  It can operate in two ways:
#
#  1. TRIGGER mode (default):
#     Logs in via curl, then hits the /deadlock-test endpoint.
#     NOTE: The push connection must already be active — you must have
#     the app open in a browser tab on the SAME session.  The script
#     re-uses the browser's session cookie.
#
#  2. MONITOR mode (--monitor):
#     Takes periodic thread dumps from the WebSphere Docker container
#     and checks for deadlocked threads.  Use this while manually
#     clicking Logout in the browser.
#
#  Usage:
#     ./reproduce-deadlock.sh                 # trigger mode
#     ./reproduce-deadlock.sh --monitor       # monitor mode
#     ./reproduce-deadlock.sh --status        # check for existing deadlocks
#     ./reproduce-deadlock.sh --thread-dump   # single thread dump
#
#  Environment variables:
#     WAS_HOST      WebSphere hostname       (default: localhost)
#     WAS_PORT      HTTP port                (default: 9080)
#     CONTEXT       Context root             (default: /mprdemo)
#     USERNAME      Login username           (default: user)
#     PASSWORD      Login password           (default: user)
#     CONTAINER     Docker container name    (default: auto-detect)
#     ATTEMPTS      Number of trigger loops  (default: 20)
#     DELAY         Thread-A delay in ms     (default: 5)
#     TIMEOUT       Deadlock timeout in ms   (default: 15000)
# ===========================================================================

set -euo pipefail

# --- Configuration ----------------------------------------------------------
WAS_HOST="${WAS_HOST:-localhost}"
WAS_PORT="${WAS_PORT:-9080}"
CONTEXT="${CONTEXT:-/mprdemo}"
USERNAME="${USERNAME:-user}"
PASSWORD="${PASSWORD:-user}"
CONTAINER="${CONTAINER:-}"
ATTEMPTS="${ATTEMPTS:-20}"
DELAY="${DELAY:-5}"
TIMEOUT="${TIMEOUT:-15000}"

BASE_URL="http://${WAS_HOST}:${WAS_PORT}${CONTEXT}"
COOKIE_JAR=$(mktemp /tmp/deadlock-cookies.XXXXXX)
DUMP_DIR="thread-dumps"

# --- Colors -----------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

# --- Find Docker container --------------------------------------------------
find_container() {
    if [ -n "$CONTAINER" ]; then
        echo "$CONTAINER"
        return
    fi
    # Auto-detect WebSphere container
    local c
    c=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -i -E 'websphere|was' | head -1 || true)
    if [ -z "$c" ]; then
        warn "No WebSphere Docker container found."
        warn "Set CONTAINER=<name> or start WebSphere."
        return 1
    fi
    echo "$c"
}

# --- Login via curl ---------------------------------------------------------
do_login() {
    info "Logging in as '${USERNAME}' at ${BASE_URL}/login ..."

    # GET the login page first (to get a session cookie)
    curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        -o /dev/null \
        "${BASE_URL}/login"

    # POST credentials
    local http_code
    http_code=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        -o /dev/null -w "%{http_code}" \
        -d "username=${USERNAME}&password=${PASSWORD}" \
        -L "${BASE_URL}/login")

    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 400 ]; then
        ok "Login successful (HTTP $http_code)"
        return 0
    else
        fail "Login failed (HTTP $http_code)"
        return 1
    fi
}

# --- Hit the deadlock-test endpoint -----------------------------------------
do_trigger() {
    local mode="${1:-forced}"
    local attempt="${2:-1}"

    info "Attempt $attempt: hitting /deadlock-test?mode=${mode}&delay=${DELAY}&timeout=${TIMEOUT} ..."

    local response http_code
    response=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        -w "\n%{http_code}" \
        "${BASE_URL}/deadlock-test?mode=${mode}&delay=${DELAY}&timeout=${TIMEOUT}" \
        --max-time $(( TIMEOUT / 1000 + 10 )) )

    http_code=$(echo "$response" | tail -1)
    local body
    body=$(echo "$response" | sed '$d')

    echo ""
    echo "$body"
    echo ""

    if echo "$body" | grep -q "DEADLOCK DETECTED"; then
        fail "DEADLOCK DETECTED on attempt $attempt!"
        echo ""
        take_thread_dump "deadlock-attempt-${attempt}"
        return 1  # deadlock found
    elif [ "$http_code" = "400" ]; then
        warn "Precondition not met (HTTP 400) — see output above."
        warn "Make sure the app is open in a browser tab with push active."
        return 2  # precondition error
    else
        ok "No deadlock on attempt $attempt."
        return 0  # no deadlock
    fi
}

# --- Thread dump ------------------------------------------------------------
take_thread_dump() {
    local label="${1:-manual}"
    local container

    container=$(find_container) || {
        warn "Cannot take thread dump — no Docker container found."
        return 0
    }

    mkdir -p "$DUMP_DIR"
    local timestamp
    timestamp=$(date +%Y%m%d-%H%M%S)
    local dump_file="${DUMP_DIR}/threaddump-${label}-${timestamp}.txt"

    info "Taking thread dump from container '$container' ..."

    # Try jcmd first (IBM JDK), then jstack, then kill -3
    if docker exec "$container" \
        /opt/IBM/WebSphere/AppServer/java/8.0/bin/jcmd 1 Thread.print -l \
        > "$dump_file" 2>/dev/null; then
        ok "Thread dump saved to $dump_file (jcmd)"
    elif docker exec "$container" \
        bash -c 'jstack $(pgrep -f java | head -1)' \
        > "$dump_file" 2>/dev/null; then
        ok "Thread dump saved to $dump_file (jstack)"
    else
        # Fall back to kill -3 (dumps to stderr of the JVM)
        docker exec "$container" \
            bash -c 'kill -3 $(pgrep -f java | head -1)' 2>/dev/null || true
        sleep 2
        docker logs "$container" 2>&1 | tail -500 > "$dump_file"
        warn "Thread dump saved to $dump_file (kill -3, from container logs)"
    fi

    # Check for deadlock indicators in the dump
    if grep -q -i "deadlock" "$dump_file" 2>/dev/null; then
        fail "DEADLOCK found in thread dump!"
        echo ""
        grep -A 30 -i "deadlock" "$dump_file" | head -80
    fi

    if grep -q "DEADLOCK-TEST" "$dump_file" 2>/dev/null; then
        info "Test threads found in dump:"
        grep -A 15 "DEADLOCK-TEST" "$dump_file"
    fi
}

# --- Status check -----------------------------------------------------------
do_status() {
    info "Checking for existing deadlocks via /deadlock-test?mode=status ..."

    # Try with cookie jar first, fall back to no auth
    local response
    response=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        "${BASE_URL}/deadlock-test?mode=status" \
        --max-time 10 2>/dev/null || \
        curl -s "${BASE_URL}/deadlock-test?mode=status" \
        --max-time 10 2>/dev/null || echo "ERROR: Could not reach endpoint")

    echo ""
    echo "$response"
    echo ""
}

# --- Monitor mode -----------------------------------------------------------
do_monitor() {
    info "Monitor mode — taking thread dumps every 10 seconds."
    info "Trigger the deadlock from the browser (click Logout or Deadlock Test)."
    info "Press Ctrl+C to stop."
    echo ""

    local count=0
    while true; do
        count=$((count + 1))
        take_thread_dump "monitor-${count}"
        echo ""
        sleep 10
    done
}

# --- Trigger loop -----------------------------------------------------------
do_trigger_loop() {
    info "Trigger mode — will attempt ${ATTEMPTS} times."
    info ""
    info "IMPORTANT: Before running this script, make sure you have the"
    info "application open in a browser tab on the SAME WebSphere server."
    info "The browser establishes the push (WebSocket) connection that"
    info "the deadlock test needs."
    info ""
    info "If the script reports 'No active PushConnection', open the app"
    info "in a browser first, then try again."
    echo ""

    do_login || exit 1
    echo ""

    for i in $(seq 1 "$ATTEMPTS"); do
        do_trigger "forced" "$i"
        local rc=$?

        if [ $rc -eq 1 ]; then
            # Deadlock found
            echo ""
            fail "========================================="
            fail "  DEADLOCK REPRODUCED on attempt $i"
            fail "========================================="
            echo ""
            info "Thread dump saved in ${DUMP_DIR}/"
            info "Use './reproduce-deadlock.sh --status' to inspect."
            cleanup
            exit 1
        elif [ $rc -eq 2 ]; then
            # Precondition failed — re-login and retry
            warn "Re-logging in ..."
            do_login || exit 1
            # Wait for push to re-establish
            sleep 3
        fi

        # Brief pause between attempts
        sleep 1

        # Re-login for next attempt (session was invalidated)
        if [ $i -lt "$ATTEMPTS" ]; then
            do_login 2>/dev/null || true
            # Wait a moment for the browser to potentially re-establish push
            sleep 2
        fi
    done

    echo ""
    ok "No deadlock detected after $ATTEMPTS attempts."
    info ""
    info "The race window may be too narrow for the current timing."
    info "Try:"
    info "  - Different delay values:  DELAY=1 ./reproduce-deadlock.sh"
    info "  - Different delay values:  DELAY=10 ./reproduce-deadlock.sh"
    info "  - More attempts:           ATTEMPTS=100 ./reproduce-deadlock.sh"
    info "  - Manual browser trigger:  click 'Deadlock Test' in the UI"
    info "  - Monitor mode:            ./reproduce-deadlock.sh --monitor"
    cleanup
}

# --- Cleanup ----------------------------------------------------------------
cleanup() {
    rm -f "$COOKIE_JAR" 2>/dev/null || true
}
trap cleanup EXIT

# --- Main -------------------------------------------------------------------
case "${1:-trigger}" in
    --trigger|trigger)
        do_trigger_loop
        ;;
    --monitor|monitor)
        do_monitor
        ;;
    --status|status)
        do_status
        ;;
    --thread-dump|thread-dump|dump)
        take_thread_dump "manual"
        ;;
    --help|-h|help)
        echo "Usage: $0 [--trigger|--monitor|--status|--thread-dump|--help]"
        echo ""
        echo "Modes:"
        echo "  --trigger      (default) Log in and hit /deadlock-test in a loop"
        echo "  --monitor      Take periodic thread dumps while you test manually"
        echo "  --status       Check for existing deadlocks via the test endpoint"
        echo "  --thread-dump  Take a single thread dump from the WebSphere container"
        echo ""
        echo "Environment variables:"
        echo "  WAS_HOST=$WAS_HOST  WAS_PORT=$WAS_PORT  CONTEXT=$CONTEXT"
        echo "  USERNAME=$USERNAME  PASSWORD=$PASSWORD"
        echo "  CONTAINER=$CONTAINER  ATTEMPTS=$ATTEMPTS"
        echo "  DELAY=$DELAY  TIMEOUT=$TIMEOUT"
        ;;
    *)
        fail "Unknown option: $1"
        echo "Run '$0 --help' for usage."
        exit 1
        ;;
esac
