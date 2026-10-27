#!/bin/sh
# =================================================================
# SERVICE MANAGEMENT SCRIPT - tr-budget-service
# Usage: ./tr-budget-service.sh {start|stop|restart|deploy|status|log [console]}
# =================================================================

# =================================================================
# 1. CONFIGURATION (Override bằng ENV vars từ Jenkins/Docker)
# =================================================================
APP_NAME="${APP_NAME_ENV:-tr-budget-service}"
CLI_SPRING_PROFILE="$SPRING_PROFILES_ACTIVE"
SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-dev}"
SERVER_PORT="${SERVER_PORT_ENV:-8503}"
JAVA_OPTS="${JAVA_OPTS_ENV:--Xms256m -Xmx512m -XX:+UseSerialGC}"

LOG_BASE_PATH="/opt/logs"
HEALTH_TIMEOUT=150
STOP_TIMEOUT=30

# =================================================================
# 2. SYSTEM PATHS
# =================================================================
APP_HOME=$(cd "$(dirname "$0")" || exit 1; pwd)
if [ -f "/opt/spring-app/.env" ]; then
    set -a
    . "/opt/spring-app/.env"
    set +a
fi
if [ -f "$APP_HOME/.env" ]; then
    set -a
    . "$APP_HOME/.env"
    set +a
fi

SPRING_PROFILE="${CLI_SPRING_PROFILE:-${SPRING_PROFILES_ACTIVE:-dev}}"
export SPRING_PROFILES_ACTIVE="$SPRING_PROFILE"
unset SPRING_CONFIG_IMPORT

JAR_FILE="$APP_HOME/$APP_NAME.jar"
PID_FILE="$APP_HOME/$APP_NAME.pid"
LOG_DIR="$LOG_BASE_PATH/$APP_NAME"
CONSOLE_LOG="$LOG_DIR/console.log"
MAIN_LOG="$LOG_DIR/sys-info.log"
HEALTH_URL="http://localhost:$SERVER_PORT/actuator/health"

# =================================================================
# 3. HELPERS
# =================================================================
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log_info()  { printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
log_warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
log_error() { printf "${RED}[ERROR]${NC} %s\n" "$*"; }
log_step()  { printf "${CYAN}[....] %s${NC}\n" "$*"; }

check_java() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        JAVACMD=$(command -v java 2>/dev/null)
    fi
    if [ -z "$JAVACMD" ]; then
        log_error "Java not found. Set JAVA_HOME or add java to PATH."
        exit 1
    fi
    java_ver=$("$JAVACMD" -version 2>&1 | head -1)
    log_info "Using Java: $java_ver ($JAVACMD)"
}

is_running() {
    if [ -f "$PID_FILE" ]; then
        _pid=$(cat "$PID_FILE")
        if kill -0 "$_pid" 2>/dev/null; then
            return 0
        fi
        rm -f "$PID_FILE"
    fi
    return 1
}

get_pid() {
    [ -f "$PID_FILE" ] && cat "$PID_FILE" || echo "N/A"
}

init_dirs() {
    mkdir -p "$LOG_DIR" || { log_error "Cannot create log dir: $LOG_DIR"; exit 1; }
}

