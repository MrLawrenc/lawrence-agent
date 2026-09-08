#!/usr/bin/env bash
set -euo pipefail
# Give the Gradle launcher and the JavaFX process their own process group.  A terminal Ctrl-C
# then only reaches this script; the handler below can reliably terminate the entire app group.
set -m

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/agent-monitor.pid"

cd "$SCRIPT_DIR"
if [[ -f "$PID_FILE" ]]; then
    existing_pid="$(tr -d '[:space:]' < "$PID_FILE")"
    if [[ "$existing_pid" =~ ^[0-9]+$ ]] && kill -0 -- "-$existing_pid" 2>/dev/null; then
        echo "Agent Monitor is already running (process group: $existing_pid). Use ./restart.sh to restart it."
        exit 1
    fi
    rm -f "$PID_FILE"
fi

./gradlew :monitor-app:run &
app_pid=$!
printf '%s\n' "$app_pid" > "$PID_FILE"

cleanup() {
    current_pid="$(tr -d '[:space:]' < "$PID_FILE" 2>/dev/null || true)"
    if [[ "$current_pid" == "$app_pid" ]]; then
        rm -f "$PID_FILE"
    fi
}

stop_managed_process() {
    local signal="$1"
    kill "-$signal" -- "-$app_pid" 2>/dev/null || true
}

handle_interrupt() {
    local signal="$1"
    echo "Stopping Agent Monitor (PID: $app_pid)..."
    trap - INT TERM
    stop_managed_process "$signal"
    exit 130
}

trap cleanup EXIT
trap 'handle_interrupt TERM' TERM
trap 'handle_interrupt INT' INT

wait "$app_pid"
