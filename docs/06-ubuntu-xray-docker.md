# Ubuntu Docker 部署 Xray VLESS + REALITY

用途：使用 Xray 官方容器镜像，为 CarTunnel 提供 `VLESS + REALITY + XTLS Vision` 服务。

基线：`ghcr.io/xtls/xray-core:26.3.27`，与当前 Android 候选包的 Xray 版本保持一致。

网络示例：容器内监听 TCP `18443`，VPS 公网开放 TCP `8443`。

> 非 Docker 部署及 REALITY 参数原理见 [Ubuntu Xray 服务端部署](./03-ubuntu-xray-server.md)。两种方案二选一，不要同时让 systemd Xray 和 Docker 容器占用同一个公网端口。

项目提供了交互式 [deploy.sh](../deploy/xray-docker/deploy.sh)，会检查 Ubuntu、Docker 和 Compose，自动生成凭据、服务端配置、Compose 文件以及可直接导入 CarTunnel 的 JSON/VLESS URI。脚本默认公网端口为 TCP `443`，不会安装 Docker 或修改系统软件源。

## 1. 端口结论

CarTunnel 支持 `1–65535` 范围内的 TCP 服务端口。服务端和客户端只需在“公网端口”上保持一致。

Docker 端口映射形式：

```text
手机 -> VPS_PUBLIC_IP:PUBLIC_PORT -> Docker NAT -> container:18443 -> Xray
```

示例：

| 场景 | Compose `ports` | Xray JSON `port` | CarTunnel 端口 |
| --- | --- | --- | --- |
| 推荐兼容性 | `443:18443/tcp` | `18443` | `443` |
| 避开已占用的 443 | `8443:18443/tcp` | `18443` | `8443` |
| 其他自定义端口 | `PUBLIC_PORT:18443/tcp` | `18443` | `PUBLIC_PORT` |

注意：

- REALITY 的 `target` 仍然通常是 `TARGET_HOST:443`，不随 VPS 公网监听端口改变。
- TCP `443` 对车机、蜂窝网络和公共 Wi-Fi 的兼容性通常最好。只在 443 已被占用或有明确网络策略时改用 `8443` 等端口。
- 不要使用 SSH 端口、已占用端口或云厂商禁止的端口。
- 这套配置只需开放 TCP，不需要映射同端口的 UDP。

## 2. 准备 Docker

