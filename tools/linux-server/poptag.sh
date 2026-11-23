#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$ROOT_DIR/server" ]]; then
  SERVER_DIR="$ROOT_DIR/server"
elif [[ -d "$ROOT_DIR/../../server" ]]; then
  SERVER_DIR="$(cd "$ROOT_DIR/../../server" && pwd)"
else
  echo "[错误] 未找到 server 目录" >&2
  exit 1
fi
resolve_classpath() {
  if [[ -d "$SERVER_DIR/classes" && -d "$SERVER_DIR/lib" ]]; then
    echo "$SERVER_DIR/classes:$SERVER_DIR/lib/*"
  elif [[ -d "$SERVER_DIR/target/classes" && -d "$SERVER_DIR/target/lib" ]]; then
    echo "$SERVER_DIR/target/classes:$SERVER_DIR/target/lib/*"
  else
    echo "[错误] 未找到已编译的 server 类文件，请先在 server/ 目录执行: mvn -DskipTests compile dependency:copy-dependencies -DoutputDirectory=target/lib" >&2
    exit 1
  fi
}
LOG_DIR="$ROOT_DIR/logs"
PID_FILE="$LOG_DIR/server.pid"
OUT_LOG="$LOG_DIR/server.out.log"
ERR_LOG="$LOG_DIR/server.err.log"
SERVICE_NAME="poptag-server"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx512m -Dfile.encoding=UTF-8}"

mkdir -p "$LOG_DIR" "$SERVER_DIR/data/local-accounts"

has_systemd_service() {
  command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files 2>/dev/null | grep -q "^${SERVICE_NAME}\.service"
}

check_java() {
  if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "[错误] 未找到 Java 运行时，请先安装 OpenJDK 21（例如运行: sudo bash install.sh）" >&2
    exit 1
  fi
  local ver
  ver="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F'.' '{print $1}')"
  if [[ -z "$ver" || "$ver" -lt 21 ]]; then
    echo "[错误] 需要 Java 21 或更高版本，当前检测到: $("$JAVA_BIN" -version 2>&1 | head -n 1)" >&2
    exit 1
  fi
}

read_server_ip() {
  if [[ -n "${POPTAG_SERVER_IP:-}" ]]; then
    echo "$POPTAG_SERVER_IP"
    return
  fi
  awk -F':' '/^[[:space:]]*ServerIP[[:space:]]*:/ {
    sub(/#.*/, "", $2);
    gsub(/^[[:space:]"'"'"']+|[[:space:]"'"'"']+$/, "", $2);
    print $2;
    exit
  }' "$SERVER_DIR/Config.yaml" 2>/dev/null || echo "auto"
}

is_running_standalone() {
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "bnbemu.MainKt"; then
        return 0
      fi
    fi
  fi
  return 1
}

check_ports() {
  if command -v ss >/dev/null 2>&1; then
    ss -tulnp 2>/dev/null | grep -E ':(3838|3839|4848|4849|47611|415[1-9]|4160)\b' || true
  elif command -v netstat >/dev/null 2>&1; then
    netstat -tulnp 2>/dev/null | grep -E ':(3838|3839|4848|4849|47611|415[1-9]|4160)\b' || true
  fi
}

cmd_fg() {
  check_java
  local cp
  cp="$(resolve_classpath)"
  cd "$SERVER_DIR"
  # shellcheck disable=SC2086
  exec "$JAVA_BIN" $JAVA_OPTS -cp "$cp" bnbemu.MainKt
}

require_root_for_systemd() {
  if [[ "$EUID" -ne 0 ]]; then
    echo "[提示] 已安装 systemd 服务 ($SERVICE_NAME)，请使用 root 权限执行: sudo $0 $1" >&2
    exit 1
  fi
}

