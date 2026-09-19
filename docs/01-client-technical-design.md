# CarTunnel 客户端技术设计

状态：Frozen / Implementation Ready  
适用版本：MVP 1.0  
主目标：iCAR 03，Android 9/11，无 root  
次目标：其他允许侧载 APK、实现标准 `VpnService` 的 arm64 Android 车机

## 1. 设计目标

开发 Agent 必须交付一个单用途 Android VPN 客户端。应用不依赖任何运营后台，节点配置完全由用户掌控；安装后即使开发方服务全部离线，应用仍可使用用户自建的 Xray 服务端。

优先级依次为：

1. 稳定连接和断开，不产生流量泄漏、死循环或车机网络假死。
2. Android 9/11 与 iCAR 03 上可安装、可授权、可开机恢复。
3. 小权限、小界面、小依赖、小 APK。
4. 出错可诊断，敏感配置不进入日志。
5. 代码和依赖可复现、可审计、可由其他 Agent 接手。

需求追踪：

| 用户需求 | 冻结实现 | 验收入口 |
| --- | --- | --- |
| 只保留 VPN/TUN/代理核心 | 单前台 tunnel service + HEV + Xray；无账号、后台、更新器、ADB | 第 13 节第 1/4/10/11 条 |
| 自定义自建节点 | 手工多节点 + `vless://` 导入导出；VLESS/REALITY/Vision | 第 5/6 节与第 13 节第 2/4 条 |
| Ubuntu 主服务 | 配套 Xray `v26.3.27` 通用部署文档 | `03-ubuntu-xray-server.md` |
| iCAR/奇瑞优先并兼容通用车机 | API 28/30、arm64、无 GMS、标准 boot/network 恢复；OEM 扩展须有证据 | 第 8/12 节与第 13 节第 1/8 条 |
| 无 root、手动 VPN 授权 | 只用公开 `VpnService.prepare()`；授权拒绝/撤销均有明确状态 | 第 2/7/13 节 |

## 2. 行为契约

### 2.1 首次使用

1. 首次打开显示空状态和“添加节点”。
2. 用户手工填写配置或导入 `vless://` URI。
3. 保存前执行本地格式校验；不得把配置上传到任何服务器。
4. 用户选择全局 VPN 并点击“启动”时调用 `VpnService.prepare()`；若选择本地代理，则直接启动前台服务并跳过 VPN 授权。
5. 全局 VPN 若收到授权 Intent，打开系统 VPN 确认页；用户拒绝则保持断开并提示“未获得系统 VPN 授权”。
6. 授权成功后启动 VPN；本地代理启动成功后只展示本地 SOCKS/HTTP 地址。

### 2.2 后续使用

- 全局 VPN 已授权时一次点击完成连接；本地代理始终不要求 VPN 授权。
- 点击“停止”必须立即把 `desiredRunning=false` 持久化，随后停止当前模式使用的 TUN/HEV（若有）、Xray 和前台服务；自动重连不得再次拉起。
- Activity 被关闭不影响正在运行的 VPN 或本地代理。
- 服务因系统回收后，只有 `desiredRunning=true` 才可恢复。
- 重启后仅当“开机自动恢复”和 `desiredRunning` 同时为真，才尝试恢复上次模式。
- 网络从 Wi-Fi/蜂窝切换或短暂中断时，保留当前模式的运行意图并自动重建数据链。
- VPN 模式被另一 VPN 抢占或系统调用 `onRevoke()` 时，立即停止并显示明确错误，不死循环抢占。本地代理模式没有 TUN，收到 VPN revoke 事件不得误停健康的本地代理。

### 2.3 VPN 模式路由语义

