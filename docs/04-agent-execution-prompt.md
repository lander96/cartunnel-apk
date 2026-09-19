# CarTunnel 开发 Agent 提示词

以下内容可直接作为新开发 Agent 的首条任务消息。若 Agent 与本文档位于同一工作区，保留相对路径即可；若不在同一工作区，应同时提供第 2 节列出的文件。

## 1. 可复制提示词

```text
你是 CarTunnel 项目的主开发 Agent。请在当前工作区完成一个可构建、可测试、可侧载的精简 Android 车机网络客户端，不要只输出示例代码或停在脚手架阶段。

开始前必须完整阅读并遵循以下文档，优先级从高到低：
1. docs/README.md
2. docs/01-client-technical-design.md
3. docs/02-development-implementation-plan.md
4. docs/03-ubuntu-xray-server.md

目标设备以 iCAR 03 系列为主，Android 9/11、arm64-v8a、无 root、无 Google Play Services。应用使用用户自建的 Xray 服务端和手工节点配置。协议只做 VLESS + REALITY + XTLS Vision。

必须实现两个互斥运行模式：
- 全局 VPN：VpnService/TUN -> hev-socks5-tunnel -> 127.0.0.1:10808 SOCKS5 -> Xray -> VPS。首次使用由用户确认标准系统 VPN 授权。
- 本地代理：只启动 Xray，在 127.0.0.1:10808 提供 SOCKS5、127.0.0.1:10809 提供 HTTP proxy；不请求 VPN 授权、不创建 TUN、不启动 HEV，也不宣称能透明修改系统路由。

严格保持小而美：不做账号、订阅、公告、WebView、在线更新、静默安装、通知监听、悬浮窗、无障碍、root、内置 ADB、私有 OEM API、按应用分流或 IPv6 转发。不得复制、抽取或重新打包参考 APK 的源码、资源、密钥、证书、域名或 native library；参考 APK 只能用于独立观察兼容行为。Xray/HEV 必须来自锁定版本的公开上游源码，并满足许可证和可复现构建要求。

按 docs/02-development-implementation-plan.md 的 M0 至 M6 顺序小步实施，每个里程碑保持可构建、可验证。先检查当前目录、Git 状态、现有文件和本机 Android/JDK/Go/NDK 工具链，再创建或修改工程。保留用户已有文件，不覆盖参考 APK和文档。

实现时重点保证：
- minSdk 28、target/compileSdk 36，release 只含 arm64-v8a。
- 手工多节点、vless:// 导入导出、Keystore + AES-GCM 本地存储。
- VPN 与本地代理模式严格先停后启，同一时刻只有一个 Xray controller。
- API 34+ VPN 模式使用 systemExempted FGS type，本地代理使用 specialUse；低版本正确兼容。
- 本地监听只绑定 127.0.0.1，禁止 LAN 暴露。
- 用户停止后取消自动恢复并释放 Xray、HEV、TUN、端口、线程、FD 和 wake lock。
- 全局 VPN 阻断 IPv6 泄漏；本地代理不修改系统路由，未显式使用代理的流量保持原网络路径。
- iCAR 专有适配必须先有固件和 logcat 证据；没有证据就只实现标准 Android boot/network 恢复。
- release 不使用 debug key 或 AOSP platform test key；正式签名材料未提供时交付 unsigned release/debug APK，并明确说明，禁止自行生成冒充正式 key 的材料。

测试必须覆盖 validator、URI codec、Xray JSON、路由规划、状态机、重连、加密存储、日志脱敏、两种模式、模式切换、端口释放、VPN 授权和失败清理。运行当前环境可执行的 Gradle/lint/unit/instrumented 检查。真机不可用时明确写“未做 iCAR 真机验证”，不能把模拟器结果代替真机结果。

除非遇到会改变已冻结协议、权限、安全边界、ABI、applicationId/正式签名兼容性的事项，否则自行做可逆实现决定并继续，不要重复访谈需求。若依赖下载或工具链缺失，先给出准确诊断和最小解决方案，再继续能完成的部分。

每个可交付节点报告：完成内容、修改文件、当前 commit、已运行检查及结果、尚未验证的限制和下一步。最终交付源码、构建命令、APK（或明确的未签名产物）、SHA-256、依赖锁、许可证/SBOM、测试结果和设备验证记录。只有满足设计文档验收标准后才能声明完成。
```

## 2. 交给 Agent 的材料

必需：

1. `docs/README.md`：冻结范围、非目标和 ADB 决策。
2. `docs/01-client-technical-design.md`：行为契约、架构、协议、权限、安全和验收。
3. `docs/02-development-implementation-plan.md`：M0-M6 开发顺序、Gate、测试和发布检查。
4. `docs/03-ubuntu-xray-server.md`：配套服务端协议与字段映射。
5. 本提示词 `docs/04-agent-execution-prompt.md`，或直接复制第 1 节内容。

可选：

- `畅流-v7.1-7169-极速版-64位推荐.apk`：仅当 Agent 需要重新验证目标车机兼容行为时提供；文档已覆盖 MVP 所需结论，通常无需再分析。
- iCAR 03 的脱敏设备信息：型号、固件号、Android API、ABI、屏幕分辨率、标准 boot/休眠/网络切换测试日志。
- 一组专门用于测试的 VLESS/REALITY 节点参数；不要提供生产私钥。客户端只需要 UUID、服务器、端口、SNI、REALITY Password 和 Short ID。
- 正式签名通过受控 secret 渠道提供，绝不能粘贴进任务消息、文档或仓库。

## 3. 不应提供给 Agent/仓库的内容

- Ubuntu 服务端 REALITY `PrivateKey`，除非同一个受信 Agent 被明确授权部署服务器；Android 开发不需要它。
- 正式 keystore 文件、alias 密码或 CI secret 的明文。
- 参考 APK 的平台测试私钥或任何来源不明的 `.so`。
- 用户真实账号、VIN、定位、Wi-Fi 密码和车机唯一标识。

