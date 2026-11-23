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
SERVICE_NAME="poptag-server"
NO_SYSTEMD=0
PUBLIC_IP_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip)
      PUBLIC_IP_ARG="${2:-}"
      shift 2
      ;;
    --no-systemd)
      NO_SYSTEMD=1
      shift
      ;;
    *)
      shift
      ;;
  esac
done

chmod +x "$ROOT_DIR/poptag.sh" "$ROOT_DIR/install.sh"
mkdir -p "$ROOT_DIR/logs" "$SERVER_DIR/data/local-accounts"

echo "=================================================="
echo "   PopTag 2010 Linux 独立服务端 一键安装向导"
echo "=================================================="
echo "安装目录: $ROOT_DIR"
echo ""

# 1. 检测并安装 Java 21
need_java=1
if command -v java >/dev/null 2>&1; then
  jver="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F'.' '{print $1}')"
  if [[ -n "$jver" && "$jver" -ge 21 ]]; then
    echo "[1/4] 已检测到 Java $jver: $(java -version 2>&1 | head -n 1)"
    need_java=0
  fi
fi

if [[ "$need_java" -eq 1 ]]; then
  echo "[1/4] 未检测到 Java 21+，准备自动安装 OpenJDK 21..."
  if [[ "$EUID" -ne 0 ]]; then
    echo "[错误] 自动安装 Java 需要 root 权限，请使用: sudo bash install.sh" >&2
    exit 1
  fi
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y openjdk-21-jre-headless
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-21-openjdk-headless
  elif command -v yum >/dev/null 2>&1; then
    yum install -y java-21-openjdk-headless
  else
    echo "[错误] 无法识别包管理器，请手动安装 OpenJDK 21 后再运行本脚本。" >&2
    exit 1
  fi
  echo "[1/4] Java 安装完成: $(java -version 2>&1 | head -n 1)"
fi

# 2. 配置公网 IP
echo ""
echo "[2/4] 配置公网 ServerIP..."
detected_ip=""
if [[ -n "$PUBLIC_IP_ARG" ]]; then
  detected_ip="$PUBLIC_IP_ARG"
else
  if command -v curl >/dev/null 2>&1; then
    detected_ip="$(curl -4 -fsS --max-time 4 https://api.ip.sb/ip 2>/dev/null \
      || curl -4 -fsS --max-time 4 https://ifconfig.me/ip 2>/dev/null \
      || curl -4 -fsS --max-time 4 https://icanhazip.com 2>/dev/null \
      || true)"
    detected_ip="$(echo "$detected_ip" | tr -d '[:space:]')"
  fi
  if [[ -t 0 ]]; then
    if [[ -n "$detected_ip" ]]; then
      read -r -p "自动检测到公网 IPv4 为 [$detected_ip]，直接回车使用，或手动输入 IP/域名: " input_ip
      chosen_ip="${input_ip:-$detected_ip}"
    else
      read -r -p "未能自动检测公网 IP，请输入你的服务器公网 IPv4（例如 123.45.67.89）: " chosen_ip
    fi
  else
    chosen_ip="${detected_ip:-auto}"
  fi
  detected_ip="$chosen_ip"
fi

if [[ -n "$detected_ip" ]]; then
  "$ROOT_DIR/poptag.sh" set-ip "$detected_ip"
else
  echo "[警告] 未填写公网 IP，保留 Config.yaml 中的默认值，后续请运行: ./poptag.sh set-ip <你的公网IP>"
fi

# 3. 放行 Linux 本地防火墙端口（如有启用）
echo ""
echo "[3/4] 检查并放行 Linux 本机防火墙端口..."
if [[ "$EUID" -eq 0 ]]; then
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi "Status: active"; then
    for p in 3838/tcp 4848/tcp 4849/tcp 47611/tcp 3839/udp 4849/udp 4151:4160/udp; do
      ufw allow "$p" >/dev/null || true
    done
    echo "  已通过 UFW 放行 TCP(3838,4848,4849,47611) 和 UDP(3839,4849,4151-4160)"
  elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state 2>/dev/null | grep -qi "running"; then
    for p in 3838/tcp 4848/tcp 4849/tcp 47611/tcp 3839/udp 4849/udp 4151-4160/udp; do
      firewall-cmd --permanent --add-port="$p" >/dev/null || true
    done
    firewall-cmd --reload >/dev/null || true
    echo "  已通过 firewalld 放行 TCP(3838,4848,4849,47611) 和 UDP(3839,4849,4151-4160)"
  else
    echo "  未检测到已启用的 ufw/firewalld，跳过本机防火墙配置。"
  fi
else
  echo "  非 root 模式，跳过本机防火墙配置。"
fi

# 4. 安装 systemd 服务并启动
echo ""
if [[ "$NO_SYSTEMD" -eq 0 && "$EUID" -eq 0 && -d /run/systemd/system ]]; then
  echo "[4/4] 安装并启动 systemd 开机自启服务 ($SERVICE_NAME)..."
  cat >"/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=PopTag 2010 Multiplayer Game Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${SERVER_DIR}
ExecStart=${ROOT_DIR}/poptag.sh fg
Restart=always
RestartSec=3
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable "$SERVICE_NAME"
  systemctl restart "$SERVICE_NAME"
  sleep 2
  "$ROOT_DIR/poptag.sh" status
else
  echo "[4/4] 以独立后台模式启动服务端..."
  POPTAG_STANDALONE=1 "$ROOT_DIR/poptag.sh" restart
fi

echo ""
echo "=================================================="
echo "   安装完成！公网开服必须确认以下云服务器安全组端口："
echo "   TCP 放行: 3838, 4848, 4849, 47611"
echo "   UDP 放行: 3839, 4849, 4151-4160"
echo ""
echo "   玩家 Windows 客户端连接方法："
echo "   双击游戏客户端 client/改服务器地址.cmd，输入: ${detected_ip:-你的公网IP}"
echo "   管理命令: ./poptag.sh {status|restart|stop|logs|set-ip <IP>}"
echo "=================================================="