- 默认全局接管 IPv4：`0.0.0.0/0`。
- `绕过局域网=false`：所有 IPv4 流量进入 VPN。
- `绕过局域网=true`：路由规划器从 `0.0.0.0/0` 中排除 `0.0.0.0/8`、`10.0.0.0/8`、`100.64.0.0/10`、`127.0.0.0/8`、`169.254.0.0/16`、`172.16.0.0/12`、`192.168.0.0/16`、`198.18.0.0/15`、`224.0.0.0/4` 和 `240.0.0.0/4`；其余 IPv4 进入 VPN。这里同时避免 CGNAT/本机/链路本地/TUN 基准网段和保留地址被错误接管。
- 应用自身必须通过 `addDisallowedApplication(applicationId)` 排除出 VPN，保证内嵌 Xray 的上游连接不重新进入 TUN。
- MVP 不调用 `allowFamily(AF_INET6)`，也不添加 IPv6 地址/路由。按 Android `VpnService.Builder` 语义，连接期间 IPv6 应被阻断，不能从物理网络旁路泄漏。
- DNS 由 VPN 接管。首版使用可配置但默认隐藏的两个 IPv4 DNS：`223.5.5.5`、`1.1.1.1`；DNS 报文同样经过 TUN/SOCKS/Xray。
- 不实现分应用代理、国内外分流、规则订阅或 GeoIP/GeoSite。

### 2.4 UI 契约

界面适配横屏和触控，不追求手机端复杂导航。最低可用触控目标 48dp，正文不小于 16sp，连接主按钮不小于 64dp 高。

只需要三个页面/面板：

1. **主页**：当前节点、`全局 VPN / 本地代理` 模式选择、连接状态、一个启动/停止主按钮、进入节点和设置的入口、最近错误摘要。
2. **节点管理**：节点列表、新增、编辑、删除、设为当前、导入、导出。
3. **设置/诊断**：开机自动恢复、绕过局域网、脱敏日志、复制诊断信息、版本与许可证。

不得加入账号、广告、公告、轮播图、远程客服、测速排行或隐藏入口。连接时通知显示当前节点和状态，并提供“断开”动作。

### 2.5 两种运行模式

模式互斥，同一时刻只能运行一个 Xray controller：

| 模式 | 启动组件 | 需要 VPN 授权 | 能否透明接管其他应用 |
| --- | --- | --- | --- |
| `VPN` | Xray + `VpnService` TUN + HEV | 是，首次由系统确认 | 是，接管约定的 IPv4 路由 |
| `LOCAL_PROXY` | Xray SOCKS5 + HTTP inbound | 否 | 否，调用方必须显式使用代理 |

本地代理固定只监听 loopback：

- SOCKS5：`127.0.0.1:10808`，TCP/UDP。
- HTTP proxy：`127.0.0.1:10809`，主要承载 TCP/HTTP CONNECT。
- 禁止绑定 `0.0.0.0`、Wi-Fi 地址或车载以太网地址，首版不提供局域网共享代理。
- 应用只负责展示端口和复制地址，不尝试修改系统 Wi-Fi proxy、其他应用配置或系统路由。
- Android 应用是否遵循系统 HTTP 代理由各应用决定；QUIC、私有网络栈和忽略 proxy 的应用不会自动进入本地代理。
- 若目标应用不能手工指定 SOCKS/HTTP proxy，此模式对它无效，用户应改用全局 VPN。

从运行中的一个模式切到另一个模式，必须完整停止旧模式，再启动新模式。模式值随 `desiredRunning` 一起持久化，开机恢复上次运行模式。

## 3. 技术栈与构建基线

### 3.1 Android 基线

- Kotlin，XML/View UI；不用 Compose。
- `minSdk=28`，`compileSdk=36`，`targetSdk=36`。
- 单 Activity、单应用进程、一个继承 `VpnService` 的 tunnel 前台服务；该服务按命令运行 VPN 或 core-only 本地代理模式。
- 仅 `arm64-v8a`，release 开启 R8、资源压缩和 native symbols 分离。
- 不依赖 Google Play Services、Hilt、Room、WorkManager、WebView 或动态特性模块。
- 可使用 AndroidX Core/AppCompat、Lifecycle 和 `kotlinx-coroutines-android`；新增依赖必须说明必要性。
- JSON 使用平台 `org.json` 或一个已在依赖树中的轻量实现，禁止为了几个配置对象引入大型序列化框架。

