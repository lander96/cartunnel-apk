#!/usr/bin/env bash
# Self-contained Ubuntu deployment for CarTunnel VMess AEAD + WebSocket.
set -Eeuo pipefail
umask 077

readonly IMAGE='ghcr.io/xtls/xray-core:26.3.27'
readonly CONTAINER_NAME='cartunnel-node'
readonly CONTAINER_PORT=18443

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ask() {
    local prompt="$1" default_value="$2" variable_name="$3" value
    read -r -p "$prompt [$default_value]: " value
    printf -v "$variable_name" '%s' "${value:-$default_value}"
}

[[ "$EUID" -eq 0 ]] || die '请在 Ubuntu 上使用 root 运行'
[[ -r /etc/os-release ]] || die '无法确认 Ubuntu 环境'
# shellcheck disable=SC1091
source /etc/os-release
[[ "${ID:-}" == ubuntu ]] || die '仅支持 Ubuntu'
command -v docker >/dev/null 2>&1 || die '未安装 Docker Engine'
docker compose version >/dev/null 2>&1 || die '未安装 Docker Compose plugin'
docker info >/dev/null 2>&1 || die 'Docker daemon 不可用'

if [[ -t 0 ]]; then
    ask '部署目录' '/opt/cartunnel-node' DEPLOY_DIR
    read -r -p '服务器公网 IPv4 或域名: ' SERVER_ADDRESS
    ask '映射到公网的 TCP 端口' '8443' PUBLIC_PORT
    printf '%s\n' 'WS Host 用于匹配车机网络白名单；默认 www.baidu.com，请按车机和运营商网络自行测试。'
    ask 'WS Host（车机白名单域名）' 'www.baidu.com' WS_HOST
else
    DEPLOY_DIR="${DEPLOY_DIR:-/opt/cartunnel-node}"
    SERVER_ADDRESS="${SERVER_ADDRESS:-}"
    PUBLIC_PORT="${PUBLIC_PORT:-8443}"
    WS_HOST="${WS_HOST:-www.baidu.com}"
fi

