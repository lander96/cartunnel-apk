# iCAR VPN 重构实施交接

面向对象：接手 Android 实现的 Agent

前置文档：07-icar-vpn-refactor-plan.md

实施基线：main，包含本次 07/08 文档提交的当前 HEAD

状态：计划待用户确认；确认后从 M1 开始

## 1. 任务目标

在不更换 Xray/HEV 锁定版本、不降低 targetSdk 的前提下，修复 iCAR 对当前 VPN 启动的兼容问题；删除用户侧本地代理模式；把节点列表与持久测速结果放到首页；增加开机、重试、网络变化和唤醒恢复设置。

实现必须建立在现有 TunnelSessionCoordinator 串行状态机上，不能把资源生命周期重新塞回 Activity、BroadcastReceiver 或 ConnectivityManager callback。

## 2. 受保护的不变量

- 主仓库及两个 submodule 的锁定版本不变，除非出现可复现的 native 缺陷并单独报告。
- TUN FD 所有权仍由 ParcelFileDescriptor 持有，不调用 detachFd。
- 停止顺序仍为 HEV → PFD → Xray。
- 所有 Start、Stop、NetworkChanged、VpnRevoked 仍由单个 coordinator 串行处理。
- epoch、startId token 与 stopSelfResult 防止旧请求终止新会话的规则不得弱化。
- 配置凭据仍使用 Android Keystore AES-GCM；健康记录不得包含凭据。
- 不复制参考 APK 代码、资源、证书、域名、服务端信息或 native 库。

## 3. 文件级实施清单

### 3.1 VPN 数据面

AndroidTunnelRuntime.kt：

- 将 start 签名收敛为纯 VPN，移除 mode 分支。
- 启动 Xray 后只等待 SOCKS_PORT readiness。
- 删除同步 measureDelay 及 NODE_UNREACHABLE 启动失败分支。
- TUN/HEV 成功后返回 Started，不携带强制测得的 latency。
- writeHevConfig 使用 10.10.0.2，并补 TCP/UDP 读写超时。
- 对 CORE_READY、TUN_READY、HEV_READY 写脱敏阶段日志。

VpnInterfaceFactory.kt：

- 把 Builder 调用封装为可单测的 VpnInterfacePlan，Android adapter 只负责应用 plan。
- 地址改为 10.10.0.2/32。
- DNS 改为 223.5.5.5、119.29.29.29。
- 显式 allowFamily(AF_INET)。
- 保留 addDisallowedApplication(packageName) 与 Ipv4RoutePlanner。

XrayConfigFactory.kt：

- 删除 HTTP_PORT 与 HTTP inbound。
- 删除 mode 参数。
- SOCKS 只监听 127.0.0.1:10808，继续启用 UDP。
- 不为兼容性随意加入参考 APK 的平台配置。

ProxyCore.kt / ProfileLatencyProbe.kt：

- 删除 Cloudflare 常量。
- 健康检查端点抽成可注入列表，默认百度、腾讯。
- 连接状态与测速状态解耦。

### 3.2 状态机与设置迁移

TunnelModel.kt：

- 删除 TunnelMode。
- Start 只包含 profileId 与 requestToken。
- Starting、Connected、Reconnecting 删除 mode。
- TunnelSettings 删除 lastMode；新增：
  - autoReconnect=true
  - reconnectOnNetworkChange=true
  - reconnectOnWake=true
- 保留 autoConnect=false、bypassLan=false。

TunnelStateStore.kt：

- 增加显式 schemaVersion。
- 第一次读取旧 mode=LOCAL_PROXY 时清除 desiredRunning，避免升级后静默变成全局 VPN。
- 清除 mode 键，迁移幂等。
- 设置写入建议使用 commit 返回值或集中错误处理，避免重要运行意图静默丢失。

TunnelSessionCoordinator.kt：

- 去掉所有 mode 参数和 currentMode。
- recoverable 启动失败只有 autoReconnect=true 才创建 retry loop。
- NetworkChanged 只有 reconnectOnNetworkChange=true 才重建。
- 增加 WakeResume 命令或等价的串行事件；只有 reconnectOnWake=true 且 desiredRunning=true 才处理。
- 用户 Stop 永远把 desiredRunning=false 并取消 operation/retry/health jobs。
- 网络提示为空时 reason 不得直接写成“网络不可用”；使用“底层网络变化”或保持会话。

CarTunnelService.kt：

- 移除模式 extra 和本地代理相关通知。
- 保持 startId token、stopSelfResult 与立即前台化。
- coordinator Connected 后启动当前节点的异步健康刷新；不得并发启动第二个 Xray Controller。
- onRevoke 保持终止性处理。

### 3.3 网络监听与车机恢复

ConnectivityMonitor.kt：