`targetSdk` 不允许为了规避后台限制而降到 28。Android 14+ 的 VPN 前台服务按官方规则声明 `systemExempted` 与 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`；本地代理声明 `specialUse`、对应 permission 和 service property；在 API 28/30 上这些新类型声明被安全忽略。

### 3.2 原生核心

- Xray：从 [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) 或等价的自有 gomobile 封装构建，只保留 arm64。不得直接抽取参考 APK 中的 `.so`。
- TUN 转发：从 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 官方源码构建 JNI，首选已修复 UDP 问题的 `2.17.1` 或实施时确认的后续稳定版。
- Xray Android 核心与 Ubuntu 服务端尽量固定为同一发行代；若不能完全一致，必须有 VLESS/REALITY/RAW/Vision 互通测试。
- 所有上游使用 tag 加完整 commit SHA 锁定，记录源码 URL、许可证、构建命令和产物 SHA-256。
- 禁止运行时下载核心、配置或代码。

基准构建日期为 2026-09-13。此时服务端建议基线为 Xray `v26.3.27`，REALITY 客户端字段使用当前名 `password`；导入旧配置时兼容旧名 `publicKey`/URI 参数 `pbk`，内部统一转换为 `password`。

### 3.3 体积与资源预算

- release APK 目标不超过 20 MiB；超过时先输出依赖/ABI 体积分析，再决定是否接受。
- 稳态 Java/Kotlin 堆目标低于 80 MiB；不得定时刷新大对象或持续轮询 UI。
- 空闲连接不应持有高频 wake lock。只允许在启动/重连关键区间使用最多 30 秒的 partial wake lock，且必须 `try/finally` 释放。

## 4. 组件架构

建议目录如下；名称可微调，但边界不可混淆：

```text
app/src/main/java/com/cartunnel/client/
  App.kt
  ui/
    MainActivity.kt
    HomeFragment.kt
    ProfilesFragment.kt
    ProfileEditorFragment.kt
    SettingsFragment.kt
  profile/
    VlessRealityProfile.kt
    ProfileValidator.kt
    VlessUriCodec.kt
    SecureProfileStore.kt
  vpn/
    CarTunnelService.kt
    TunnelMode.kt
    TunnelCommand.kt
    TunnelRuntimeState.kt
    TunnelStateStore.kt
    VpnInterfaceFactory.kt
    Ipv4RoutePlanner.kt
    ConnectivityMonitor.kt
    ReconnectPolicy.kt
  core/
    ProxyCore.kt
    XrayProxyCore.kt
    XrayConfigFactory.kt
    TunForwarder.kt
    HevTunForwarder.kt
  boot/
    BootReceiver.kt
  diag/
    RedactingLog.kt
    DiagnosticReport.kt
