# Ubuntu 部署 Xray VLESS + REALITY 服务端

用途：给 CarTunnel 客户端提供单节点上游  
范围：常规 Ubuntu 22.04/24.04 VPS，公网 IP，TCP 443 可入站  
基线：Xray `v26.3.27`；升级前先做客户端兼容测试  
说明：这是一份通用部署指引，不包含云厂商控制台、域名购买或隐蔽性专项调优

## 1. 准备条件

- 一台具有公网 IPv4 的 Ubuntu VPS。
- root/sudo 权限。
- 云安全组和主机防火墙都允许 TCP 443 入站。
- TCP 443 未被 Nginx、Caddy、Apache 或其他程序占用。
- 系统时间正确。建议启用 Ubuntu 默认的 systemd-timesyncd/chrony。
- 一个适合作为 REALITY `target`/SNI 的 HTTPS 站点。

REALITY 不要求你为 VPS 购买域名或证书。`target` 应支持正常 TLS 1.3，连接稳定，尽量与 VPS 位于相同 ASN/邻近网络。不要不加评估地选择免费 CDN 目标；未认证连接会被转发到 target，错误选择可能使 VPS 成为可被滥用的转发节点。以 Xray 官方 REALITY 文档和 `xray tls ping` 实测为准。

## 2. 安装固定版本 Xray

先更新系统并安装基础工具：

```bash
sudo apt update
sudo apt install -y curl ca-certificates unzip openssl ufw
```

使用 XTLS 官方安装脚本并显式固定版本：

```bash
sudo bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install --version v26.3.27
```

确认版本：

```bash
/usr/local/bin/xray version
systemctl cat xray
```

高安全环境应进一步把安装脚本固定到审核过的 commit，并验证下载产物摘要。官方脚本会下载对应版本和 `.dgst` 校验文件，但脚本的 `main` 分支本身仍会变化。

## 3. 生成凭据

生成一个用户 UUID：

```bash
/usr/local/bin/xray uuid
```

生成 REALITY X25519 密钥材料：

```bash
/usr/local/bin/xray x25519
```

记录输出中的两项：

- `PrivateKey`：只写入服务端配置，绝不能发给客户端。
- `Password`：由公钥派生、由客户端持有。旧版文档/URI 常称它 `PublicKey` 或 `pbk`。

生成 8-byte short ID：

```bash
openssl rand -hex 8
```

short ID 必须是偶数长度的十六进制字符串，最多 16 个字符。本文命令会生成完整 16 字符。

## 4. 选择并检查 REALITY target

用候选域名替换 `TARGET_HOST`：

```bash
/usr/local/bin/xray tls ping TARGET_HOST
```

确认：

- 目标可从 VPS 稳定访问 443。
- 返回证书的 SAN 包含你将填写的 `serverName`。
- 不使用自己无法解释或明显不合适的 CDN/特殊地址。
- `target` 与 `serverNames` 保持一致，首版只配置一个名称。

不要把 VPS 自身 IP 或监听中的 `:443` 配成 target，否则会形成循环。

## 5. 写入最小服务端配置

备份现有配置：

```bash
sudo cp /usr/local/etc/xray/config.json /usr/local/etc/xray/config.json.bak
```

编辑 `/usr/local/etc/xray/config.json`，替换以下四个占位符：

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
      "port": 443,
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

配置文件包含服务端私钥。权限必须只允许 root 和 Xray 实际 service account 读取。先从 `systemctl cat xray` 确认 `User=`，再设置 owner/group；不要盲目设成 root-only 导致服务无法读取。

## 6. 校验、启动与开放端口

先做配置语法/语义校验：

```bash
sudo /usr/local/bin/xray run -test -config /usr/local/etc/xray/config.json
```

只有看到配置测试成功后才重启：

```bash
sudo systemctl enable xray
sudo systemctl restart xray
sudo systemctl status xray --no-pager
sudo ss -lntp 'sport = :443'
```

配置 UFW 前先保留 SSH：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

若云厂商还有安全组/网络 ACL，同样开放 TCP 443。无需为这套配置开放 UDP 443，也不要开放 Xray 管理 API。

查看最近日志：

```bash
sudo journalctl -u xray -n 100 --no-pager
```

## 7. 客户端字段对应关系

在 CarTunnel 中新增节点：