cmd_start() {
  if has_systemd_service && [[ "${POPTAG_STANDALONE:-0}" != "1" ]]; then
    require_root_for_systemd "start"
    systemctl start "$SERVICE_NAME"
    sleep 1
    cmd_status
    return
  fi
  check_java
  if is_running_standalone; then
    echo "[提示] 服务端已在运行中 (PID $(cat "$PID_FILE"))"
    return 0
  fi
  local cp
  cp="$(resolve_classpath)"
  echo "[启动] 正在启动 PopTag Linux 服务端 (ServerIP=$(read_server_ip))..."
  (
    cd "$SERVER_DIR"
    # shellcheck disable=SC2086
    nohup "$JAVA_BIN" $JAVA_OPTS -cp "$cp" bnbemu.MainKt \
      >>"$OUT_LOG" 2>>"$ERR_LOG" </dev/null &
    echo $! >"$PID_FILE"
  )
  local pid
  pid="$(cat "$PID_FILE")"
  for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[错误] 服务端启动失败，最近错误日志:" >&2
      tail -n 30 "$ERR_LOG" "$OUT_LOG" 2>/dev/null || true
      rm -f "$PID_FILE"
      exit 1
    fi
    if command -v ss >/dev/null 2>&1; then
      if ss -tln 2>/dev/null | grep -q ':3838\b' && ss -tln 2>/dev/null | grep -q ':47611\b'; then
        echo "[成功] 服务端已启动 (PID $pid, ServerIP=$(read_server_ip))"
        check_ports
        return 0
      fi
    fi
    sleep 0.5
  done
  echo "[提示] 进程已启动 (PID $pid)，请执行 ./poptag.sh status 或 ./poptag.sh logs 确认端口。"
}

cmd_stop() {
  if has_systemd_service && [[ "${POPTAG_STANDALONE:-0}" != "1" ]]; then
    require_root_for_systemd "stop"
    systemctl stop "$SERVICE_NAME"
    echo "[已停止] systemd 服务 $SERVICE_NAME 已停止"
    return 0
  fi
  if ! is_running_standalone; then
    echo "[提示] 服务端未运行"
    rm -f "$PID_FILE"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  echo "[停止] 正在停止服务端 (PID $pid)..."
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "[已停止] 服务端已退出"
      return 0
    fi
    sleep 0.25
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "[已强制停止] PID $pid"
}

cmd_status() {
  echo "=== PopTag Linux 服务端状态 ==="
  echo "配置目录: $SERVER_DIR"
  echo "当前 ServerIP: $(read_server_ip)"
  if has_systemd_service && [[ "${POPTAG_STANDALONE:-0}" != "1" ]]; then
    systemctl --no-pager -l status "$SERVICE_NAME" || true
  elif is_running_standalone; then
    echo "运行状态: 运行中 (PID $(cat "$PID_FILE"))"
  else
    echo "运行状态: 未运行"
  fi
  echo "--- 监听端口 ---"
  check_ports
}

cmd_logs() {
  if has_systemd_service && [[ "${POPTAG_STANDALONE:-0}" != "1" ]]; then
    journalctl -u "$SERVICE_NAME" -n 100 -f
  else
    touch "$OUT_LOG" "$ERR_LOG"
    tail -n 100 -f "$OUT_LOG" "$ERR_LOG"
  fi
}

cmd_set_ip() {
  local new_ip="${1:-}"
  if [[ -z "$new_ip" ]]; then
    echo "用法: $0 set-ip <公网IP或域名>" >&2
    exit 1
  fi
  if grep -qE '^[[:space:]]*ServerIP[[:space:]]*:' "$SERVER_DIR/Config.yaml"; then
    sed -i -E "s|^[[:space:]]*ServerIP[[:space:]]*:.*|ServerIP: ${new_ip}|" "$SERVER_DIR/Config.yaml"
  else
    printf '\nServerIP: %s\n' "$new_ip" >>"$SERVER_DIR/Config.yaml"
  fi
  echo "[完成] 已将 $SERVER_DIR/Config.yaml 中的 ServerIP 修改为: $new_ip"
  echo "请执行 ./poptag.sh restart 使新地址生效。"
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_stop; cmd_start ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  fg)      cmd_fg ;;
  set-ip)  cmd_set_ip "${2:-}" ;;
  *)
    cat <<EOF
用法: $0 {start|stop|restart|status|logs|set-ip <IP>|fg}

  start            后台启动服务端
  stop             停止服务端
  restart          重启服务端
  status           查看运行状态与端口监听
  logs             实时查看运行日志
  set-ip <IP>      修改 Config.yaml 中的公网 ServerIP
  fg               前台运行（供 systemd / Docker 使用）
EOF
    exit 1
    ;;
esac
