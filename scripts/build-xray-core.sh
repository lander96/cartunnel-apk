#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$root/core-xray/upstream"
expected="880725442c1d4023a973ccbcdbf527c89ef83a32"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$expected"
command -v go >/dev/null
command -v gomobile >/dev/null
test -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
cd "$source_dir"
gomobile init
go mod download
gomobile bind -v -target=android/arm64 -androidapi 28 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o "$root/core-xray/libs/cartunnel-xray.aar" ./
shasum -a 256 "$root/core-xray/libs/cartunnel-xray.aar" > "$root/core-xray/libs/cartunnel-xray.aar.sha256"
cp "$root/core-xray/libs/cartunnel-xray.aar" "$root/core-xray/libs/cartunnel-xray-slim.aar"
zip -q -d "$root/core-xray/libs/cartunnel-xray-slim.aar" assets/geoip.dat assets/geosite.dat
shasum -a 256 "$root/core-xray/libs/cartunnel-xray-slim.aar" > "$root/core-xray/libs/cartunnel-xray-slim.aar.sha256"
