#!/usr/bin/env bash
# Source this script before every project build; it has no global side effects.
set -euo pipefail
CAR_TUNNEL_SOURCE="${BASH_SOURCE:-$0}"
CAR_TUNNEL_ROOT="$(cd "$(dirname "$CAR_TUNNEL_SOURCE")/.." && pwd)"
export JAVA_HOME="$CAR_TUNNEL_ROOT/.toolchains/jdk-17/Contents/Home"
export ANDROID_HOME="$CAR_TUNNEL_ROOT/.toolchains/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export GOBIN="$CAR_TUNNEL_ROOT/.toolchains/go-1.26.8/bin"
export GRADLE_USER_HOME="$CAR_TUNNEL_ROOT/.cache/gradle"
export GOPATH="$CAR_TUNNEL_ROOT/.cache/go"
export GOMODCACHE="$GOPATH/pkg/mod"
export GOCACHE="$CAR_TUNNEL_ROOT/.cache/go-build"
export ANDROID_USER_HOME="$CAR_TUNNEL_ROOT/.cache/android-user"
export GOTOOLCHAIN=local
export PATH="$JAVA_HOME/bin:$CAR_TUNNEL_ROOT/.toolchains/go-1.26.8/bin:$CAR_TUNNEL_ROOT/.toolchains/gradle-8.11.1/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
