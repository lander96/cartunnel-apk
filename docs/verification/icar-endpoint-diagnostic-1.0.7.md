# iCAR 节点不可达诊断包 1.0.7

状态：**待车机数据验证，尚非车机网络修复结论**。用户报告同一节点在手机 1.0.6 上可用，在 iCAR 车载流量下显示 `ENDPOINT_UNREACHABLE`；参考 APK 在该车机可联网。

## 已确认的行为

- `ENDPOINT_UNREACHABLE` 由经 Xray 请求百度、腾讯测速目标均失败产生；它不是 `VpnService.Builder.establish()` 的错误码。
- 旧 UI 的“已连接”只表示 Xray、TUN、HEV 本地启动成功，不表示已经与远端完成握手。1.0.7 将未测得出口延迟时的文案改为“VPN 已启动 · 出口待验证”。
- 手机可用证明当前节点配置和服务端至少在该网络下有效；参考 APK 的服务器、端口和协议不同，不能直接证明车载流量可访问我们的服务端 TCP 端口。
- 本轮未调整 TUN 路由、DNS、targetSdk、Xray/HEV 版本或服务端端口，避免在缺少车机证据时误改数据面。

## 诊断能力

- 节点手工测速先进行一次不改变 VPN 路由的服务器 TCP 端口检查，记录 `NODE_TCP_OPEN` 或 `NODE_TCP_FAIL`，然后仍执行原 Xray HTTP 测速。
- 两个测速目标分别记录 `NODE_HTTP_*`；连接后异步测速分别记录 `VPN_HTTP_*`。
- 日志只包含节点地址短哈希、端口、延迟、失败类别和异常类名；不记录原始异常文本或节点凭据。
- 设置页新增“查看脱敏诊断（可拍照）”，最近 80 条日志按最新在上显示；原复制功能保留。日志仍只在当前进程内存中，应用重启后不会保留。

## 本机验证与产物

- `testPreviewReleaseUnitTest`、`testDebugUnitTest`：各 58 个测试，0 失败、错误或跳过。
- `lintDebug`、`lintVitalPreviewRelease`、`assemblePreviewRelease`、`assembleRelease`：通过。
- APK 回归校验：HEV JNI 四方法齐全、v2 签名、arm64 ABI、Manifest、native 压缩通过。
- 文件：`dist/CarTunnel-1.0.7-diagnostic-previewRelease-arm64-v8a.apk`
- SHA-256：`d36840bf928b7b898ebbba62a4d4ba136660b05d283b991cdfb4b78a048b68b4`
- 大小：12,309,372 bytes；包名 `com.cartunnel.client.debug`；versionCode 8；versionName `1.0.7-test`。
- 仍沿用项目内 Android Debug 测试证书，可覆盖 1.0.6 测试包并保留节点数据；不是正式发布签名包。

## 车机复验与决策

1. 覆盖安装，不卸载旧包。车载流量下启动 VPN，等待出口探测完成。
2. 停止 VPN，点击当前节点的“测试”，等待结果。
3. 打开“设置与诊断 → 查看脱敏诊断”，拍摄含 `NODE_TCP_*`、`NODE_HTTP_*`、`VPN_HTTP_*` 的页面；必要时向下滚动补拍。无需提供节点凭据。
4. 如果方便，用手机热点重复第 1～3 步，形成“同车机、不同底层网络”的 A/B 对照。

`NODE_TCP_FAIL` 且仅车载流量失败：优先验证车载流量对当前服务器地址/端口的直连能力，再评估服务端改用 443/TCP。`NODE_TCP_OPEN` 但 `NODE_HTTP_FAIL`：继续核对车机时间、REALITY SNI/握手以及服务端连接日志；测速目标不可达仍需与握手失败区分。两种网络都失败：优先检查车机对本应用的网络限制或平台差异。没有这些证据时不降低 targetSdk、不照搬参考 APK 的高风险权限。

Termark 本轮未运行，无法只读核对远端容器及连接日志；本机也没有可用的车机/模拟器，因此车机数据面和服务端端口可达性尚未验证。
