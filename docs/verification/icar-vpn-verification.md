# iCAR VPN refactor verification

Date: 2026-09-15

Implementation baseline: `a8382d58bffa9b66ba100ab5a40c255115407e0c`

Verified APK build commit: `68b9f763b7ddbe7446b882b70a55bca6afaf7d3b`

This is the targeted follow-up for runtime wake routing, independent network/wake rebuild switches, direct profile navigation/import, and deferred credential-unlock recovery.

## Automated checks

The following command completed successfully:

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease --no-daemon
```

- JVM unit tests: 55 tests, 0 skipped, 0 failures, 0 errors.
- `lintDebug`: passed; Android lint reported warnings only.
- `assembleDebugAndroidTest`: passed; instrumentation APK was compiled only.
- `assembleDebug`: passed.
- `assembleRelease`: passed.
- Debug signing uses the existing project-local `.cache/android-user/debug.keystore`; the keystore is not tracked or included in the commit. Release signing configuration was not changed.

Instrumentation was not executed. No usable Android device or emulator was available on the host; `adb devices` could not start its daemon. No simulator was installed or started.

The coverage includes the iCAR VPN contract, Xray/HEV configuration, domestic health endpoints, network-set filtering, connectivity/reconnect policy, boot/wake cooldown, persistent profile health, migration, and coordinator behavior. The follow-up regression cases are `networkChangeRebuildsWhenFailureRetryIsDisabled`, `wakeResumeRebuildsWhenFailureRetryIsDisabled`, and `deferredBootResumeIsConsumedOnlyOnce`. Coordinator tests exercise startup, forced healthy-core rebuild, one retry loop, bounded backoff, cancellation, stale epoch protection, cleanup order, token callbacks, and multi-dispatcher visibility.

Runtime `SCREEN_ON` is registered dynamically by the live `CarTunnelService` and submitted as `TunnelCommand.WakeResume`; it is no longer a static manifest receiver. Static `USER_UNLOCKED` is reserved for delayed boot recovery and consumes a persisted pending marker set when credential storage was unavailable during boot. The profile-management button now focuses the existing home list, and import presents only paste or file selection.

## Debug APK audit

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

- SHA-256: `4d9832cd5e6c6db89cbfc2e0b4324a0908296e97e1e4f215bd3ee883f35487fb`
- Size: `21,187,022` bytes
- Package/version: `com.cartunnel.client.debug`, versionCode `5`, versionName `1.0.4-debug` (previous candidate was versionCode `4`, `1.0.3-debug`)
- Signature: existing Android debug certificate, APK Signature Scheme v2; certificate SHA-256 `f988ebba9b31078954ce2a778f86cb7b57cdd220d66030527cb5d2d5814b0fee`, matching `dist/CarTunnel-1.0.3-debug-arm64-v8a.apk`
- ABI: `arm64-v8a`
- SDK: min 28, target 36, compile 36
- Declared permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, and the non-exported app dynamic-receiver permission.
- VPN service binding: `android.permission.BIND_VPN_SERVICE`; service is not exported.

Native submodule revisions and the existing dependency/version locks were not changed. Third-party notices refer to the build-time generated `build/component-inventory.json`.

## Not covered by this verification

No real-device, simulator, VPS, or iCAR data-plane test was run. `adb` could not start its daemon and no device was available. Therefore this artifact is a device-run-test candidate package, not a formal release deliverable.
