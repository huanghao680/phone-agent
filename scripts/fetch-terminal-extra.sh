#!/usr/bin/env bash
# Stages the Codex CLI and Claude Code for the terminal picker.
#
# Both ship musl binaries that run natively on Android bionic:
# - codex: fully static (aarch64-unknown-linux-musl), no loader needed
# - claude: dynamically linked against /lib/ld-musl-aarch64.so.1, so the
#   Alpine musl loader is bundled next to it and used as the launcher
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
source scripts/versions.env

ASSETS="$REPO/app/src/main/assets/terminal-extra"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

echo "== codex $CODEX_VERSION-linux-arm64"
META=$(curl -fsSL "https://registry.npmjs.org/@openai/codex/$CODEX_VERSION-linux-arm64")
TARBALL=$(echo "$META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$TARBALL" -o "$WORK/codex.tgz"
mkdir -p "$WORK/cx"
tar -xzf "$WORK/codex.tgz" -C "$WORK/cx"
cp "$WORK/cx/package/vendor/aarch64-unknown-linux-musl/bin/codex" "$ASSETS/codex"
chmod 755 "$ASSETS/codex"

echo "== claude-code $CLAUDE_VERSION (musl arm64)"
CC_META=$(curl -fsSL "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/$CLAUDE_VERSION")
CC_TARBALL=$(echo "$CC_META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$CC_TARBALL" -o "$WORK/claude.tgz"
mkdir -p "$WORK/cc"
tar -xzf "$WORK/claude.tgz" -C "$WORK/cc"
cp "$WORK/cc/package/claude" "$ASSETS/claude"
chmod 755 "$ASSETS/claude"

echo "== musl loader $MUSL_LOADER_VERSION (for claude)"
curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/musl-$MUSL_LOADER_VERSION.apk" -o "$WORK/musl.apk"
mkdir -p "$WORK/musl"
tar -xzf "$WORK/musl.apk" -C "$WORK/musl" 2>/dev/null || true
cp "$WORK/musl/lib/ld-musl-aarch64.so.1" "$ASSETS/ld-musl-aarch64.so.1"
chmod 755 "$ASSETS/ld-musl-aarch64.so.1"

ls -la "$ASSETS"
echo "== done"