```

核心依赖方向：

```text
UI -> profile store + TunnelStateStore -> CarTunnelService
CarTunnelService -> XrayProxyCore + VpnInterfaceFactory + HevTunForwarder
VpnInterfaceFactory -> Android VpnService.Builder
HevTunForwarder -> TUN file descriptor + local SOCKS endpoint
XrayProxyCore -> generated Xray JSON + user server
```

不得让 UI 直接操作 JNI；不得让 JNI 持久化业务状态；不得把 Activity Context 保存在单例中。

## 5. 节点模型与校验

### 5.1 内部模型

配置 schema 从 1 开始，显式版本化：

```json
{
  "schemaVersion": 1,
  "id": "local-profile-id",
  "name": "My VPS",
  "server": "203.0.113.10",
  "port": 443,
  "uuid": "00000000-0000-4000-8000-000000000000",
  "serverName": "target.example.com",
  "realityPassword": "x25519-public-material-held-by-client",
  "shortId": "0123456789abcdef",
  "fingerprint": "chrome",
  "spiderX": "/",
  "flow": "xtls-rprx-vision",
  "transport": "raw"
}
```

字段约束：

| 字段 | 约束 |
| --- | --- |
| `name` | 去首尾空格后 1..40 字符 |
| `server` | DNS 名、IPv4 或 IPv6 literal；不得包含 scheme/path |
| `port` | 1..65535，默认 443 |
| `uuid` | RFC 4122 形式；保存为小写 canonical 字符串 |
| `serverName` | 必填，默认按 DNS hostname 校验 |
| `realityPassword` | 必填；日志和错误消息中一律脱敏；兼容导入名 `publicKey`/`pbk` |
| `shortId` | 2..16 个十六进制字符且字符数为偶数；Xray 可接受空值，但 MVP 明确要求非空 |
| `fingerprint` | MVP 保存值固定为 `chrome`，导入其他值时明确报不支持 |
| `spiderX` | 默认 `/`，必须以 `/` 开头，最长 256 字符 |
| `flow` | 固定 `xtls-rprx-vision` |
| `transport` | 内部固定 `raw`；导入 `tcp` 时归一化为 `raw` |

保存时校验，不对服务器做网络探测。连接失败由运行时诊断处理。

### 5.2 URI 导入导出

接受常见 URI：

```text
vless://UUID@HOST:PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=SNI&fp=chrome&pbk=PASSWORD&sid=SHORT_ID&type=tcp&spx=%2F#NAME
```

要求：

- 严格限制 scheme 为 `vless`、security 为 `reality`、flow 为 `xtls-rprx-vision`。
- `type=tcp` 和 `type=raw` 都接受，内部统一为 `raw`。
- 正确处理 IPv6 方括号、URL 编码和重复参数；重复的安全关键参数视为错误，不采用“最后一个获胜”。
- URI 解析失败只返回字段级错误，不记录原 URI。
- 导出会暴露 UUID、REALITY password 和 shortId，导出确认框必须写明“链接等同连接凭据”。
- 文件导入/导出使用系统 Storage Access Framework，不申请广泛存储权限。

### 5.3 本地加密存储

实现 `SecureProfileStore`：

- Android Keystore 生成不可导出的 AES-256-GCM 密钥，别名 `cartunnel.profile.v1`。
- 节点列表序列化为一个版本化 JSON blob，每次写入随机 12-byte IV；密文、IV、schema 进入 `MODE_PRIVATE` SharedPreferences。
- 密钥不要求生物识别或锁屏认证，以支持车机无人值守重连；API 28+ 可设置仅设备解锁后可用。
- `android:allowBackup="false"`，并提供 data extraction rules 禁止云备份/设备迁移备份。
- 当前节点 ID、开机连接开关、LAN 绕过开关、`desiredRunning` 可明文保存在私有 preferences；节点凭据必须加密。
- Keystore 失效时进入 `CONFIG_UNREADABLE`，不要静默清空密文；提示用户重新导入或重建节点。
- 任何日志、崩溃报告和剪贴板默认不得包含 UUID、完整服务器、REALITY password 或 shortId。应用不接第三方崩溃 SDK。

## 6. Xray 配置生成

Xray 只监听 loopback SOCKS5，不暴露到局域网。以下是语义模板，实际键名必须由固定版本的 Xray `run -test` 或 Android core dry-run 验证：

```json
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "socks-in",
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true }
    },
    {
      "tag": "http-in",
      "listen": "127.0.0.1",
      "port": 10809,
      "protocol": "http",
      "settings": {}
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "PROFILE_SERVER",
            "port": 443,
            "users": [
              {
                "id": "PROFILE_UUID",
                "encryption": "none",
                "flow": "xtls-rprx-vision"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "serverName": "PROFILE_SNI",
          "fingerprint": "chrome",
          "password": "PROFILE_REALITY_PASSWORD",
          "shortId": "PROFILE_SHORT_ID",
          "spiderX": "/"
        }
      }
    }
  ]
}
```

`http-in` 只在 `LOCAL_PROXY` 模式生成；VPN 模式只生成 `socks-in`，减少无用监听面。

约束：

- 使用结构化 JSON builder，不允许字符串拼接或模板替换。
- 本地 SOCKS 端口常量为 10808，HTTP proxy 端口常量为 10809；启动前探测所需端口，发现被占用则报错，不动态换端口。
- 除 `LOCAL_PROXY` 所需的 loopback HTTP inbound 外，不开启 Xray API、metrics、额外 inbound、远程 DNS 管理、Mux、规则订阅或远程日志。
- 允许 release 默认 `warning`、debug 默认 `info`，但传给 UI 的日志必须二次脱敏。
- Android 进程内同一时间只允许一个 core controller。

## 7. VPN 数据链与生命周期

### 7.1 状态机

`TunnelRuntimeState` 为 sealed class，至少包含：

```text
Stopped
PermissionRequired
Starting(mode, step)
Connected(mode, profileId, since, latencyMs)
Reconnecting(mode, attempt, reason)
Stopping
Error(code, safeMessage, recoverable)
```

所有 start/stop/restart command 进入单线程 command queue。用递增 generation ID 丢弃旧异步回调，避免快速点按造成“旧启动覆盖新断开”。

必须满足：

- `start + start` 幂等。
- `stop + stop` 幂等。
- `start(A)` 期间切换到 `B` 等价于完整停止 A 后启动 B。
- `stop` 优先于排队中的自动 reconnect。
- 任一启动步骤失败都执行同一个逆序清理函数。

### 7.2 启动顺序

1. 校验 mode；`VPN` 模式检查 `VpnService.prepare()` 已返回 `null`，否则不启动 service 工作链；`LOCAL_PROXY` 明确跳过该检查。
2. `startForegroundService()` 后，`CarTunnelService` 在系统时限内立即创建通知并 `startForeground()`。API 34+ 的 VPN 模式先请求 `systemExempted` type；若 OEM 在 VPN 成为 active 前拒绝该类型，降级为已声明具体用途的 `specialUse`。本地代理模式直接使用 `specialUse`；两者均不得让前台服务策略异常逃逸并终止进程。
3. 读取并校验当前节点，生成 Xray JSON。
4. 初始化并启动 Xray core。
5. 最多等待 5 秒确认 `127.0.0.1:10808` TCP accept；本地代理模式还必须确认 `127.0.0.1:10809`；失败则清理并报 `CORE_NOT_READY`。
6. 通过当前 Xray outbound 请求 HTTPS `generate_204`；只有实际出口成功才记录 `latencyMs`，失败返回 `NODE_UNREACHABLE`。
7. 若模式为 `LOCAL_PROXY`，进入 `Connected`，不得调用 `VpnService.prepare()`、`Builder.establish()` 或 HEV。
8. 若模式为 `VPN`，构造 `VpnService.Builder`：session、MTU、IPv4 地址、DNS、路由、应用自身排除。
9. 调用 `establish()`；返回 `null` 视为失败。持有唯一 `ParcelFileDescriptor`。
10. 将 Java 持有的 `ParcelFileDescriptor.fd` 借给 HEV；HEV 退出后由 Java 关闭 PFD，避免 detached FD 泄漏或双重关闭。
11. HEV 返回 running 后进入 `Connected`，界面同时显示该次实测延迟。

推荐参数：

```text
session = CarTunnel
TUN IPv4 = 198.18.0.1/30
MTU = 1500（真机验证后才能调整）
SOCKS = 127.0.0.1:10808
SOCKS UDP relay = udp
HEV task stack = 24576
HEV TCP buffer = 4096（仅在压测无吞吐回退后采用）
```

不要直接照搬参考 APK 的 MTU。1500 是保守首发值；若蜂窝网络出现分片/握手问题，可在真机矩阵中比较 1400，但最终只保留一个默认值。

### 7.3 停止顺序

统一清理流程必须可重复调用：

1. 取消重连定时器和 connectivity generation。
2. 若旧模式为 VPN，停止 HEV 并等待其工作线程退出，最长 3 秒。
3. 若存在 TUN，关闭仍由 Java 持有的 TUN PFD/FD。
4. 停止 Xray core 并释放 controller。
5. 释放 wake lock、network callback 和 service scope。
6. 更新 `Stopped`/`Error`，移除前台通知并 `stopSelf()`。

超时不得阻塞主线程；native 停止超时要记录错误码。不能粗暴 `killProcess()`。

### 7.4 `VpnService.Builder` 要点

```kotlin
Builder()
    .setSession("CarTunnel")
    .setMtu(1500)
    .addAddress("198.18.0.1", 30)
    .addDnsServer("223.5.5.5")
    .addDnsServer("1.1.1.1")
    .addDisallowedApplication(applicationContext.packageName)