readonly DEPLOY_DIR SERVER_ADDRESS PUBLIC_PORT WS_HOST
[[ "$DEPLOY_DIR" == /* && "$DEPLOY_DIR" != / && "$DEPLOY_DIR" != /opt && "$DEPLOY_DIR" != *'/../'* && "$DEPLOY_DIR" != *'/..' ]] || die '部署目录不安全'
[[ "$DEPLOY_DIR" =~ ^/[A-Za-z0-9_/-]+$ ]] || die '部署目录只能使用简单的绝对路径字符'
[[ -n "$SERVER_ADDRESS" && "$SERVER_ADDRESS" =~ ^[A-Za-z0-9.-]+$ ]] || die '公网 IPv4 或域名格式无效'
[[ "$PUBLIC_PORT" =~ ^[1-9][0-9]*$ ]] && (( PUBLIC_PORT <= 65535 )) || die '映射端口必须是 1-65535'
[[ "$WS_HOST" =~ ^[A-Za-z0-9.-]+$ ]] || die 'WS Host 格式无效'
[[ ! -L "$DEPLOY_DIR" && ! -e "$DEPLOY_DIR/compose.yaml" && ! -e "$DEPLOY_DIR/config/config.json" ]] || die '部署目录已有配置，不会覆盖现有凭据'
[[ -z "$(docker ps -a --filter "name=^/${CONTAINER_NAME}$" --format '{{.Names}}')" ]] || die '容器名称已被占用'
if command -v ss >/dev/null 2>&1 && ss -H -ltn "sport = :$PUBLIC_PORT" | grep -q .; then
    die "TCP $PUBLIC_PORT 已被占用"
fi

docker pull "$IMAGE" >/dev/null
uuid="$(docker run --rm "$IMAGE" uuid | tr -d '\r\n')"
profile_id="$(docker run --rm "$IMAGE" uuid | tr -d '\r\n')"
[[ "$uuid" =~ ^[0-9a-fA-F-]{36}$ && "$profile_id" =~ ^[0-9a-fA-F-]{36}$ ]] || die '无法生成有效 UUID'
ws_path="/ctv-${profile_id//-/}"

stage="$(mktemp -d /tmp/cartunnel-node.XXXXXXXX)"
trap 'rm -rf -- "$stage"' EXIT
mkdir -p "$stage/config" "$stage/client"

cat > "$stage/config/config.json" <<EOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "tag": "vmess-ws-in",
    "listen": "0.0.0.0",
    "port": $CONTAINER_PORT,
    "protocol": "vmess",
    "settings": {"clients": [{"id": "$uuid", "alterId": 0}]},
    "streamSettings": {
      "network": "ws",
      "security": "none",
      "wsSettings": {"path": "$ws_path"}
    }
  }],
  "outbounds": [
    {"tag": "direct", "protocol": "freedom"},
    {"tag": "block", "protocol": "blackhole"}
  ]
}
EOF

cat > "$stage/compose.yaml" <<EOF
services:
  xray:
    image: $IMAGE
    container_name: $CONTAINER_NAME
    restart: unless-stopped
    mem_limit: 256m
    stop_grace_period: 10s
    user: "0:0"
    ports:
      - "$PUBLIC_PORT:$CONTAINER_PORT/tcp"
    volumes:
      - ./config:/usr/local/etc/xray:ro
    read_only: true
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    logging:
      driver: local
      options:
        max-size: "5m"
        max-file: "2"
EOF

cat > "$stage/client/cartunnel-profile.json" <<EOF
[{"schemaVersion":2,"id":"$profile_id","name":"Car VMess WS $PUBLIC_PORT","server":"$SERVER_ADDRESS","port":$PUBLIC_PORT,"uuid":"$uuid","wsHost":"$WS_HOST","wsPath":"$ws_path"}]
EOF

vmess_json="$(printf '{"v":"2","ps":"Car VMess WS %s","add":"%s","port":"%s","id":"%s","aid":"0","scy":"auto","net":"ws","type":"none","host":"%s","path":"%s","tls":"","sni":""}' "$PUBLIC_PORT" "$SERVER_ADDRESS" "$PUBLIC_PORT" "$uuid" "$WS_HOST" "$ws_path")"
vmess_uri="vmess://$(printf '%s' "$vmess_json" | base64 -w 0)"
printf '%s\n' "$vmess_uri" > "$stage/client/cartunnel-vmess-uri.txt"
cat > "$stage/client/client-info.txt" <<EOF
CarTunnel VMess + WebSocket 节点（包含凭据，请勿公开）
服务器: $SERVER_ADDRESS
端口: $PUBLIC_PORT/tcp
UUID: $uuid
WS Host: $WS_HOST
WS Path: $ws_path
TLS: none
VMess URI: $vmess_uri
请在云安全组和主机防火墙放行 TCP $PUBLIC_PORT。
WS Host 是车机白名单域名，默认 www.baidu.com，实际可用性需自行测试。
EOF

mkdir -p "$DEPLOY_DIR/config" "$DEPLOY_DIR/client"
cp "$stage/compose.yaml" "$DEPLOY_DIR/compose.yaml"
cp "$stage/config/config.json" "$DEPLOY_DIR/config/config.json"
cp "$stage/client/"* "$DEPLOY_DIR/client/"
chmod 0755 "$DEPLOY_DIR"
chmod 0700 "$DEPLOY_DIR/config" "$DEPLOY_DIR/client"
chmod 0644 "$DEPLOY_DIR/compose.yaml"
chmod 0600 "$DEPLOY_DIR/config/config.json" "$DEPLOY_DIR/client/"*

docker compose -f "$DEPLOY_DIR/compose.yaml" up -d
for _ in 1 2 3 4 5; do
    [[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" == true ]] && break
    sleep 1
done
[[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" == true ]] || die '容器未正常运行，请查看 docker compose logs'

docker compose -f "$DEPLOY_DIR/compose.yaml" ps
printf '\n部署完成。\n'
printf '客户端 JSON: %s/client/cartunnel-profile.json\n' "$DEPLOY_DIR"
printf 'VMess URI: %s/client/cartunnel-vmess-uri.txt\n' "$DEPLOY_DIR"
printf '连接信息: %s/client/client-info.txt\n' "$DEPLOY_DIR"
printf '请确认云安全组和防火墙已放行 TCP %s。\n' "$PUBLIC_PORT"