dump_startup_diagnostics() {
    local REASON="$1"
    local ERROR_SUMMARY_FILE="$LOG_DIR/last-startup-error.txt"
    local ERROR_LOG="$LOG_DIR/sys-error.log"

    echo ""
    log_error "=============================================================="
    log_error " ❌ STARTUP FAILED: $APP_NAME ($REASON)"
    log_error "=============================================================="

    : > "$ERROR_SUMMARY_FILE" 2>/dev/null || true
    if [ -f "$CONSOLE_LOG" ]; then
        grep -E "ERROR|FATAL|Exception|Caused by:|WebServerException|Port .* was already in use|password authentication failed|database .* does not exist" "$CONSOLE_LOG" 2>/dev/null | awk '!seen[$0]++' | tail -n 20 > "$ERROR_SUMMARY_FILE" 2>/dev/null || true
    fi
    if [ ! -s "$ERROR_SUMMARY_FILE" ] && [ -f "$ERROR_LOG" ]; then
        tail -n 200 "$ERROR_LOG" 2>/dev/null | grep -E "ERROR|FATAL|Exception|Caused by:|WebServerException|Port .* was already in use|password authentication failed|database .* does not exist" 2>/dev/null | awk '!seen[$0]++' | tail -n 20 > "$ERROR_SUMMARY_FILE" 2>/dev/null || true
    fi
    if [ ! -s "$ERROR_SUMMARY_FILE" ] && [ -f "$MAIN_LOG" ]; then
        tail -n 200 "$MAIN_LOG" 2>/dev/null | grep -E "ERROR|FATAL|Exception|Caused by:|WebServerException|Port .* was already in use|password authentication failed|database .* does not exist" 2>/dev/null | awk '!seen[$0]++' | tail -n 20 > "$ERROR_SUMMARY_FILE" 2>/dev/null || true
    fi
    if [ ! -s "$ERROR_SUMMARY_FILE" ] && [ -f "$CONSOLE_LOG" ]; then
        tail -n 20 "$CONSOLE_LOG" > "$ERROR_SUMMARY_FILE" 2>/dev/null || true
    fi

    echo ""
    echo "=== 🔍 ROOT CAUSE SUMMARY (last 20 error lines) ==="
    if [ -s "$ERROR_SUMMARY_FILE" ]; then
        cat "$ERROR_SUMMARY_FILE"
    else
        echo "(No specific ERROR/Exception lines matched)"
    fi

    echo ""
    echo "=== 📄 LAST 60 LINES OF CONSOLE LOG ($CONSOLE_LOG) ==="
    if [ -f "$CONSOLE_LOG" ]; then
        tail -n 60 "$CONSOLE_LOG"
    else
        echo "(Console log file not found)"
    fi

    if [ -s "$ERROR_LOG" ]; then
        echo ""
        echo "=== 📄 LAST 30 LINES OF SYS-ERROR LOG ($ERROR_LOG) ==="
        tail -n 30 "$ERROR_LOG"
    fi
    echo "=============================================================="
}

# =================================================================
# 4. CORE FUNCTIONS
# =================================================================

start_app() {
    if is_running; then
        log_warn "$APP_NAME is already running (PID: $(get_pid)). Skipping start."
        return 0
    fi

    if [ -f "/opt/spring-app/.env" ]; then
        log_info "Environment loaded from: /opt/spring-app/.env"
        set -a
        . "/opt/spring-app/.env"
        set +a
    fi
    if [ -f "$APP_HOME/.env" ]; then
        log_info "Environment loaded from: $APP_HOME/.env"
        set -a
        . "$APP_HOME/.env"
        set +a
    fi

    SPRING_PROFILE="${CLI_SPRING_PROFILE:-${SPRING_PROFILES_ACTIVE:-dev}}"
    export SPRING_PROFILES_ACTIVE="$SPRING_PROFILE"
    export JPA_DDL_AUTO="${JPA_DDL_AUTO:-update}"
    unset SPRING_CONFIG_IMPORT

    check_java
    init_dirs

    log_info "=============================================="
    log_info " Starting: $APP_NAME"
    log_info " Profile : $SPRING_PROFILE"
    log_info " Port    : $SERVER_PORT"
    log_info " JAR     : $JAR_FILE"
    log_info "=============================================="

    if [ ! -f "$JAR_FILE" ]; then
        log_error "JAR file not found: $JAR_FILE"
        exit 1
    fi

    export LOG_PATH="$LOG_BASE_PATH"

    local JVM_ARGS="$JAVA_OPTS \
        -XX:+UseContainerSupport \
        -DLOG_PATH=$LOG_BASE_PATH \
        -Dspring.application.name=$APP_NAME \
        -Dspring.profiles.active=$SPRING_PROFILE \
        -DJPA_DDL_AUTO=$JPA_DDL_AUTO \
        -Dspring.jpa.hibernate.ddl-auto=$JPA_DDL_AUTO \
        -Dserver.port=$SERVER_PORT"

    cd "$APP_HOME" || exit 1
    nohup "$JAVACMD" $JVM_ARGS -jar "$JAR_FILE" > "$CONSOLE_LOG" 2>&1 < /dev/null &

    local PID=$!
    echo "$PID" > "$PID_FILE"
    log_info "Process started with PID: $PID"
    log_info "Console log: $CONSOLE_LOG"

    log_step "Waiting for service to be healthy (timeout: ${HEALTH_TIMEOUT}s)..."
    local elapsed=0
    while [ "$elapsed" -lt "$HEALTH_TIMEOUT" ]; do
        if ! kill -0 "$PID" 2>/dev/null; then
            echo ""
            log_error "Process $PID died unexpectedly! Check: $CONSOLE_LOG"
            rm -f "$PID_FILE"
            dump_startup_diagnostics "Process $PID died unexpectedly"
            exit 1
        fi

        if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
            echo ""
            log_info "✅ $APP_NAME is UP and HEALTHY! (PID: $PID, ${elapsed}s)"
            log_info "   Health  : $HEALTH_URL"
            log_info "   Logs    : $LOG_DIR"
            return 0
        fi

        printf "."
        sleep 3
        elapsed=$((elapsed + 3))
    done

    echo ""
    log_error "⏱️  Health check timed out after ${HEALTH_TIMEOUT}s."
    log_error "   Service failed to become UP in time. Check: $CONSOLE_LOG"
    dump_startup_diagnostics "Health check timed out after ${HEALTH_TIMEOUT}s"
    exit 1
}

