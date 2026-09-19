# UX Contract

## Product context

- Audience：iCAR 03 等允许侧载 APK 的车主和工程测试人员。
- Primary jobs：导入自建节点、选择节点、测试、连接/断开、读取脱敏诊断。
- Target market(s)：中国大陆自有设备测试场景。
- Active locales：简体中文。
- Language/content register and native-review policy：直接、技术准确；中文由项目所有者真机复核。
- Timezone/calendar policy：节点测试时间使用设备本地时区，不承担业务日期计算。
- Accessibility target：WCAG 2.2 AA 原则与 Android 原生可访问性；车机关键触控目标至少 48dp。

## Business-context sources

| Domain / scope | Authoritative source | Source type | Reviewed date |
|---|---|---|---|
| 支持协议与设备 | 当前用户需求；`docs/verification/1.0.9-vmess-ws-compat.md` | 产品决策 / 真机证据 | 2026-09-16 |
| VPN 生命周期 | `docs/07-icar-vpn-refactor-plan.md`；状态机测试 | 设计合同 / 测试 | 2026-09-16 |
| 本地节点生命周期 | 当前用户需求；`SecureProfileStore` | 产品决策 / 实现证据 | 2026-09-16 |
| 凭据与诊断隐私 | `docs/01-client-technical-design.md` 第 5.3 节 | 安全设计 | 2026-09-16 |
| 服务端部署 | `deploy/server/deploy.sh`；根 `README.md` | 部署合同 / 使用说明 | 2026-09-17 |

旧文档中 VLESS、REALITY、本地代理等描述已经被当前用户决策替代，只能作为历史证据，不能继续决定产品范围。

## Visual contract

- Project `DESIGN.md`：`/DESIGN.md`
- Token ownership model：现有 Android runtime resources canonical；DESIGN.md 镜像批准值和意图。
- Runtime design-system/token source：`app/src/main/res/values`、`values-night`、共享 style/drawable。
- Mapping/export/adapters：所有布局和 Activity 通过命名资源消费，不复制颜色、圆角和控件高度。
- Token drift gate：布局中新增原始颜色值视为失败；DESIGN.md 与资源同次提交更新。
- Supported themes：系统浅色/深色。
- Design-context owner/review policy：本仓库维护；车机真机截图为最终视觉证据。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Form | `ProfileEditActivity` 共享字段样式与 `ProfileValidator` | 本合同 + 校验单测 | 新增 / 编辑 | JVM + instrumentation 编译 + 真机 |
| CRUD | `MainActivity` 的节点动作协调器 | 本合同 | 停止态删除 / 断开并删除 | JVM 状态测试 + 真机 |
| Dialog | `UiFeedback.dialog` + AppCompat `AlertDialog` | 本合同 | 信息 / 凭据 / 永久删除 | 焦点、按钮和恢复测试 |
| Status feedback | 主页稳定状态区 +短 Toast | 本合同 | success / warning / error | 状态测试 + 真机 |
| File import | SAF `OpenDocument`，`GetContent` 仅作为 OEM fallback | 本合同 | JSON / VMess URI | instrumentation 编译 + 真机 |
| Toast | `UiFeedback.notice` | 本合同 | 去重的短确认 | Android 编译 + 真机 |
| Scrollbar | Android 原生 `ScrollView` | DESIGN.md | 竖屏整体滚动 / 横屏两栏独立滚动 | 资源检查 + 真机 |

Premium 1.4.0 审计器的 capability 枚举仅识别部分 Web 名称；Dialog、Status feedback、File import 三个 Android 合同项由项目 `verify-android-ui.py` 补充检查，不因此豁免设备运行验证。

## Component behavior

| Component | Default | Focus | Active | Disabled | Busy | Error |
|---|---|---|---|---|---|---|
| 主连接按钮 | 连接/断开 | 可见焦点框 | 平台按压反馈 | 停止收尾时禁用 | 文本立即变化且动作门禁 | 状态区说明并可重试 |
| 次要按钮 | 文字+边框 | 可见焦点框 | 平台按压反馈 | 灰化并说明 | 防重复点击 | 就地反馈 |
| 节点卡 | 未选 | 整卡焦点 | 点击只选择 | 运行约束时仍可查看 | 测试状态稳定 | 显示错误码 |
| 输入 | 标签+帮助 | 可见焦点 | n/a | 灰化 | 保存按钮门禁 | 字段错误+页级摘要 |
| 诊断列表 | 等宽、最新在上 | 可滚动 | n/a | n/a | n/a | 空日志显示“暂无日志” |

## Dataset navigation

- 节点列表是小型有界本地列表，全部渲染，不分页、不搜索。
- 空状态提供“导入配置”和“手工新增”。
- 选择状态持久化；删除当前节点后选择下一个可用节点，否则为空。
- 节点卡的子按钮不触发父卡选择。

## Flow ledger

