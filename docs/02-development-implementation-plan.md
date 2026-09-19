# CarTunnel 开发实施计划

状态：Approved for implementation  
执行者：后续开发 Agent  
前置文档：必须先读完 [01-client-technical-design.md](./01-client-technical-design.md)  
实施原则：以最小完整实现为目标；任何新增功能、权限或后台依赖都视为范围变化

## 1. 开工前事实确认

不要根据 “iCAR 03” 名称猜测系统能力。在首台目标车机可连接时，使用开发机外部 ADB 做只读采集；这不等于在 APK 内嵌 ADB：

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell cmd package list packages | grep -F com.cartunnel.client
```

记录到 `docs/device-matrix.md`，不要采集 VIN、账号、定位、Wi-Fi 密码或设备唯一标识。若没有真机，先用 API 28/30 模拟器完成开发，但不得声称 iCAR 验证通过。

创建仓库后先确认：

```bash
git status --short --branch
java -version
./gradlew --version
sdkmanager --list_installed
```

## 2. 预期仓库结构

```text
.
├── app/
│   ├── src/main/
│   ├── src/test/
│   └── src/androidTest/
├── native/
│   └── hev/                 # JNI glue + locked upstream source/submodule
├── core-xray/
│   ├── libs/                # reproducibly built arm64 AAR, if not built inline
│   └── README.md            # exact upstream/build instructions/hash
├── config/
│   ├── dependency-locks/
│   └── versions.lock
├── docs/
│   ├── adr/
│   ├── device-matrix.md
│   ├── user-guide.md
│   └── verification/
├── licenses/
├── scripts/
│   ├── build-xray-core.sh
│   ├── verify-apk.sh
│   └── make-sbom.sh
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

脚本必须 `set -euo pipefail`、固定输入版本并校验 SHA-256。脚本不得下载 `latest` 后直接打包；允许显式的“检查新版本”维护任务，但更新依赖必须产生审查 diff 和完整回归。

## 3. 版本锁定策略

实施 Agent 先创建 `config/versions.lock`，至少记录：

```properties
android.compileSdk=36
android.targetSdk=36
android.minSdk=28
android.ndk=<installed-and-tested-exact-version>
android.cmake=<exact-version>
agp=<exact-version>
kotlin=<exact-version>
go=<exact-version-required-by-wrapper>
gomobile=<full-commit>
xray.server=v26.3.27
xray.android=<xray-core-module-version-and-full-commit>
androidLibXrayLite=<full-commit>
hev=2.17.1
hev.commit=<full-commit>
```

`v26.3.27` 是 2026-09-13 的已核对基线，不表示允许自动追随新版本。若构建 Android core 必须改用其他版本：

1. 先证明支持 VLESS + REALITY `password` + RAW + Vision。
2. 更新锁文件和许可证。
3. 对固定服务端跑 TCP、UDP、网络切换、8 小时稳定性回归。

## 4. 里程碑与提交边界

每个里程碑都应形成一个可构建提交。不要把所有工作堆到一个提交，也不要在同一提交做无关重构。

### M0：仓库骨架与安全基线

任务：

- 创建 Kotlin/XML Android app，临时 `applicationId=com.cartunnel.client`。
- 设置 `minSdk 28 / targetSdk 36 / arm64-v8a`。
- 添加 release R8、resource shrinking、dependency locking 和版本目录。
- 设置 `allowBackup=false`、`usesCleartextTraffic=false`。
- 只声明设计文档白名单权限、Activity、同时承载 VPN/本地代理的 `CarTunnelService` 和 BootReceiver。服务声明 `systemExempted|specialUse`，并提供明确的 special-use subtype property。
- 添加空白三页 UI、notification channel、debug/release build type。
- 添加静态检查、单测和 APK manifest 检查任务。
- `.gitignore` 排除 keystore、`local.properties`、签名属性和导出配置。

