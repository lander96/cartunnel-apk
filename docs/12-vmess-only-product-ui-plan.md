# CarTunnel VMess-only 产品与界面重构计划

状态：Implementation Ready  
基线：`48e53b9d75e79e6fc555f0cd667902df287c92db`  
目标版本：`1.1.0-test`  
主设备：iCAR 03，Android 9/11，横屏触控  

## 1. 已确认目标

1. 产品只保留全局 VPN、VMess AEAD、WebSocket、无 TLS；删除 REALITY、VLESS+WS+TLS 和本地代理残留。
2. 修复车机无法删除节点，以及导入/连接需要重复点击的问题。
3. 主页重组为状态、连接、节点列表和最少菜单；删除重复入口。
4. “复制脱敏诊断”移入“查看脱敏诊断”对话框，删除所有“可拍照”文案。
5. 只保留一套 Ubuntu + Docker Compose 服务端部署脚本和一个有效容器。
6. 根 README 变为当前实现、使用、部署、构建和验收的权威入口。

## 2. 根因与修复策略

### 2.1 删除节点

现实现的 `confirmDelete()` 在 `desiredRunning=true` 时直接返回，因此连接期间任何节点都无法删除；删除和持久化异常也未捕获，用户只看到“无反应”或进程异常。

修复：

- 非运行节点可在 VPN 连接期间删除。
- 正在使用的节点显示“断开并删除”确认；等待状态机进入 `Stopped` 后再提交本地 hard-delete。
- 删除包含加密配置、健康记录和当前选择修复，三步由一个可测试协调器串行执行。
- 持久化失败时保留节点与对话框，显示“删除失败，本地加密存储不可写”，不得宣称成功。
- 删除文案明确“只删除本机配置，不影响服务器”。

### 2.2 导入需要两次操作

主页“导入”当前先打开二级菜单，用户再选择文件或粘贴，本身就是两个动作。改为“导入配置”直接启动 SAF 文件选择器；“粘贴导入”进入低频配置菜单。单节点导入成功后自动设为当前节点。

### 2.3 连接需要再次点击

现实现只在 VPN 授权 Activity 返回 `RESULT_OK` 时继续；部分车机实际已授权但返回结果异常，第二次点击时 `VpnService.prepare()==null` 才启动。

修复：

- 增加 lifecycle-safe 的 `PendingVpnStartGate`。
- 发起系统授权前记录 pending profile/generation。
- ActivityResult 返回时以“RESULT_OK 或 prepare 已为 null”收敛。
- `onResume` 再复查一次授权；实际已授权则自动继续且清除 pending。
- gate 保证 start 最多执行一次，取消授权则显示明确提示。
- 连接、导入、保存、删除增加共享 single-flight/action gate，并记录脱敏的动作 accepted/ignored 日志。
- 不采用自定义 TouchListener 强行模拟点击；先修复真实流程和菜单层级，避免破坏无障碍、旋钮和键盘事件。

## 3. 协议和数据模型精简

### 3.1 新模型

用 `VmessWsProfile` 替代 `VlessRealityProfile`，只保留：

- schemaVersion、id、name
- server、port、uuid
- wsHost、wsPath

固定隐含值：protocol=vmess、network=ws、security=none、alterId=0、cipher/security=auto。

删除 `ProfileMode`、`VlessUriCodec`、REALITY/SNI/flow/shortId/password/fingerprint/spiderX 分支及对应 UI、测试和说明。

### 3.2 兼容迁移

- 新 JSON schema 为 2；仍接受部署脚本生成的 schema 1 VMess+WS 配置并归一化。
- 首次读取旧加密存储时，只迁移 `protocol=vmess + transport=ws + security=none` 节点。
- VLESS/REALITY 节点按已确认产品范围移除，并显示一次性非敏感提示“已移除 N 个旧协议节点”。
- 当前节点若被移除，则自动选择首个迁移后的节点；无节点则进入空状态。
- 迁移逻辑抽成纯 Kotlin 函数并覆盖混合配置、空配置、损坏条目、当前节点修复和幂等测试。

## 4. 信息架构

### 4.1 主页

```text
┌──────────────── 状态轨 ────────────────┐
│ 未连接 / 正在连接 / 已连接 / 错误       │
│ 当前节点 · 最近延迟 · 恢复提示           │
│              [ 连接 / 断开 ]             │
└─────────────────────────────────────────┘

节点
┌ 选中 │ 节点名 │ 服务器:端口 │ 延迟 ┐
│      │        │ [测试] [更多]          │
└───────────────────────────────────────┘

[ 导入配置 ]       [ 配置 ▾ ]       [ 设置 ]
```

- 横屏用左右双栏；竖屏自然堆叠。
- “导入配置”直接打开文件选择器。
- “配置”菜单：手工新增、粘贴导入、导出全部。
- 节点“更多”：编辑、导出 JSON、导出 VMess URI、删除。
- 删除“设为当前”和“测试节点延迟”的菜单重复项；选节点靠卡片，测试靠卡片按钮。
- 空状态直接提供导入与手工新增。