```

- 普通模式再添加 `0.0.0.0/0`。
- LAN 绕过模式用 `Ipv4RoutePlanner` 计算排除私网后的最小公网 CIDR 集；不得靠漏加默认路由实现。
- 为路由规划器写穷举边界与抽样覆盖测试：所有允许地址恰好一次覆盖，所有排除地址零覆盖，CIDR 不重叠。
- 不允许 `allowBypass()`，否则应用可自行绕过 VPN。

## 8. 自动恢复与车机兼容

### 8.1 通用 Android 路径

- `BootReceiver` 只监听 `BOOT_COMPLETED` 和 `MY_PACKAGE_REPLACED`。
- `BOOT_COMPLETED` 时检查 `autoConnect && desiredRunning && currentProfileExists`，随后启动前台 VPN service。
- 不监听 `LOCKED_BOOT_COMPLETED`，因为节点凭据位于 credential-encrypted storage。
- 服务返回 `START_STICKY`；重建时仍必须检查 `desiredRunning`。
- `ConnectivityMonitor` 使用 `ConnectivityManager.NetworkCallback`，忽略 `TRANSPORT_VPN`，只追踪可用的底层网络。
- 网络丢失不立即销毁 VPN；进入 `Reconnecting`。新底层网络稳定 2 秒后重建 Xray/TUN。
- 重试退避为 1、2、5、10、30 秒，之后每 30 秒一次；用户断开、授权被撤销或配置错误立即停止重试。
- 每次屏幕唤醒不强制重启健康连接。可用 `SCREEN_ON` 作为一次健康检查触发，但 receiver 仅在 service 存活时动态注册。

### 8.2 iCAR/奇瑞策略

未获得 iCAR 03 官方公开的应用保活广播或白名单接口前，不得猜测或硬编码车厂 action、组件名、包名和权限。

真机适配按以下顺序做：

1. 先验证标准 `BOOT_COMPLETED`、`START_STICKY`、前台通知和 `NetworkCallback`。
2. 记录冷启动、熄屏/唤醒、锁车休眠、切 Wi-Fi/蜂窝时的时间线和脱敏 logcat。
3. 如果标准机制确实失效，再形成带固件版本、action 来源和复现证据的 OEM ADR。
4. OEM 逻辑必须位于独立 product flavor/source set；通用版不包含。

可以在用户说明中引导用户手工打开车机已有的“允许自启动/后台运行/电池不限制”设置；应用不通过 ADB、私有 intent 或隐藏 API 修改这些设置。

## 9. Manifest 与权限

仅允许以下权限；新增项必须写 ADR：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

服务声明语义：

```xml
<service
    android:name=".vpn.CarTunnelService"
    android:exported="true"
    android:foregroundServiceType="systemExempted|specialUse"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:stopWithTask="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-initiated local loopback proxy while VPN mode is disabled" />
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

