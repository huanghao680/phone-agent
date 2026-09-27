#!/usr/bin/env bash
# Cross-compiles node-pty ($NODE_PTY_VERSION) for android-arm64 with the NDK
# against the upstream Node headers the embedded runtime matches, and stages
# the resulting pty.node into app/src/main/assets/native/node-pty/.
#
# dsh's node-pty loader checks prebuilds/android-arm64/pty.node; the package
# ships no Android prebuild, so we build it here. The app copies the staged
# file into the installed tree after `npm install --ignore-scripts`.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/versions.env

ASSETS="app/src/main/assets/native/node-pty"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

# locate NDK installed by sdkmanager (workflow installs ndk;26.3.11579264)
# pinned exactly: the runner's preinstalled NDK 27 may lack the r24 clang wrappers
NDK_DIR="$ANDROID_HOME/ndk/26.3.11579264"
[ -d "$NDK_DIR" ] || { echo "!! NDK 26.3.11579264 missing at $NDK_DIR" >&2; exit 1; }
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x64/bin"
export CC="$TOOLCHAIN/aarch64-linux-android24-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android24-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export STRIP="$TOOLCHAIN/llvm-strip"
[ -x "$CC" ] || { echo "!! clang wrapper missing: $CC" >&2; ls "$TOOLCHAIN" | head -20 >&2; exit 1; }
echo "== NDK: $NDK_DIR"

echo "== packing node-pty@$NODE_PTY_VERSION"
npm pack "node-pty@$NODE_PTY_VERSION" --pack-destination "$WORK"
tar -xzf "$WORK"/node-pty-*.tgz -C "$WORK"
cd "$WORK/package"

export CC_host=cc
export CXX_host=c++
export GYP_DEFINES="android_ndk_path=$NDK_DIR"
export npm_config_arch=arm64

echo "== node-gyp rebuild (android-arm64, node $NODE_UPSTREAM headers)"
npx node-gyp rebuild --release --arch=arm64 --target="$NODE_UPSTREAM" --dist-url=https://nodejs.org/dist

echo "== staging"
ls -la build/Release/
cp build/Release/pty.node "$ASSETS/pty.node"
ls -la "$ASSETS"
echo "== done"