### 4.2 设置与诊断

- 连接恢复：开机连接、失败重试、网络切换重连、唤醒恢复。
- 网络：绕过局域网及生效条件说明。
- 诊断与关于：查看脱敏诊断、清除日志、许可证、版本。
- 设置保留明确“保存设置”；按钮 busy 时稳定尺寸并防重复。
- 诊断对话框标题固定“脱敏诊断”，按钮为“复制诊断”“关闭”；无“可拍照”字样。

## 5. 视觉实现

- 遵循根 `DESIGN.md`；现有 Android resources 是运行时 token owner。
- 建立 `colors.xml`、`dimens.xml`、DayNight 色值、Button/Card/状态样式和必要 selector/drawable。
- 主按钮至少 68dp，其他重要按钮至少 56dp，触控目标至少 48dp。
- 不新增 Compose、Material Components、图片、字体或动画依赖。
- 横屏增加 `layout-land/activity_main.xml`；手机继续使用竖屏布局。
- 状态改变不得推动连接按钮；选中、成功、失败同时用文本与视觉提示。

## 6. 服务端与仓库清理

### 6.1 唯一部署目录

`deploy/server/` 只保留 `deploy.sh`。脚本仅检查 Docker Engine 与 Docker Compose plugin，不自动安装；交互确认部署目录、公网地址、映射端口和 WS Host。WS Host 默认 `www.baidu.com`，作为车机白名单域名使用并提示用户自行测试。脚本内嵌 Xray `26.3.27` 的 Compose 与配置模板，生成 UUID、WS 路径、`compose.yaml`、Xray JSON、客户端 JSON/VMess URI并启动容器；容器保留 256 MiB 内存限制、只读根、cap_drop ALL、no-new-privileges、日志轮转。部署完成只确认容器正常运行，不启动额外测试容器或执行协议 smoke。使用说明统一放在根 `README.md`。

删除仓库旧目录 `deploy/xray-docker`、`deploy/xray-ws-compat` 和重复的 `deploy/xray-vmess-ws-compat` 名称；新目录成为唯一入口。

### 6.2 服务器清理原则

真实服务器标识、端口拓扑、容器名、证书位置、部署路径、备份路径和运行日志不得提交到公开仓库。服务器迁移与清理记录应存放在受控的私有运维系统中；公开文档仅保留使用占位符的通用步骤。

## 7. README 与文档

根 `README.md` 必须重写并包含：

1. 产品范围和工作链：Android VpnService → TUN → HEV → 本地 SOCKS → Xray VMess+WS → VPS。
2. 支持矩阵：Android 9+、arm64-v8a、无 root、手动 VPN 授权、IPv4。
3. 客户端安装、节点导入、连接、测试、设置和诊断。
4. 服务端 Ubuntu + Docker Compose 部署和映射端口安全组要求。
5. 无 TLS 模式的安全边界：VMess 载荷加密，但 IP/端口/Host/路径可观察。
6. 可复现构建、测试、APK 类型和签名说明。
7. 故障排查与脱敏日志关键码。

历史验证文档可以保留，但 `docs/README.md` 必须标记旧 VLESS/REALITY 文档为历史，不再作为当前部署入口。

## 8. 验收门槛

### 自动化

- 新增/更新 JVM 测试：VMess JSON/URI round-trip、Xray JSON、schema 迁移、删除协调器、single-flight、VPN 授权 gate。
- `testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest`、`assemblePreviewRelease` 全部通过。
- `verify-apk.sh`：v2/v3 签名、min/target SDK、arm64-only、权限白名单、cleartext 声明、native Deflate、版本与大小。
- Frontend Design Premium strict audit、DESIGN lint、anti-pattern diff 搜索执行并保存结果；Android 不适用项必须明确记录，不能伪称浏览器验证。

### 人工

- 普通 Android：导入、选择、连接、删除、权限取消/允许、Wi-Fi/蜂窝切换。
- iCAR：导入和连接均只需一次应用内点击（系统 VPN 确认另计）；删除停止态节点和运行节点；横屏触控；诊断复制。
- 连续连接/断开 10 次；Wi-Fi/车载流量切换 5 轮；休眠 10 分钟；开机恢复。
- 服务端从干净目录部署、重启并完成协议 smoke；旧服务确实停止且当前节点继续可用。

### 产物

- `CarTunnel-1.1.0-VMess-WS-previewRelease-arm64-v8a.apk`
- SHA-256、大小、签名证书摘要、构建日志、APK 审计日志。
- APK、客户端 JSON/VMess URI、部署/清理记录存放在受控的私有发布或运维系统中。

## 9. 非目标

- 不实现 TLS、REALITY、VLESS、本地代理、分应用、IPv6、订阅、账号、广告、更新器、ADB/root。
- 不复制参考 APK 的代码、资源、证书或线上节点。
- 不承诺未执行的 iCAR 真机验收。