说明：

- `POST_NOTIFICATIONS` 仅在 API 33+ 运行时请求；拒绝不应导致 API 28/30 的 VPN 启动失败。
- API 34+ 只能把实际运行模式对应的 type 传给 `startForeground()`：VPN 用 `systemExempted`，本地代理用 `specialUse`，不能无条件同时请求两个 type。
- `BIND_VPN_SERVICE` 是系统 signature 权限，阻止第三方任意绑定；该 service 为系统发现 VPN 而 exported。
- Boot receiver 可 exported，但仅接受 manifest 中列出的系统广播；收到后仍验证 action 和本地状态。
- 所有 Activity 默认 `exported=false`，launcher Activity 例外。
- 禁止 `QUERY_ALL_PACKAGES`、通知监听、悬浮窗、安装包、无障碍、读取设备标识、位置、蓝牙、短信、电话、麦克风和存储权限。
- 所有自定义 PendingIntent 显式指向本包组件并设置正确的 immutable/mutable flag。

## 10. 错误、日志和诊断

定义稳定错误码，不把底层英文堆栈直接显示给用户：

| 错误码 | 含义 | 自动重试 |
| --- | --- | --- |
| `VPN_PERMISSION_REQUIRED` | 未授权或授权被撤销 | 否 |
| `PROFILE_INVALID` | 配置字段错误 | 否 |
| `CONFIG_UNREADABLE` | Keystore/密文不可读 | 否 |
| `CORE_INIT_FAILED` | Xray 初始化失败 | 否，除非底层网络原因明确 |
| `CORE_NOT_READY` | SOCKS 端口未就绪 | 是，最多按退避 |
| `NODE_UNREACHABLE` | Xray 已启动但无法通过节点完成 HTTPS 出口请求 | 是，最多按退避 |
| `LOCAL_PORT_IN_USE` | 10808 或本地代理所需的 10809 已占用 | 否 |
| `TUN_ESTABLISH_FAILED` | 系统未创建 TUN | 是一次，持续失败停止 |
| `TUN_FORWARDER_FAILED` | HEV 启动或运行失败 | 是 |
| `UPSTREAM_UNREACHABLE` | 服务端不可达/握手失败 | 是 |
| `VPN_REVOKED` | 被系统或另一 VPN 撤销 | 否 |