验收：

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk
apkanalyzer files list app/build/outputs/apk/debug/app-debug.apk
```

Gate：API 28 和 30 模拟器可安装/启动；manifest 无多余权限；仓库无密钥。

### M1：节点领域层与安全存储

任务：

- 实现 `VlessRealityProfile`、schema migration 框架和字段 validator。
- 实现 `VlessUriCodec`，兼容 URI `pbk` 与 `type=tcp`，内部归一化为 `realityPassword` 与 `raw`。
- 使用 Android Keystore AES-GCM 实现 `SecureProfileStore`。
- 实现当前节点、用户设置、`desiredRunning` 和上次 `TunnelMode` 的持久化。
- 通过 SAF 完成 JSON/URI 导入导出；导出前明确风险确认。
- 完成节点列表和编辑页；所有输入错误定位到字段。

验收：

- validator、URI、加密存储和篡改测试通过。
- 重启进程后节点仍可读取；修改密文任一字节必须认证失败。
- 日志和异常不含测试凭据。

Gate：不联网即可完成节点 CRUD 和 import/export round-trip。

### M2：可复现的 Xray 与 HEV 产物

任务：

- 锁定 AndroidLibXrayLite/Xray-core 的完整 SHA，按官方 gomobile 方法构建 arm64 AAR。
- 锁定 HEV `2.17.1` 完整 SHA，从源码构建 arm64 `.so` 和最小 JNI wrapper。
- JNI 公开最小 API：`start(config, tunFd)`、`stop()`、`isRunning()`；不要暴露任意 shell/file API。
- 明确 FD 所有权：Java 保留 `ParcelFileDescriptor` 所有权，HEV 仅在运行期间借用 `pfd.fd`；停止并 join HEV 后由 Java 关闭 PFD，失败路径也只关闭一次。
- 实现 `ProxyCore`/`TunForwarder` 接口和 fake，实现层不引用 UI。
- 记录完整构建命令、工具链版本、产物 SHA-256、许可证和源码获取方式。
- 检查 AAR/APK 只含 `arm64-v8a`，不含 x86/armv7 冗余库。

建议先独立做两个 smoke test：

1. Xray 只启动 loopback SOCKS，开发机/测试代码通过 SOCKS 访问配套服务端。
2. HEV 使用 fake/local SOCKS 消费测试 TUN FD，反复 start/stop 100 次无崩溃和 FD 增长。

Gate：两核心能单独启动/停止；固定输入可重建同版本产物；第三方义务已记录。

### M3：本地代理与完整 VPN 数据链

任务：

- 用结构化对象实现 `XrayConfigFactory`：VPN 模式只生成 10808 SOCKS inbound；本地代理模式生成 loopback 10808 SOCKS + 10809 HTTP inbound。
- 启动 Xray 后轮询当前模式所需端口，5 秒超时；端口已被占用时返回稳定错误，不随机改端口。Xray 配置必须包含 `stats` manager，并在发布 `Connected` 前完成一次经节点的 HTTPS 出口检测并返回延迟。
- 先完成 `LOCAL_PROXY`：不调用 `VpnService.prepare()`、不创建 TUN、不启动 HEV；用显式配置代理的测试客户端验证 SOCKS5/HTTP CONNECT。
- 实现 `Ipv4RoutePlanner` 和 `VpnInterfaceFactory`。
- 实现 `CarTunnelService` 的串行 command queue、mode、generation 和统一清理。
- 完成前台通知、停止 action、`onRevoke()`、`onTaskRemoved()` 和 `onDestroy()`。移除 Activity task 时不得停止健康的任一模式；`onRevoke()` 只停止 VPN 模式，不得误停本地代理；实际停止由用户命令、VPN 授权撤销或不可恢复错误触发。
- 接通 `VpnService -> TUN FD -> HEV -> SOCKS -> Xray -> VPS`。
- 先只做手工 connect/disconnect，不要同时加入自动重连。

实现注意：

- `VpnService.Builder.establish()`、JNI/core start/stop 都不得在主线程执行。
- `startForegroundService()` 后立即前台化，不能等核心加载完成。
- API 34+ 的 VPN 模式传 `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`，本地代理模式传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；不能同时传两个，也不能让低版本引用不可用常量。
- 将应用自身排除 VPN；若 `addDisallowedApplication` 抛错，停止启动，不能冒险继续。
- local SOCKS/HTTP 只监听 `127.0.0.1`。
- 建链失败统一逆序清理；失败后 TUN、10808/10809 监听和 native 线程都必须消失。
- notification 的“停止”必须携带不可伪造、仅限本包的 explicit PendingIntent。

Gate：本地代理模式无需 VPN 授权即可通过 10808/10809 访问上游，停止后端口释放；VPN 模式在 API 28/30 完成首次授权，连接后 IPv4 出口为 VPS、DNS 可用、IPv6 不旁路；两种模式各连续 start/stop 100 次，并反复切换 50 次，无崩溃且 FD/线程回到基线附近。

### M4：恢复机制与车机行为

任务：

- 实现 `ConnectivityMonitor`，忽略 VPN transport，2 秒 debounce。
- 实现 1/2/5/10/30 秒退避和取消语义。
- 实现 `START_STICKY` 恢复、`BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`，恢复上次处于 running 的具体模式。
- 实现动态 `SCREEN_ON` 健康检查；健康连接不得被无条件重启。
- 在设置页加入“开机自动恢复”，并说明依赖车机允许后台/自启动。
- 不添加任何 iCAR 私有 action。只有真机证据证明标准机制不足时才新建 OEM ADR。

Gate：API 28/30 模拟器分别在 VPN/本地代理模式完成 airplane/network 切换和 reboot；真机完成锁车休眠/唤醒时间线。用户主动停止后，无论网络/屏幕/开机广播如何都不得恢复任一模式。

### M5：UI、诊断与小型化

任务：

- 完成横屏布局、48dp 触控目标、状态与字段错误文案，以及清晰的 `全局 VPN / 本地代理` 两段式模式选择。
- UI 只观察 `TunnelStateStore`，不直接操作 JNI。
- 实现 200 条/128 KiB 内存环形日志和完整脱敏。
- 实现诊断信息导出、清除；不接入遥测或崩溃 SDK。
- 打开 R8/resource shrinking，分析 APK 大文件和依赖树。
- 删除 debug 菜单、未使用资源、其他 ABI、远程 URL 和测试凭据。

Gate：主流程在 1280×720 横屏无需滚动或仅编辑页合理滚动；release APK 目标 ≤20 MiB；静态字符串扫描无参考 APK 域名、ADB、在线更新器和测试 key 痕迹。

### M6：发布候选与真机验收

任务：

- 使用配套 Ubuntu Xray 节点执行完整矩阵。
- 交付 Ubuntu-only Docker 交互式部署脚本：仅检查 Docker/Compose，不安装系统依赖；自动生成固定版本 Compose、REALITY 服务端配置、CarTunnel JSON 和 VLESS URI，并在启动前完成配置校验。
- 在至少一台 iCAR 03 做 8 小时运行、网络切换、休眠/唤醒和重启恢复。
- 收集 `dumpsys vpn`、`dumpsys activity services`、进程内存/线程/FD 的脱敏摘要。
- 核对 manifest、native ABI、签名证书、SBOM 和许可证。
- 用正式私有 key 签名；输出 APK、SHA-256、版本说明和用户指南。
- 安装 release 包重新跑关键路径，不能用 debug 测试代替 release 验收。

Gate：满足技术设计第 13 节全部标准，未通过项逐条标记，禁止把“未测”写成“通过”。

## 5. 关键实现伪代码

### 5.1 UI 发起运行

```kotlin
fun onStartClicked(mode: TunnelMode) {
    val profile = profileStore.current() ?: return showNoProfile()
    validator.validate(profile).throwIfInvalid()

    if (mode == TunnelMode.VPN) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            requestTunnelStart(profile.id, mode, userInitiated = true)
        }
    } else {
        requestTunnelStart(profile.id, mode, userInitiated = true)
    }
}
```

授权结果只表示可以尝试建立 VPN，不表示连接已成功。真实状态以 service state 为准。

### 5.2 Service command 串行化

```kotlin
sealed interface TunnelCommand {
    data class Start(
        val profileId: String,
        val mode: TunnelMode,
        val reason: StartReason
    ) : TunnelCommand
    data object Stop : TunnelCommand
    data class UnderlyingNetworkChanged(val networkId: String?) : TunnelCommand
}