在 Ubuntu 22.04/24.04 VPS 上安装 Docker Engine 和 Compose plugin。建议按 [Docker 官方 Ubuntu 安装文档](https://docs.docker.com/engine/install/ubuntu/) 配置官方 APT 仓库，然后确认：

```bash
sudo docker version
sudo docker compose version
sudo systemctl enable --now docker
```

推荐直接使用项目中的单文件部署脚本：

```bash
chmod 0755 deploy.sh
sudo ./deploy.sh
```

脚本会交互式确认部署目录、客户端访问地址、公网端口、REALITY target/SNI 和节点名。默认 target/SNI 为已在 Xray 26.3.27 上完成实际 REALITY 握手验证的 `www.samsung.com`。它只检查 Docker/Compose，不安装任何系统软件。成功后会生成：

```text
config/config.json
compose.yaml
client/client-info.txt
client/cartunnel-profile.json
client/cartunnel-vless-uri.txt
```

`cartunnel-profile.json` 和 `cartunnel-vless-uri.txt` 均可直接导入 CarTunnel。客户端文件权限为 `0600`，包含完整连接凭据。服务端 PrivateKey 只写入 `config/config.json`，不会输出到客户端文件。

以下各节保留为手工部署与排障参考。使用脚本时不需要手工重复创建这些文件。

创建部署目录：

```bash
sudo install -d -m 0755 /opt/cartunnel-node/config
cd /opt/cartunnel-node
```

## 3. 生成凭据

使用与服务端一致的固定镜像生成：

```bash
sudo docker pull ghcr.io/xtls/xray-core:26.3.27
sudo docker run --rm ghcr.io/xtls/xray-core:26.3.27 uuid
sudo docker run --rm ghcr.io/xtls/xray-core:26.3.27 x25519
openssl rand -hex 8
```

保存：

- UUID：服务端和客户端共用。
- `PrivateKey`：只放在服务端 `config.json`。
- `Password`：填入 CarTunnel 的 `REALITY Password`。
- Short ID：服务端和客户端共用。

## 4. 选择 REALITY target

将 `TARGET_HOST` 换成候选 HTTPS 域名：

```bash
sudo docker run --rm ghcr.io/xtls/xray-core:26.3.27 tls ping TARGET_HOST
```

确认返回证书的 SAN 包含 `TARGET_HOST`，且该站点可从 VPS 稳定访问。

`tls ping` 只检查目标站 TLS 能力，不代表 REALITY 全链路一定可用。Xray 26.3.27 对某些证书握手记录较大的站点可能拒绝握手；修改默认值后，需用客户端实际发起 VLESS/REALITY 请求验证。

## 5. 写入 Xray 配置

可直接使用项目中的 [config.json](../deploy/xray-docker/config/config.json)。

编辑 `/opt/cartunnel-node/config/config.json`，替换：

- `CLIENT_UUID`
- `TARGET_HOST`
- `SERVER_PRIVATE_KEY`
- `SHORT_ID`

```json
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "vless-reality-in",
      "listen": "0.0.0.0",
      "port": 18443,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "CLIENT_UUID",
            "flow": "xtls-rprx-vision"
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "target": "TARGET_HOST:443",
          "xver": 0,
          "serverNames": [
            "TARGET_HOST"
          ],
          "privateKey": "SERVER_PRIVATE_KEY",
          "shortIds": [
            "SHORT_ID"
          ]
        }
      }
    }
  ],
  "outbounds": [
    {
      "tag": "direct",
      "protocol": "freedom"
    },
    {
      "tag": "block",
      "protocol": "blackhole"
    }
  ]
}
```

限制配置权限，同时让官方镜像中的非 root 用户可读：

```bash
sudo chown root:65532 /opt/cartunnel-node/config/config.json
sudo chmod 0640 /opt/cartunnel-node/config/config.json
```

镜像内的 Xray 以 UID/GID `65532` 的非 root 身份运行，只读挂载的配置需允许该组读取。

## 6. 写入 Compose 配置

可直接使用项目中的 [compose.yaml](../deploy/xray-docker/compose.yaml)。

编辑 `/opt/cartunnel-node/compose.yaml`：

```yaml
services:
  xray:
    image: ghcr.io/xtls/xray-core:26.3.27
    container_name: cartunnel-node
    restart: unless-stopped
    mem_limit: 1g
    ports:
      - "8443:18443/tcp"
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
        max-size: "10m"
        max-file: "3"
```

如需公网 TCP 443，只把端口映射改为：

```yaml
ports:
  - "443:18443/tcp"
```

不需要修改 Xray JSON 内的 `18443`。

## 7. 校验并启动

```bash
cd /opt/cartunnel-node
sudo docker compose config
sudo docker compose run --rm --no-deps xray run -test -confdir /usr/local/etc/xray/
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs --tail=100 xray
sudo ss -lntp 'sport = :8443'
```

如果公网端口改成 443，最后一条改查 `:443`。

只有配置测试成功且容器处于 `running`时，才进行手机端连接。

## 8. 防火墙和云安全组

在云厂商安全组/网络 ACL 中开放对应的 TCP 公网端口，例如 `8443/tcp`。

Docker 发布的端口可能绕过 UFW 的常规入站规则，因此：

- 不要认为“UFW 未 allow”就代表 Docker 端口一定不可达。
- 优先用云安全组限定对外端口。
- 需要更精细的主机策略时，在 `DOCKER-USER` chain 中配置，不要只依赖 UFW。
- Compose 中只发布一个所需 TCP 端口，不发布管理 API。

## 9. 填写 CarTunnel 节点

按上面 `8443:18443/tcp` 的示例：

| CarTunnel 字段 | 填写值 |
| --- | --- |
| 服务器 | VPS 公网 IPv4 或指向它的域名 |
| 端口 | `8443` |
| UUID | `xray uuid` 输出 |
| SNI | `TARGET_HOST` |
| REALITY Password | `xray x25519` 输出的 `Password` |
| Short ID | `openssl rand -hex 8` 输出 |

如 Compose 左侧改为 `443`，CarTunnel 端口也改为 `443`。客户端不需知道容器内部的 `18443`。

## 10. 日常维护

```bash
cd /opt/cartunnel-node
sudo docker compose ps
sudo docker compose logs --since=30m xray
sudo docker compose restart xray
sudo docker inspect --format '{{.Config.Image}}' cartunnel-node
```

配置变更后：

```bash
sudo docker compose run --rm --no-deps xray run -test -confdir /usr/local/etc/xray/
sudo docker compose up -d
```

升级时不要直接改成 `latest`。显式更换镜像版本，在测试节点完成 TCP、UDP、REALITY 和重连验收后，再升级正式节点。

## 11. 最小验收

1. `docker compose run ... -test` 通过。
2. `docker compose ps` 显示 Xray 运行。
3. VPS 外部能连通 `PUBLIC_PORT/tcp`。
4. CarTunnel 连接后访问 `https://api.ipify.org`，返回 VPS 公网 IPv4。
5. 重启容器后，CarTunnel 能自动恢复。
6. 只发布了预期 TCP 端口，无额外管理端口。

## 12. 官方资料

- [Xray 官方 Docker 镜像与目录结构](https://xtls.github.io/en/document/install.html#docker-installation)
- [Xray-core 官方容器包](https://github.com/XTLS/Xray-core/pkgs/container/xray-core)
- [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [REALITY 配置字段](https://xtls.github.io/en/config/transports/reality.html)