`RedactingLog` 是唯一业务日志入口：

- 内存环形缓冲最多 200 条或 128 KiB，先到者为准。
- release 不写持久日志文件；用户主动“导出诊断”时生成临时文本并通过 SAF 分享。
- 默认只记录时间、状态迁移、稳定错误码、Android/API/ABI、应用版本、核心版本、网络类型和重试次数。
- host 只保留哈希前 8 位；UUID、password、shortId、完整 URI、Xray JSON、TUN FD 一律不记录。
- 诊断页提供清空日志。

## 11. 安全与供应链约束

- release 必须使用项目自有正式签名；禁止 AOSP platform test key、debug key 或参考 APK 证书。
- keystore 路径、alias、密码只来自 CI secret/local untracked properties，不进入 Git、APK resources 或日志。
- release 构建禁止 cleartext traffic：`android:usesCleartextTraffic="false"`。REALITY 由 Xray 建立，不需要 Android 明文 HTTP。
- 不实现任何远程控制面；应用产生的唯一远程业务连接是用户配置的 Xray endpoint，以及该连接承载的用户流量。
- 依赖锁定并生成 SBOM；至少跑一次依赖漏洞扫描和 native 符号/许可证盘点。
- LGPL 依赖（例如 AndroidLibXrayLite 的具体发行许可）必须由交付方核对动态/静态链接义务，随包提供许可证、对应源码/修改和重新链接所需材料；不能只在文档中写名字。
- 禁止从反编译样本复制实现。可以复用的仅是公开 Android API 行为和独立观察到的架构事实。

## 12. 测试设计

### 12.1 JVM 单元测试

至少覆盖：

- `ProfileValidatorTest`：每个字段的正常值、边界、Unicode、空值、恶意超长值。
- `VlessUriCodecTest`：round-trip、IPv6、URL 编码、重复参数、旧 `pbk`、`tcp -> raw`。
- `XrayConfigFactoryTest`：结构化 JSON snapshot、特殊字符不注入、敏感字段只出现在必要位置。
- `XrayConfigFactoryTest` 还要证明 VPN 模式没有 HTTP inbound，而本地代理模式同时且仅监听 loopback 的 10808/10809。
- `Ipv4RoutePlannerTest`：私网排除、边界 IP、公网覆盖、CIDR 无重叠。
- `ReconnectPolicyTest`：退避、reset、用户停止取消。
- `TunnelStateReducerTest`：快速 start/stop/profile switch 与旧 generation callback。
- `SecureProfileStoreTest`：加解密、篡改检测、schema 不支持、密钥失效行为。
- `RedactingLogTest`：UUID、URI、server、password、shortId 均不可泄漏。