stop_app() {
    if is_running; then
        local PID
        PID=$(get_pid)
        log_info "Stopping $APP_NAME (PID: $PID)..."
        kill -15 "$PID" 2>/dev/null

        local count=0
        printf "Waiting"
        while kill -0 "$PID" 2>/dev/null && [ "$count" -lt "$STOP_TIMEOUT" ]; do
            printf "."
            sleep 1
            count=$((count + 1))
        done
        echo ""

        if kill -0 "$PID" 2>/dev/null; then
            log_warn "SIGTERM failed. Forcing SIGKILL on PID $PID..."
            kill -9 "$PID" 2>/dev/null
            sleep 1
        fi

        rm -f "$PID_FILE"
        log_info "✅ $APP_NAME stopped."
    else
        log_warn "$APP_NAME is not running (no active PID file)."
    fi

    local ORPHAN_PIDS
    ORPHAN_PIDS=$(pgrep -f "$APP_NAME.jar" 2>/dev/null || true)
    if [ -n "$ORPHAN_PIDS" ]; then
        log_warn "Found orphan $APP_NAME process(es): $ORPHAN_PIDS. Stopping..."
        kill -15 $ORPHAN_PIDS 2>/dev/null || true
        sleep 2
        kill -9 $ORPHAN_PIDS 2>/dev/null || true
    fi
}

deploy_app() {
    log_info "🚚 Deploy triggered for $APP_NAME..."
    stop_app
    sleep 2
    start_app
}

view_logs() {
    local target="$MAIN_LOG"
    [ "$1" = "console" ] && target="$CONSOLE_LOG"

    if [ ! -f "$target" ]; then
        log_error "Log file not found: $target"
        exit 1
    fi
    log_info "Tailing: $target  (Ctrl+C to stop)"
    tail -f "$target"
}

show_status() {
    echo ""
    echo "  Service : $APP_NAME"
    echo "  Profile : $SPRING_PROFILE"
    echo "  Port    : $SERVER_PORT"
    if is_running; then
        local pid
        pid=$(get_pid)
        local uptime
        uptime=$(ps -p "$pid" -o etime= 2>/dev/null | tr -d ' ')
        log_info "🟢 RUNNING  (PID: $pid | Uptime: $uptime)"
        if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
            log_info "   Health : UP ✅"
        else
            log_warn "   Health : NOT RESPONDING ⚠️"
        fi
    else
        log_error "🔴 STOPPED"
    fi
    echo ""
}

# =================================================================
# 5. ENTRY POINT
# =================================================================
case "$1" in
    start)            start_app ;;
    stop)             stop_app ;;
    restart)          stop_app; sleep 2; start_app ;;
    deploy)           deploy_app ;;
    status)           show_status ;;
    log|logs)         view_logs "$2" ;;
    *)
        echo ""
        echo "  Usage: $0 {start|stop|restart|deploy|status|log [console]}"
        echo ""
        echo "  Commands:"
        echo "    start    – Start the service"
        echo "    stop     – Gracefully stop the service"
        echo "    restart  – Stop then start"
        echo "    deploy   – CI/CD deploy (stop → wait → start)"
        echo "    status   – Show running status and health"
        echo "    log      – Tail system log (add 'console' for startup log)"
        echo ""
        exit 1
        ;;
esac
