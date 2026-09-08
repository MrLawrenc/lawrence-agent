#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/agent-monitor.pid"
STOP_TIMEOUT_SECONDS=10

cd "$SCRIPT_DIR"

read_pid() {
    [[ -f "$PID_FILE" ]] || return 1
    local pid
    pid="$(tr -d '[:space:]' < "$PID_FILE")"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$pid"
}

process_group_exists() {
    kill -0 -- "-$1" 2>/dev/null
}

stop_process_group() {
    local group_leader="$1" signal="$2"
    kill "-$signal" -- "-$group_leader" 2>/dev/null || true
}

if app_pid="$(read_pid 2>/dev/null)"; then
    if process_group_exists "$app_pid"; then
        echo "Stopping Agent Monitor process group (leader PID: $app_pid)..."
        stop_process_group "$app_pid" TERM
        for ((second = 0; second < STOP_TIMEOUT_SECONDS; second++)); do
            sleep 1
            process_group_exists "$app_pid" || break
        done
        if process_group_exists "$app_pid"; then
            echo "Process did not exit within ${STOP_TIMEOUT_SECONDS}s; forcing shutdown."
            stop_process_group "$app_pid" KILL
            for ((second = 0; second < 3; second++)); do
                sleep 1
                process_group_exists "$app_pid" || break
            done
        fi
        if process_group_exists "$app_pid"; then
            echo "Unable to stop process group $app_pid; restart cancelled to avoid a duplicate instance."
            exit 1
        fi
    else
        echo "Removing stale PID file (process group $app_pid is no longer running)."
    fi
    rm -f "$PID_FILE"
else
    echo "No Agent Monitor PID file found; starting a new instance."
fi

exec "$SCRIPT_DIR/start.sh"
