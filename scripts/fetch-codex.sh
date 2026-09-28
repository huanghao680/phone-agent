#!/usr/bin/env bash
# Stages the Codex CLI (OpenAI, Apache-2.0) for the terminal picker.
#
# Codex ships statically-linked musl binaries per platform
# (aarch64-unknown-linux-musl) which run natively on Android bionic —
# verified on-device: `codex --version` works with no dynamic loader.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
source scripts/versions.env

ASSETS="$REPO/app/src/main/assets/codex"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

# resolve the platform variant tarball via the aliased version
META=$(curl -fsSL -x "${PROXY:-}" "https://registry.npmjs.org/@openai/codex/$CODEX_VERSION-linux-arm64")
TARBALL=$(echo "$META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")

echo "== downloading codex $CODEX_VERSION-linux-arm64"
curl -fsSL -x "${PROXY:-}" "$TARBALL" -o "$WORK/codex.tgz"
mkdir -p "$WORK/x"
tar -xzf "$WORK/codex.tgz" -C "$WORK/x"

echo "== staging"
cp "$WORK/x/package/vendor/aarch64-unknown-linux-musl/bin/codex" "$ASSETS/codex"
# TUI companion binaries the CLI may shell out to
mkdir -p "$ASSETS/companions"
cp "$WORK/x/package/vendor/aarch64-unknown-linux-musl/codex-path/rg" "$ASSETS/companions/rg" 2>/dev/null || true
chmod 755 "$ASSETS/codex"
ls -la "$ASSETS"
echo "== done"
