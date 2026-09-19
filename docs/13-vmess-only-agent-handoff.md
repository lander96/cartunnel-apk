# 实施 Agent 交接说明

请从包含本文件的 Git 提交开始工作，严格以 `docs/12-vmess-only-product-ui-plan.md`、根 `DESIGN.md` 和根 `UX-CONTRACT.md` 为行为合同。

## 必须使用的能力

1. 同时加载并完整遵循已安装的 `frontend-design` 与 `frontend-design-premium` skills；这是用户明确指定的 UI 方法。
2. 按 premium skill 读取适用于 Android 产品 UI 的 canonical resolution、design context、interaction、navigation、async、permission/clipboard、layer、anti-pattern 和 verification 参考。
3. 开始编码前运行 upstream compatibility status，并记录结果；若 `frontend-design` 缺失则停止。

## 实施约束

- 单 Agent 完成本轮，不再拆分并行修改。
- 先确认主机、分支、提交、工作区和两个 submodule；不得修改锁定的 Xray/HEV 上游。
- 在现有 Kotlin/XML/View 架构上做最小完整重构；不引入 Compose、Material Components、数据库、字体或图片依赖。
- 先补能复现删除、重复动作和 VPN 授权回传的纯 Kotlin 测试，再改实现。
- VMess-only 存储迁移不得把旧节点导致的异常伪装成 Keystore 损坏；迁移必须幂等且有一次性提示。
- 所有文件编辑使用 apply_patch；保留无关用户改动。
- 远端清理前按计划再次只读核对精确容器/目录，先备份再 compose down；禁止 prune、广泛 rm、删除镜像或触碰其他服务。
- APK 仍为 previewRelease 测试签名，不能称为正式 release。

## 关键回归点

- 导入文件一次点击直接打开 SAF；若 OEM 没有 OpenDocument，单次失败后自动走 GetContent，不弹第二层菜单。
- VPN 授权 callback 与 onResume 只能触发一次 start；取消授权不启动。
- 快速双击连接、导入、保存、删除不能重复执行或反向停止。
- 删除非活动节点可在连接时完成；运行节点经“断开并删除”完成；存储失败不丢 UI 数据。
- 节点卡子按钮不触发父卡选择。
- 诊断对话框内复制，复制后不关闭对话框、不泄露正文到 Toast。
- 旧 VLESS/REALITY 配置迁移行为和提示有测试。

## 验证和交付

- 执行并保存：premium strict audit、DESIGN lint（若工具可用）、anti-pattern diff 搜索、JVM tests、lint、debugAndroidTest 编译、previewRelease 构建、APK 审计。
- Android 真机不可用时必须写“未执行”，不得用编译成功代替设备验证。
- 服务端必须完成 Xray config check、VMess+WS protocol smoke、容器内存/只读/重启策略审计和外网 818 TCP 检查。
- 提交源代码和文档，保持主仓库和 submodule 干净。
- 最终汇报：提交号、行为变化、自动验证、远端清理/部署结果、APK 本地和 VPS 路径、SHA-256、仍需用户执行的 iCAR 测试。