- 使用 registerNetworkCallback + NetworkRequest，筛选 NET_CAPABILITY_INTERNET、NET_CAPABILITY_NOT_VPN。
- 维护非 VPN 网络集合，不依赖 activeNetwork 单点快照。
- 首次观察只建 baseline。
- onLost 使用宽限与集合复核；不因空集合自动终止当前隧道。
- 继续 2 秒 debounce，并把事件提交到 coordinator。

UnderlyingNetworkTracker.kt：

- 输入从可空单 ID 升级为稳定集合摘要或明确事件。
- 覆盖离线 baseline、重复 capabilities、VPN 网络忽略、Wi-Fi/蜂窝切换、先 lost 后 available 的乱序。

BootReceiver.kt：

- 从设置决定是否恢复，不接受 Intent 携带的 profile 或凭据。
- 支持 BOOT_COMPLETED、MY_PACKAGE_REPLACED、USER_UNLOCKED、QUICKBOOT_POWERON、autochips.intent.action.QB_POWERON。
- BOOT_COMPLETED、QUICKBOOT_POWERON 与 AutoChips 快速上电按 autoConnect 决策；开关开启且节点有效时先写 desiredRunning=true 再启动。
- MY_PACKAGE_REPLACED 只在 desiredRunning=true 时恢复；USER_UNLOCKED 只继续此前因凭据区未解锁而延迟的启动。
- 引入持久冷却时间，抑制同一次车机唤醒的重复广播。

新增 WakeReceiver 或 service 内动态 receiver：

- 仅在 reconnectOnWake=true 且 desiredRunning=true 时提交 WakeResume。
- 自定义厂商 action 不得绕过状态校验。
- 如使用 WakeLock，最长 30 秒，finally 强制释放。

AndroidManifest.xml：

- VPN Service exported=false。
- 更新 foreground service property 文案，删除“本地代理”描述。
- 只增加上述必要广播 action。
- 仅在代码确实使用短时 WakeLock 时增加 WAKE_LOCK。
- 不增加 QUERY_ALL_PACKAGES、SYSTEM_ALERT_WINDOW、REQUEST_INSTALL_PACKAGES、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 或旧存储权限。

### 3.4 节点健康仓库

新增 profile/ProfileHealthStore.kt：

- SharedPreferences 私有存储即可，不与凭据文件混放。
- key 使用 profileId。
- 字段：status、latencyMs、checkedAt、safeErrorCode、profileRevision。
- status：UNTESTED、TESTING（只存内存）、AVAILABLE、FAILED、STALE。
- profileRevision 使用不含明文凭据的稳定摘要；配置变化时旧结果返回 STALE。
- 删除节点同步删除记录。

ProfileLatencyProbe.kt：

- 提供 fake 接口，JVM 测试不得访问公网。
- 按顺序尝试国内端点，第一次成功即返回。
- 错误只映射为安全码，如 TIMEOUT、CORE_START_FAILED、ENDPOINT_UNREACHABLE。

运行中不要对非当前节点测速，因为 AndroidLibXrayLite 的 Controller 生命周期不应并行。当前会话的延迟如果 native API 可安全读取，可在同一 controller 上异步刷新；否则显示“已连接”，不伪造延迟。

### 3.5 首页与设置

activity_main.xml：

- 删除 mode RadioGroup 和本地代理说明。
- 增加状态卡、连接按钮、节点列表容器、添加/导入操作。
- 1280×720 横屏首屏应看到状态、连接按钮和至少 3 个节点；手机竖屏可滚动。

MainActivity.kt：

- 不再使用节点管理多级 AlertDialog 作为主入口。
- 直接观察 profiles、health 与 tunnel state，渲染节点列表。
- 点击行选择，单独测试按钮测速，更多菜单编辑/删除/导出。
- 运行中禁止会改变当前配置的操作。
- VPN 启动只请求系统授权，然后发送 Start(profileId)。

建议新增 ProfileListAdapter.kt 和 item_profile.xml。可以引入 AndroidX RecyclerView；新增依赖必须进入 lockfile、verification metadata、component inventory 和许可证材料。

新增 SettingsActivity.kt / activity_settings.xml：

- Switch：开机自动连接、连接失败自动重试、网络切换后重连、唤醒后恢复、绕过局域网。
- 显示固定退避策略 1/2/5/10/30 秒，首轮不提供任意数字输入。
- 保留版本、Xray/HEV 版本、许可证和脱敏诊断入口。
- 修改影响运行的数据面设置时要求先停止 VPN。

strings.xml：

- 消除“本地代理”“10809”以及把网络提示误写为确定断网的文案。
- 节点测试失败使用“当前网络下测试失败”，避免等同于节点永久不可用。

## 4. 测试清单

### 4.1 JVM 回归

必须保留现有 coordinator、stop token、epoch、取消和清理顺序测试，并新增：

