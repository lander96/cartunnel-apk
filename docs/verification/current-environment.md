# Current environment verification

Date: 2026-09-14 (Asia/Shanghai)

## Available inputs

- HEV source is checked out at `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a` (`2.17.1`).
- AndroidLibXrayLite source is checked out at `880725442c1d4023a973ccbcdbf527c89ef83a32` (`v26.3.27`), whose module pins Xray-core `v1.260327.0`.
- Script shell syntax: passed (`bash -n`).

## Verified local toolchain

- Go 1.26.8, Gradle 8.11.1, AGP 8.10.1, Android SDK 36 and NDK 27.2.12479018 are supplied in `.toolchains` and loaded by `scripts/env.sh`.
- Xray slim AAR and HEV arm64 artifacts were reproduced by the predecessor. HEV SHA-256 is `ec4c335130d178e37b94c435241c77b397c5a464934c47368bf3963252736c95`.
- 2026-09-14: `testDebugUnitTest` passed (13 JVM tests); `assembleDebugAndroidTest` passed (Keystore instrumentation test compiled). Dependency locks and Gradle SHA-256 verification metadata were generated.

## Still external to this workspace

- No emulator, iCAR device, test VPS, or production signing credential is supplied. Instrumentation execution, real VPN/HEV traffic, reboot/network switching, 8-hour stability and production-signature validation remain unexecuted.
- The workspace is supplied without a Git repository; no commit identifier is available.