同一组节点参数可用于“全局 VPN”和“本地代理”两种客户端模式；这是客户端取流方式的区别，不要求服务端配置两套 inbound。

| CarTunnel 字段 | 服务端来源 |
| --- | --- |
| 节点名 | 自定义，例如 `My VPS` |
| 服务器 | VPS 公网 IP 或指向它的 DNS 名 |
| 端口 | `443` |
| UUID | `xray uuid` 输出，等于 `CLIENT_UUID` |
| Server Name / SNI | `TARGET_HOST` |
| REALITY Password | `xray x25519` 输出中的 `Password`，不是 `PrivateKey` |
| Short ID | `SHORT_ID` |
| Fingerprint | `chrome` |
| Flow | `xtls-rprx-vision` |
| Transport | `raw`；URI 中 `tcp` 也会被客户端归一化 |
| Spider X | `/` |

服务端 `PrivateKey` 永远不进入手机/车机。客户端导出的 `vless://` URI 含 UUID、Password 和 Short ID，应按凭据保护。

## 8. 最小验收

1. 服务端 `xray run -test` 成功，systemd 状态为 active。
2. `ss` 显示 Xray 监听 `0.0.0.0:443`。
3. 从 VPS 外部确认 TCP 443 可达。
4. CarTunnel 能建立连接，访问 HTTPS 正常。
5. 车机 DNS 正常，常见 UDP 应用可用。
6. 查询公网 IPv4 时显示 VPS 出口地址。
7. VPN 开启时 IPv6 被阻断而不是走车机物理网络泄漏。
8. 重启 Xray 后客户端能按退避自动恢复。

## 9. 日常维护

常用命令：

```bash
sudo systemctl status xray --no-pager
sudo systemctl restart xray
sudo journalctl -u xray --since '30 minutes ago' --no-pager
/usr/local/bin/xray version
```

升级时不要直接跟随 `latest`。先阅读 Xray release notes，在测试 VPS 安装明确版本，验证与固定 Android core 的互通，再在生产节点执行：

```bash
sudo bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install --version vNEW_VERSION
```

升级前保留配置和可回退的旧二进制/快照。升级成功只表示进程启动，不等于 TCP、UDP、REALITY 和客户端重连均健康。

## 10. 凭据轮换与多设备

- 每台车机建议使用独立 UUID，便于单独撤销。
- 可在 `settings.clients` 数组中增加多个 `{id, flow}`，不必共享 UUID。
- short ID 也可为不同客户端分配不同值，并全部加入服务端 `shortIds`。
- REALITY 私钥轮换会影响所有客户端；先下发新的客户端 Password，再切换服务端，或安排停机窗口。
- 删除某个客户端 UUID 后，校验配置并 restart Xray 即可撤销。

## 11. 常见故障定位

### 端口不可达

依次检查 Xray service、`ss`、UFW、云安全组、VPS 公网 IP 和运营商网络。不要只看 `systemctl active` 就判定网络正常。

### REALITY 握手失败

重点核对：

- 客户端填的是 `Password`，不是 `PrivateKey`。
- UUID、short ID、SNI 完全一致，short ID 为偶数长度十六进制。
- 客户端 `flow=xtls-rprx-vision`、`security=reality`、内部 transport 为 `raw`。
- `TARGET_HOST` 当前可从 VPS 访问，证书 SAN 与 SNI 匹配。
- VPS 与车机时间没有明显偏差。
- Android core 和服务端 Xray 版本处于已验证组合。

### TCP 正常但 UDP/DNS 异常

确认 Android 端 SOCKS inbound 开启 UDP、HEV `socks5.udp=udp`、Xray 版本支持当前 Vision/XUDP 组合。先用固定 DNS 查询复现，再测音视频；不要通过让 DNS 直连来掩盖问题。

### CPU 或内存异常

先限定时间查看 Xray 日志和连接数，确认没有未认证 fallback 被滥用；再检查 target 选择、客户端重连风暴和服务端版本。不要直接提高资源或无限重启。

## 12. 官方资料

- [Xray 官方安装项目](https://github.com/XTLS/Xray-install)
- [Xray 配置总览](https://xtls.github.io/en/config/)
- [VLESS 入站配置](https://xtls.github.io/en/config/inbounds/vless.html)
- [REALITY 配置字段](https://xtls.github.io/en/config/transports/reality.html)
- [Xray-core Releases](https://github.com/XTLS/Xray-core/releases)