1. connectivity endpoint failure does not fail tunnel startup。
2. socks readiness timeout still fails startup。
3. active network unavailable does not stop healthy session。
4. duplicate and reordered network callbacks create at most one rebuild。
5. reconnect disabled prevents retry loop。
6. network-change reconnect disabled ignores network event。
7. wake reconnect disabled ignores wake event。
8. legacy local proxy migration clears desired running。
9. legacy VPN migration retains safe settings。
10. TUN plan exactly matches address, prefix, DNS, route, self exclusion and IPv4 family。
11. profile health persists across repository recreation。
12. profile edit returns stale health；delete removes health。
13. stop during startup cancels operation without Error/Retry。

### 4.2 Android instrumentation

- 首页节点选择、滚动、编辑入口和测试状态渲染。
- VPN permission denied / granted。
- SAF 文件导入取消、无文件选择器 fallback、导出取消。
- 设置重建 Activity 后仍保持。
- manifest service 非导出验证。

报告必须给出实际运行设备或模拟器、API 和测试数量；assembleDebugAndroidTest 只能算编译 Gate。

### 4.3 人工设备矩阵

普通 Android：

- 新装、升级旧 APK、导入现有服务端节点。
- 20 次连接/断开。
- Wi-Fi ↔ 蜂窝切换、断网 30 秒再恢复。
- 屏幕关闭 10 分钟后恢复。
- 重启后分别验证 autoConnect 开/关。

iCAR 03：

- 记录 Android 版本、ABI、联网方式、是否允许后台启动。
- 连接前采集 dumpsys connectivity 摘要；不得把 activeNetwork 为空直接当失败。
- 验证 TUN 建立、VPN 图标、IPv4 DNS/HTTP/HTTPS/UDP。
- 熄屏/唤醒、车辆休眠恢复、网络切换、快速上电。
- 20 次连接/断开后检查无崩溃、无残留前台通知和 VPN 图标。
- 若失败，采集应用 PID 相关 logcat、dumpsys vpn/connectivity，不先降 targetSdk。

## 5. 构建与交付

按项目内工具链执行：

- ./gradlew testDebugUnitTest
- ./gradlew lintDebug
- ./gradlew connectedDebugAndroidTest
- ./gradlew assembleDebug assembleRelease

同步：

- app/gradle.lockfile
- gradle/verification-metadata.xml
- build/component-inventory.json
- licenses/THIRD_PARTY_NOTICES.md 和新增许可证正文

输出：

- 可侧载 debug APK、SHA-256、大小、签名方案、min/target SDK、ABI、权限审计。
- unsigned release 明确标注不可安装；没有正式签名材料不得声称 release 已交付。
- docs/verification 下保存本轮构建和设备验收记录。

## 6. 提交策略

建议拆成四个可独立回退的提交：

1. fix: remove network gate and align icar vpn plan
2. refactor: remove local proxy mode
3. feat: show persistent profile health on home
4. feat: add reconnect and vehicle resume settings

每个提交运行对应局部测试；最终提交后运行完整 Gate。不得把 APK、toolchains、cache、正式签名材料或 native 构建产物纳入 Git。

## 7. 给实施 Agent 的任务提示词

你接手 CarTunnel Android 项目，请严格执行 docs/07-icar-vpn-refactor-plan.md 和 docs/08-icar-vpn-implementation-handoff.md。从包含这两份文档的 main 当前 HEAD 开始；先确认工作区与两个 submodule 干净。参考 APK 只允许做行为对比，禁止复制代码、资源、凭据和二进制。

按 M1 到 M5 实施。优先修复启动前 Cloudflare 门禁、车机 default-network 误判和 TUN/HEV 参数；随后删除 LOCAL_PROXY；再完成首页节点列表、持久健康状态和设置/唤醒策略。保持 TunnelSessionCoordinator、epoch、startId token、stopSelfResult、TUN FD 所有权及 HEV→PFD→Xray 清理顺序。首轮不得降低 targetSdk，不得新增高风险权限。

先补能复现问题的测试，再做最小完整修改。每个里程碑报告提交、测试数量和未验证项。最终必须实际运行 instrumentation，并分别报告普通 Android 与 iCAR 真机结果；未做 iCAR 实测时只能交付“iCAR 兼容候选包”。

若计划与现有实现冲突，先以 07 文档的行为契约为准；只有协议、权限、targetSdk、路由、安全边界或验收条件需要变化时暂停并请用户确认。

## 8. 汇报模板

- 当前提交：
- 完成的里程碑：
- 行为变化：
- JVM 测试：数量 / 失败数
- instrumentation：设备、API、数量 / 失败数
- lint / debug / release：
- APK 路径、SHA-256、大小、签名、ABI：
- 普通 Android 结果：
- iCAR 03 结果：
- 未完成或需用户输入：
