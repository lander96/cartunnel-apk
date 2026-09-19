#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$root/native/hev/upstream"
expected="9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a"
git -C "$source_dir" cat-file -e "$expected^{commit}" || git -C "$source_dir" fetch --no-tags origin "$expected"
git -C "$source_dir" checkout --detach --force "$expected"
patch_file="$root/native/hev/patches/0001-cartunnel-jni-class.patch"
git -C "$source_dir" apply --check "$patch_file"
git -C "$source_dir" apply "$patch_file"
test -n "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
git -C "$source_dir" submodule update --init --recursive
"$ANDROID_NDK_HOME/ndk-build" -C "$source_dir" APP_ABI=arm64-v8a APP_PLATFORM=android-28 NDK_PROJECT_PATH="$source_dir" APP_BUILD_SCRIPT="$source_dir/Android.mk" NDK_APPLICATION_MK="$source_dir/Application.mk"
mkdir -p "$root/app/src/main/jniLibs/arm64-v8a"
cp "$source_dir/libs/arm64-v8a/libhev-socks5-tunnel.so" "$root/app/src/main/jniLibs/arm64-v8a/"
find "$root/app/src/main/jniLibs/arm64-v8a" -type f -name '*.so' -exec shasum -a 256 {} \; > "$root/native/hev/jni/artifacts.sha256"
shasum -a 256 "$patch_file" > "$root/native/hev/patches/0001-cartunnel-jni-class.patch.sha256"
