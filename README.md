# CarTunnel

面向车机的自建节点 VPN 客户端，可利用 VPN 服务绕过车机流量管控。已测试支持 iCAR 03，其余车机需自行测试。

车机流量到期后，实际存在一些白名单域名可访问，如：baidu.com
该apk使用websocket伪装流量域名发到vpn服务器，再由vpn服务器进行分发，需要自建相应vpn服务并开放公网访问，该项目提供服务端部署方式。

理论上支持所有以该方式限制流量的车机或android硬件。

## 已支持功能

- VMess AEAD + WebSocket、全局 IPv4 VPN，适用于 Android 9 及以上 arm64 设备。
- JSON / VMess URI 导入导出、手工配置、多节点选择与管理。
- 节点出口延迟测试、一键连接与断开。
- 开机连接、失败重试、网络切换重连、唤醒恢复及绕过局域网。
- 本地凭据加密保存、脱敏诊断与复制。
- 横屏双栏、竖屏布局及系统浅色 / 深色主题。
- 首次使用展示 MIT 协议及免责声明，同意后进入应用。

## 安装和使用

1. 通过[adb助手](https://github.com/lander96/cartunnel-apk/releases/tag/adb%E5%8A%A9%E6%89%8B)安装 [CarTunnel.apk](https://github.com/lander96/cartunnel-apk/releases/)。
2. 首次安装后，连接 ADB 并执行 VPN 授权命令：

   ```sh
   adb shell appops set com.cartunnel.client.debug ACTIVATE_VPN allow
   ```

   已进入 ADB shell 时执行：`appops set com.cartunnel.client.debug ACTIVATE_VPN allow`。
3. 打开应用，阅读并同意 MIT 协议及免责声明。
4. 点击 **导入配置**，选择服务端生成的 JSON 或 VMess URI 文件；也可通过 **配置** 手工新增或粘贴导入。
5. 选择节点后点击 **连接**；需要停止时点击 **断开**。设置和诊断入口位于 **设置**。

## 服务端部署

服务端只需要 [deploy.sh](deploy/server/deploy.sh)。准备 Ubuntu、Docker Engine 和 Docker Compose plugin，将脚本复制到服务器后执行：

```sh
sudo bash deploy.sh
```

脚本会交互式询问部署目录、公网 IPv4 或域名、映射到公网的 TCP 端口以及 WS Host，然后生成 Xray 配置、`compose.yaml` 和客户端导入凭据并启动容器。WS Host 默认 `www.baidu.com`，用于匹配车机网络白名单；不同车机和运营商的白名单策略可能不同，需要自行测试，也可以部署时输入其他域名。

部署完成后，在云安全组和主机防火墙放行所输入的 TCP 端口。客户端 JSON 位于部署目录的 `client/cartunnel-profile.json`，可通过应用的 **导入配置** 直接导入；`client/cartunnel-vmess-uri.txt` 可用于粘贴导入。凭据文件仅 root 可读，请勿公开。

自动化部署可通过环境变量提供参数；非交互模式必须设置 `SERVER_ADDRESS`，可选设置 `DEPLOY_DIR`、`PUBLIC_PORT` 和 `WS_HOST`。脚本发现已有配置、同名容器或端口占用时会停止，不覆盖现有凭据。

## 开源协议与使用免责

本项目自主开发部分采用 [MIT License](LICENSE)，允许使用、复制、修改和分发，分发时须保留版权及许可声明。第三方组件遵循各自许可证，详见 [第三方声明](licenses/THIRD_PARTY_NOTICES.md)。

软件按原样提供，不提供任何明示或默示担保，包括适销性、特定用途适用性及不侵权担保。作者或版权持有人不对因软件或其使用产生的索赔、损害或其他责任负责。完整条款以 [MIT 协议原文](LICENSE) 为准。
