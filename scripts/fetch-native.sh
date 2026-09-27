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

# locate any NDK that provides the android24 clang wrappers; the prebuilt dir
# is named linux-x64 up to r26 and linux-x86_64 from r27 on
CLANG_REL="toolchains/llvm/prebuilt/linux-x64/bin/aarch64-linux-android24-clang"
NDK_DIR="$ANDROID_HOME/ndk/26.3.11579264"
[ -x "$NDK_DIR/$CLANG_REL" ] || NDK_DIR=""
if [ -z "$NDK_DIR" ]; then
  for d in "$ANDROID_HOME"/ndk/*/; do
    if [ -x "$d$CLANG_REL" ]; then NDK_DIR="${d%/}"; break; fi
    if [ -x "$d/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang" ]; then NDK_DIR="${d%/}"; break; fi
  done
fi
[ -n "$NDK_DIR" ] || { echo "!! no NDK with android24 clang wrappers found" >&2; ls "$ANDROID_HOME/ndk" >&2 || true; exit 1; }
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x64/bin"
[ -x "$TOOLCHAIN/aarch64-linux-android24-clang" ] || TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
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
# binding.gyp requires node-addon-api (devDependency) at configure time;
# --ignore-scripts skips the prepare hook whose TS build needs excluded sources
npm install --ignore-scripts --no-audit --no-fund --loglevel=error

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