| Operation | Trigger | Pending | Success destination | Success feedback | Failure recovery | Focus outcome | Source ref |
|---|---|---|---|---|---|---|---|
| 文件导入 | 导入配置 | 防重复启动 SAF | 主页 | 已导入并选中节点 | 保留原列表并显示格式/文件错误 | 返回导入按钮或新节点 | 当前需求 |
| 粘贴导入 | 配置→粘贴导入 | 导入按钮忙碌 | 主页 | 已导入并选中节点 | 对话框保留文本、显示错误 | 返回文本框 | 当前需求 |
| 编辑 | 节点更多→编辑 | 保存门禁 | 主页 | 节点已保存 | 保留字段并聚焦首错 | 更新后的节点 | 当前需求 |
| 测试 | 节点卡→测试 | 当前测试按钮禁用 | 主页 | 可用与延迟 | 明确不可达并可重试 | 原测试按钮 | 当前需求 |
| 连接 | 主按钮→连接 | 单次动作门禁；必要时系统 VPN 授权 | 主页 | 已连接和出口延迟 | 授权回传异常时 onResume 复查；失败可重试 | 主按钮 | 真机问题 |
| 断开 | 主按钮→断开 | 显示正在断开 | 主页 | 未连接 | 失败保留错误和重试 | 主按钮 | VPN 合同 |
| 永久删除 | 节点更多→删除 | 确认框；运行节点先断开 | 主页 | 节点已删除 | 对话框保留并显示存储错误 | 下一节点或导入按钮 | 真机问题 |
| 复制诊断 | 查看脱敏诊断→复制诊断 | 防重复 | 诊断对话框保持打开 | 显示“已复制”且不回显正文 | 显示复制失败 | 复制按钮 | 当前需求 |

## Navigation and responsive behavior

- 主页是唯一主工作面；节点编辑和设置是二级 Activity。
- 手机竖屏纵向自然滚动；车机横屏双栏，左侧状态与连接，右侧列表与低频配置。
- 高频导入文件不进入菜单；低频新增、粘贴、导出进入单层“配置”菜单。
- 节点更多不重复选择和测试。
- 返回主页后恢复列表、选择和滚动；异步授权回来后不要求再次点击连接。

## Overlays and feedback

- Dialog primitive：AppCompat AlertDialog，经共享 helper 统一标题、按钮语义和错误保留。
- Destructive confirmation levels：本地节点为不可恢复的 hard-delete；明确“不影响服务器”。
- Toast/status：短确认使用 Toast，连接和可恢复错误保留在主页状态区，重复消息去重。
- Unsaved changes：节点编辑已修改后返回需要确认放弃。
- Layer contract：Activity < dialog < Toast；不嵌套对话框。

## Async and resilience

- 本地写入和删除使用 pessimistic UI，持久化成功后再更新列表。
- 同一动作使用 single-flight gate；连接、导入、保存、删除不得重复提交。
- VPN 授权通过 ActivityResult 和 `onResume + VpnService.prepare()==null` 双路径收敛，只能启动一次。
- 节点测试和连接状态通过 generation/token 忽略过期结果。
- 自动重连保持已有有界退避和用户停止取消语义。

## Validation

- 只接受 VMess AEAD、WebSocket、无 TLS、alterId=0。
- 新配置 schema 移除 VLESS/REALITY 字段；旧存储只迁移符合 VMess+WS 的节点，其余按用户决定的产品范围移除并给一次性提示。
- 手工编辑只展示服务器、端口、UUID、WebSocket Host、WebSocket 路径和节点名。
- 错误不记录或显示 UUID、完整配置和密钥。

## Permission and clipboard

- VPN 授权由系统页面负责；应用不得模拟或绕过。
- 诊断仅在用户点击后复制，成功提示不包含诊断正文。
- 连接凭据导出始终二次确认。

## Migration status

- 当前风险切片：删除可靠性、单击动作、VMess-only 数据迁移、主页菜单、诊断合并。
- 旧协议类、UI、测试和部署脚本在迁移测试通过后删除。
- 远端旧容器先备份配置再 `docker compose down`，保留 VMess+WS 容器。
- 回滚点是实施前 Git 提交和服务器运维目录下时间戳备份。

## Verification

- Required static commands：premium strict audit、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest`、`assemblePreviewRelease`、APK 审计。
- Device matrix：普通 Android 手机 + iCAR 03；竖屏 + 横屏；浅色 + 深色。
- CRUD evidence：新增/导入/选择/编辑/导出/删除完整流程，包含运行节点“断开并删除”。
- Failure evidence：无文件选择器、无效 JSON/URI、Keystore 写失败模拟、VPN 授权取消/回传异常、重复点击、停止超时。
- 服务端：干净 Ubuntu + Docker Compose 部署、内存限制、同机 protocol smoke、外网 TCP、重启恢复。

## 1.0.0 首次使用同意（2026-09-17 用户决策）

- 协议来源：根目录 `LICENSE`，标准 MIT；第三方组件保留各自许可证。随 APK 打包 `licenses/CarTunnel-MIT.txt`，提示展示完整原文及中文摘要。
- `UserConsent` 是安装级同意状态的唯一所有者；`MainActivity` 通过 `UiFeedback.dialog` 展示不可取消的首次提示。未同意不初始化主界面，后台恢复也不启动 VPN。
- “同意并继续”持久化成功后进入主页；写入失败保持提示并可重试。“不同意并退出”退出，下一次打开继续提示。旋转不表示同意。
- 同意后普通重启、进程重建、覆盖升级不重复提示；清除数据或卸载重装重新提示。此前没有同意记录的旧版升级首次也提示。
- VPN 授权文档遵照本次用户指定的 ADB 命令，应用自身仍通过系统 VPN 权限流程启动连接。