private suspend fun handle(command: TunnelCommand) = commandMutex.withLock {
    when (command) {
        is Stop -> stopAndClearDesiredState()
        is Start -> startGeneration(command)
        is UnderlyingNetworkChanged -> scheduleReconnectIfDesired()
    }
}
```

不要机械照抄伪代码；必须确保 stop 可以取消正在等待的 readiness/retry，并且 `desiredRunning=false` 在清理前持久化。

### 5.3 统一失败清理

```kotlin
suspend fun fail(generation: Long, error: SafeTunnelError) {
    if (generation != activeGeneration) return
    cleanupRuntime()
    stateStore.set(Error(error.code, error.userMessage, error.recoverable))
    if (error.recoverable && settings.desiredRunning) reconnectPolicy.schedule()
}
```

## 6. 测试与验证命令

项目落地后保证以下命令有稳定含义：

```bash
./gradlew clean testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
./gradlew dependencies
./scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
./scripts/make-sbom.sh
```

`verify-apk.sh` 至少检查：

- APK v2/v3 签名有效且非 debug/AOSP test key。
- application ID、versionCode/versionName。
- min/target SDK。
- 只含 `arm64-v8a` native libraries。
- 权限集合与 allowlist 完全一致。
- `usesCleartextTraffic=false`、`allowBackup=false`。
- VpnService 的 permission、exported、FGS type 正确。
- `systemExempted` 与 `specialUse` 的权限/type/property 均存在，运行时按 mode 只请求其中一个。
- 不含禁止字符串：参考 APK 域名、`5555`/`5556` 等 ADB 扫描代码、`appops`、`pm install`、在线更新 URL。
- 输出 APK SHA-256 和大小。

真机常用只读验证：

```bash
adb shell dumpsys package com.cartunnel.client
adb shell dumpsys vpn
adb shell dumpsys activity services com.cartunnel.client
adb shell dumpsys meminfo com.cartunnel.client
adb shell pidof com.cartunnel.client
adb logcat --pid "$(adb shell pidof com.cartunnel.client)"
```

日志归档前必须脱敏；不要把用户真实节点 URI 写入仓库。

## 7. 安全评审清单

开发 Agent 在发布候选上逐项回答“是/否/证据”：

- 配置是否从未离开设备，除非用户主动导出？
- 节点凭据是否使用 Keystore + AES-GCM，备份是否关闭？
- Xray SOCKS 是否只监听 loopback？
- 本地代理 HTTP 10809 是否同样只监听 loopback，且没有修改系统 proxy/route？
- 应用自身是否排除 VPN，是否禁止 `allowBypass()`？
- IPv6 是否在连接时被阻断而不是直连泄漏？
- 是否只有 allowlist 权限和组件？
- 是否完全没有 ADB、通知监听、WebView、在线更新和动态代码？
- release 是否禁用 cleartext traffic？
- 正式签名材料是否在仓库和 APK 之外安全保存？
- 核心、JNI、licenses、SBOM 是否与锁文件一致？
- start/stop/revoke/error 是否都回收 TUN FD、端口、线程和 wake lock？
- 诊断导出是否通过自动化测试证明脱敏？

任一“否”必须阻止发布或形成用户明确接受的 ADR。

## 8. 发布与升级策略

- 版本号采用 `major.minor.patch`，`versionCode` 单调递增。
- 不做应用内升级。由用户/运维通过侧载、MDM 或受控安装渠道升级。
- 保持相同 application ID 和正式签名即可覆盖安装并保留 VPN 配置；升级前在副本设备测试 schema migration。
- 节点 schema 只允许向前迁移。解析到未来未知 schema 时只读失败，不覆盖原数据。
- 正式 key 离线备份；丢失 key 将无法原地升级。
- 发布包同时提供 `app-release.apk`、`app-release.apk.sha256`、`THIRD_PARTY_NOTICES`、SBOM、release notes。

## 9. 实施中允许自主决定的事项

无需再次询问用户：

- XML 控件具体布局、颜色和文案微调。
- 类名、包内目录、测试框架的小调整。
- 不改变功能和权限的依赖补丁版本。
- MTU 1500 与 1400 的真机对比后选择更稳者，并把证据写入验证记录。

必须暂停并确认：

- 新增任何协议、远程后台、账号/订阅或在线更新。
- 增加 ADB、私有 OEM API、系统签名、root、通知监听、无障碍或敏感权限。
- 改为按应用/规则分流，或允许 IPv6 旁路。
- 降低 target SDK、放宽证书/REALITY 校验或允许明文网络。
- 将 armv7 打入同一个 universal APK，或更换正式 application ID/签名后的兼容策略。

## 10. Agent 完工回报模板

最终回复保持事实化：

```text
完成：<里程碑/功能>
提交：<commit SHA>
产物：<绝对路径或制品链接 + SHA-256>
验证：<单测/仪器/真机，分别列结果>
设备：<型号、固件、API、ABI；无真机则明确写未测>
安全：<权限、签名、SBOM、敏感字符串扫描>
限制：<仍未验证或不在范围内的事项>
```