### 12.2 Android 仪器测试

- Manifest 权限快照，若出现白名单外权限直接失败。
- `VpnService.prepare()` 授权前后路径。
- service 前台化、notification stop action。
- 本地代理启动不调用 VPN 授权/TUN/HEV；模式切换严格先停后启。
- Activity 旋转/重建不重复启动核心。
- 模拟 core/HEV adapter 的启动失败和停止超时，确认清理顺序。
- preferences/Keystore 在进程重启后的恢复。

### 12.3 真机矩阵

必须记录设备型号、固件号、Android API、ABI、测试网络和时间：

| 场景 | iCAR 03 Android 9 | iCAR 03 Android 11 | AOSP API 28/30 | API 34+ 冒烟 |
| --- | --- | --- | --- | --- |
| 侧载、首次启动 | 必测 | 可获得则必测 | 必测 | 必测 |
| VPN 首次授权/拒绝/再次授权 | 必测 | 必测 | 必测 | 必测 |
| TCP 网页/HTTPS/大文件 | VPN/本地代理均必测 | VPN/本地代理均必测 | 两模式必测 | 冒烟 |
| UDP DNS/音视频 | 必测 | 必测 | 必测 | 冒烟 |
| UI 退出后维持 | 必测 | 必测 | 必测 | 冒烟 |
| Wi-Fi/蜂窝切换 | 必测 | 必测 | 必测 | 冒烟 |
| 休眠/唤醒 | 必测 | 必测 | 不适用 | 不适用 |
| 重启自动恢复 | 必测 | 必测 | 必测 | 冒烟 |
| 另一 VPN 抢占 | 必测 | 必测 | 必测 | 冒烟 |
| 8 小时稳定性 | 必测一台 | 建议 | 建议 | 不要求 |

## 13. MVP 验收标准

只有同时满足以下条件才可宣布完成：

1. iCAR 03 arm64 设备可侧载，应用不要求 root、ADB、Google 服务或车厂签名。
2. 用户可手工创建至少 3 个节点并切换；导入/导出可 round-trip。
3. 第一次连接只出现标准系统 VPN 授权；同一安装后续连接无需重复授权。
4. 使用配套 Ubuntu 节点时，HTTPS、DNS 和常见 UDP 应用可用；应用自身无 VPN 路由死循环。
5. 本地代理模式不出现 VPN 授权、不创建 TUN/HEV，10808 SOCKS5 与 10809 HTTP proxy 可被支持代理的测试应用使用；停止后两个端口均释放。
6. 用户停止后 3 秒内释放 TUN（若有）、10808/10809 和前台通知，且不再自动启动。
7. 网络切换后在底层网络恢复起 15 秒内重新可用，或给出稳定错误码。
8. 开机自动恢复在目标车机允许自启动的前提下，于用户存储可用后 30 秒内完成上次运行模式。
9. IPv6 泄漏测试表明 VPN 连接期间 IPv6 被阻断；IPv4 出口为 VPS。
10. release manifest 不含第 9 节白名单外权限，APK 内无参考 APK 域名、测试证书、ADB 或更新器字符串。
11. 单元测试、仪器测试和目标真机矩阵达到本节约定；8 小时运行无崩溃、无持续高 CPU、无明显 FD/线程增长。
12. release APK 可复现构建、正式签名、附 SHA-256、SBOM 和许可证材料，体积目标已检查。

## 14. 已知限制与后续候选

以下不是首版缺陷，除非用户重新扩展范围：

- 不支持 IPv6、按应用代理、多协议、多服务器负载均衡或复杂路由。
- 不承诺绕过所有车厂的后台冻结；只能使用标准 Android 能力和用户可见设置。
- 不提供静默 VPN 授权。应用重装、清数据或另一 VPN 取代后可能需要再次手动授权。
- 不提供应用内更新。升级由受控侧载/MDM/人工安装完成。
- `armeabi-v7a`、OEM 唤醒广播、ADB 实验工具均需独立证据和独立决策。
