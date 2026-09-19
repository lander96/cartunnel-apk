# CarTunnel 1.0.6 HEV JNI 修复验证

基线：`main / d839d98`。变更只涉及 HEV JNI 的 R8 保留规则、包版本，以及 APK 回归校验。

## 原因与修复

1.0.5 测试包启用 R8 后，`HevNative.TProxyGetStats()` 被移除；HEV 的 `JNI_OnLoad` 必须注册它和另外三个 native 方法，加载失败被运行时代码映射为 `TUN_FORWARDER_FAILED`。1.0.4 Debug 包没有执行缩减，因此不受影响。

`app/proguard-rules.pro` 现保留整个 `HevNative` 类及方法。`scripts/verify-apk.sh` 检查签名 APK 中四个 HEV JNI 方法及原始类名；在 1.0.5 故障包上该检查确实失败，在修复包上通过。

## 本机构建与静态验证

- `testPreviewReleaseUnitTest`：56 个测试，0 失败、错误或跳过。
- `lintVitalPreviewRelease`、`assemblePreviewRelease`、`assembleRelease`：通过；正式 Release 仍未签名。
- 修复包 APK 的 DEX 和 R8 seeds 均保留 `TProxyStartService`、`TProxyStopService`、`TProxyIsRunning`、`TProxyGetStats`，名称与 native 注册表一致。
- `verify-apk.sh --allow-debug`、APK v2 签名和 zipalign：通过。
- 手机连接成功由用户复测报告；本机未运行 instrumentation，车机和 VPS 数据面仍须分别验证。

## 可覆盖安装的测试包

- 文件：`dist/CarTunnel-1.0.6-previewRelease-arm64-v8a.apk`
- SHA-256：`196a27af2bda5d214ba8b90745bed1ae20aa2f7642701b774dbae745d3d550b6`
- 大小：12,300,664 bytes
- 包名：`com.cartunnel.client.debug`；versionCode 7；versionName `1.0.6-test`
- Android Debug 证书 SHA-256：`f988ebba9b31078954ce2a778f86cb7b57cdd220d66030527cb5d2d5814b0fee`

包名和证书与 1.0.4/1.0.5 测试包相同，版本码递增，可以覆盖安装并保留本地配置。它不是正式发布签名包。
