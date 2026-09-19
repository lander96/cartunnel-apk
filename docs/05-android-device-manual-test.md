# CarTunnel 普通 Android 真机人工测试指南

用途：在普通 Android 9/11 `arm64-v8a` 真机上，对当前 CarTunnel debug 候选包做首轮体验和运行验证。

当前候选基线：

- 版本：`1.0.1-debug`（versionCode `2`）
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256：`4416635746a2c6c4f3d8880d0ce72996cc8f3259851328b4c67264c077556378`
- APK 大小：`21,096,495 bytes`
- 包名：`com.cartunnel.client.debug`
- 签名：Android Debug，证书 SHA-256 `f988ebba9b31078954ce2a778f86cb7b57cdd220d66030527cb5d2d5814b0fee`

> 这是设备运行测试包，不是正式发布包。初次测试使用专用测试 UUID，不要先放入长期生产凭据。

## 1. 测试前准备

### 1.1 记录设备基线

记录以下信息：

- 手机品牌和型号。
- Android 版本和安全补丁日期。
- CPU ABI，应为 `arm64-v8a`。
- 测试网络：Wi-Fi/蜂窝，是否具有 IPv6。
- VPS 公网 IPv4。
- 测试时间。

在未启动 CarTunnel 时，用浏览器访问：

- `https://api.ipify.org`：记录手机原始出口 IPv4。
- `https://api6.ipify.org`：若能打开，记录手机原始 IPv6；若打不开，说明当前网络本身可能没有 IPv6，无法用它证明 VPN 的 IPv6 阻断行为。

先关闭手机上其他 VPN、私有 DNS/VPN 类应用，避免干扰。

### 1.2 核对 APK

在 Mac 上执行：

```bash
cd '/Users/lander/MyDocument/chatgpt-workdir/畅流急速版'
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

输出必须与本文档顶部的 SHA-256 完全一致。

备份 `.cache/android-user/debug.keystore`。后续测试包若丢失该签名，将无法覆盖安装，只能卸载旧包并丢失其测试配置。

## 2. 安装和首次启动

1. 将 `app-debug.apk` 复制到手机。
2. 在系统设置中仅为本次使用的文件管理器/浏览器允许“安装未知应用”。
3. 打开 APK 完成安装，然后可关闭该“安装未知应用”权限。
4. 首次打开应用，确认不要求 root、ADB、账号登录、无障碍或悬浮窗权限。
5. 确认首页可见“全局 VPN / 本地代理”、当前节点、启动/停止、节点管理和设置入口。

如果手机上安装过使用其他 debug 证书的同包名 APK，覆盖安装可能报签名冲突。需先导出节点，再卸载旧测试包。

## 3. 节点配置与持久化

优先使用 Ubuntu Docker 部署脚本生成的 `cartunnel-profile.json` 或 `cartunnel-vless-uri.txt` 导入，可避免将服务端 PrivateKey 误填到客户端。

导入后进入“已有节点”，点击节点并选择“测试节点延迟”。只有经该节点完成 HTTPS 出口请求才显示“可用 · Nms”；这不是单纯 TCP 端口探测。点击启动后，同样只有实际出口检测成功才显示“已连接”和延迟。

在“节点管理”中手工新增节点：

| 客户端字段 | 填写值 |
| --- | --- |
| 节点名 | 自定义，例如 `Test VPS` |
| 服务器 | VPS 公网 IPv4 或指向它的域名 |
| 端口 | `443` |
| UUID | 服务端 `xray uuid` 输出 |
| SNI | REALITY `TARGET_HOST` |
| REALITY Password | `xray x25519` 输出的 `Password`，不是 `PrivateKey` |
| Short ID | 服务端 `openssl rand -hex 8` 输出 |

> 新版部署脚本会直接输出上述字段。`Hash32` 不是 Short ID，本客户端不使用它。

检查：

1. 字段错误能定位到对应输入框。
2. 保存后节点出现在列表并可设为当前。
3. 强制停止应用后重新打开，节点仍存在。
4. 测试 JSON 导出、VLESS URI 导出和再导入。导出文件包含连接凭据，仅保存在可信位置，测试后删除。

## 4. 本地代理模式

此模式不会透明接管手机网络，只有明确使用代理端口的应用流量才会进入 VPS。

1. 选择“本地代理”并启动。
2. 必须不出现 Android 系统 VPN 授权框，状态栏也不应出现 VPN 钥匙图标。
3. 应用应显示 `127.0.0.1:10808` SOCKS5 和 `127.0.0.1:10809` HTTP proxy。

推荐使用能显式指定代理的测试客户端。如手机已有 Termux/curl：

```bash
curl --proxy socks5h://127.0.0.1:10808 https://api.ipify.org
curl --proxy http://127.0.0.1:10809 https://api.ipify.org
```

两个命令都应返回 VPS 公网 IPv4。

如没有 Termux，可在当前 Wi-Fi 的高级设置中临时把 HTTP 代理设为 `127.0.0.1:10809`，用遵循 Android 系统代理的浏览器测试。测试结束必须将 Wi-Fi 代理恢复为“无”。

停止本地代理后：

- 前台通知应消失。
- 上述两个 curl 命令应立即失败。
- 普通未配置代理的应用始终使用手机原网络。

## 5. 全局 VPN 模式

1. 确保 Wi-Fi 代理已恢复为“无”。
2. 选择“全局 VPN”并启动。
3. 首次必须出现 Android 标准 VPN 授权框；选择同意。
4. 应用显示“全局 VPN 已连接”，系统显示 VPN 图标且存在前台通知。
5. 再次访问 `https://api.ipify.org`，结果必须是 VPS 公网 IPv4，不是手机原始出口。
6. 访问数个之前未打开的 HTTPS 域名，确认 DNS 解析和 HTTPS 正常。
7. 打开短视频/音频或其他常用 UDP 场景，确认播放、拖动进度和切换视频无明显异常。这是体验性 UDP 验证，不代替后续的专用测试。

