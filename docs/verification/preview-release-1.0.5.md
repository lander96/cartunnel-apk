# CarTunnel 1.0.5 预览 Release 测试包验证

状态：**已废弃，请勿用于连接测试**。R8 删除了 `HevNative.TProxyGetStats()`，但 HEV 的 `JNI_OnLoad` 必须注册该方法，导致 `TUN_FORWARDER_FAILED`。修复包及验证见 `preview-release-1.0.6.md`。

基线：main / 4f03e22

目标：移除重复的“节点管理”按钮，并提供可覆盖已安装 1.0.4 测试包的缩减版 APK。

## 构建契约

- `previewRelease` 从正式 `release` 继承 R8 代码缩减、资源缩减和 ProGuard 规则。
- 测试包沿用 `com.cartunnel.client.debug` 和项目内已有的 Android Debug 证书，版本码从 5 增加为 6。
- 正式 `release` 的包名仍是 `com.cartunnel.client`，构建产物仍未签名；本测试包不是正式发布签名包。
- 内部 Xray/HEV、协议、TUN 路由和权限未修改。

## 静态与构建验证

- `:app:testDebugUnitTest`：56 个测试，0 失败/错误/跳过。
- `:app:testPreviewReleaseUnitTest`：56 个测试，0 失败/错误/跳过。
- `:app:lintDebug`、`:app:lintVitalPreviewRelease`：通过。
- `:app:assemblePreviewRelease`、`:app:assembleRelease`、`:app:assembleDebugAndroidTest`：通过。
- `previewRelease` 执行了 `minifyPreviewReleaseWithR8` 和 `optimizePreviewReleaseResources`。
- 首页布局和 Activity 均已删除 `profiles` 按钮及点击处理；生成包的资源表无 `id/profiles` 或“节点管理”文本。
- APK v2 签名及 zipalign 验证通过；VPN Service 不导出并使用 `BIND_VPN_SERVICE`；只含 `arm64-v8a`。

## 可安装产物

- 文件：`dist/CarTunnel-1.0.5-previewRelease-arm64-v8a.apk`
- SHA-256：`08309f581a037d52fed2add5eb3e25e3e68691adc82470deac9e84cd9096bdf2`
- 大小：12,300,560 bytes
- 包名：`com.cartunnel.client.debug`
- 版本：versionCode 6，versionName 1.0.5-test
- 证书 SHA-256：`f988ebba9b31078954ce2a778f86cb7b57cdd220d66030527cb5d2d5814b0fee`

与 `dist/CarTunnel-1.0.4-debug-arm64-v8a.apk` 的包名、证书完全一致且版本码递增，可覆盖安装并保留本地节点数据；若设备上安装的是其他证书签名的同包名 APK，系统会拒绝覆盖，不能通过卸载规避数据丢失。

## 尚未执行

当时没有可用的 Android 真机/模拟器，instrumentation 仅编译、未运行；这份静态验证没有检查 APK 中的完整 HEV JNI 注册表，因此漏过了上述运行时故障。正式 Release 的签名和安装验收亦未执行。