### IPv6 阻断

只有在测试网络开启 VPN 前确实具有 IPv6 时，此项才有效。

1. VPN 开启前 `https://api6.ipify.org` 可访问。
2. VPN 开启后该纯 IPv6 站点应无法连接。
3. VPN 开启后 `https://api.ipify.org` 仍能正常返回 VPS IPv4。

如 VPN 前就无 IPv6，记录“测试网络无 IPv6，本项未验证”，不要记录为通过。

### 停止

1. 从应用主按钮停止，确认 3 秒内 VPN 图标和前台通知消失。
2. 再次访问 `https://api.ipify.org`，应恢复为手机原始出口。
3. 同一次安装再次启动 VPN，通常不应再弹出授权框。

## 6. 网络切换和重连

保持 VPN 已连接，每个场景恢复网络后都重新查询出口 IPv4：

1. Wi-Fi 关闭 10 秒后开启。
2. 如手机有 SIM，执行 Wi-Fi → 蜂窝 → Wi-Fi。
3. 飞行模式开启 10 秒后关闭。
4. 不等待完全连接，连续执行 10 次“停止 → 启动”。
5. 在“全局 VPN”和“本地代理”之间停止后切换，循环 10 次。

通过标准：

- 应用无崩溃、无永久“正在重连”。
- 网络恢复后在约 15 秒内重新可用；超时时记录实际耗时和界面状态。
- 恢复后公网 IPv4 始终是 VPS。
- 用户主动停止后不得自动复活。

## 7. Activity、休眠与重启

1. VPN 连接后回到桌面，从最近任务划掉 CarTunnel；等待 5 分钟。VPN 和通知应继续存在。
2. 熄屏 5 分钟后唤醒，再次验证 HTTPS 和 VPS 出口。
3. 在设置中开启“开机自动恢复”，保持 VPN 运行并重启手机。
4. 解锁后等待 30 秒，检查是否自动恢复上次模式。
5. 如没有恢复，记录手机的自启动/电池限制设置；只在确认是系统后台限制后，再为 CarTunnel 允许自启动或取消电池优化。
6. 用户手动停止后再重启手机，应用不得自动建立连接。

## 8. VPN 授权撤销

如手机已安装另一个可信的 VPN 应用：

1. 先连接 CarTunnel VPN。
2. 启动另一个 VPN，确认系统切换 VPN。
3. CarTunnel 应停止并显示授权被撤销/不可恢复错误，不得循环抢占 VPN。
4. 再次手动启动 CarTunnel 时，可能需要重新确认系统 VPN 授权。

## 9. 诊断与脱敏

1. 制造一次错误，例如临时关闭 VPS 端口或使用一个专用错误测试节点。
2. 在设置页复制脱敏诊断。
3. 确认诊断中不包含完整 VPS 域名/IP、UUID、REALITY Password、Short ID、VLESS URI 或 Xray JSON。
4. 清除日志后再复制，确认旧记录已消失。

## 10. 可选 ADB 取证

应用本身不依赖 ADB。如普通 Android 手机已开启 USB 调试，可在 Mac 上执行以下只读命令：

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell dumpsys package com.cartunnel.client.debug
adb shell dumpsys vpn
adb shell dumpsys activity services com.cartunnel.client.debug
adb shell dumpsys meminfo com.cartunnel.client.debug
```

日志如需归档，必须先检查并删除设备标识、公网 IP、域名和凭据。

## 11. 首轮结果记录

| 测试项 | 结果（通过/失败/未测） | 实际现象/耗时 | 截图或诊断编号 |
| --- | --- | --- | --- |
| 安装和首次启动 |  |  |  |
| 节点保存与恢复 |  |  |  |
| JSON/URI 导入导出 |  |  |  |
| 本地 SOCKS5 |  |  |  |
| 本地 HTTP proxy |  |  |  |
| VPN 首次授权 |  |  |  |
| VPN HTTPS/DNS |  |  |  |
| VPN 出口 IPv4 |  |  |  |
| VPN IPv6 阻断 |  |  |  |
| 音视频/UDP 体验 |  |  |  |
| Wi-Fi 断开恢复 |  |  |  |
| Wi-Fi/蜂窝切换 |  |  |  |
| 快速 Stop/Start |  |  |  |
| Activity 划掉后继续 |  |  |  |
| 熄屏/唤醒 |  |  |  |
| 开机自动恢复 |  |  |  |
| 手动停止后不恢复 |  |  |  |
| 其他 VPN 抢占 |  |  |  |
| 诊断脱敏 |  |  |  |

首轮体验通过后，再执行 100 次 start/stop、50 次模式切换和 8 小时稳定性测试；不要在首轮就投入长时间压测。
