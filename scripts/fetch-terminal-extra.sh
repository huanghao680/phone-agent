#!/usr/bin/env bash
# Stages the Codex CLI and Claude Code for the terminal picker.
#
# Both are npm-distributed; we stage their platform npm tarballs as-is (they
# are already gzip-compressed) and the app extracts the binaries on first
# use. Updates re-download the platform tarball at the new version, same as
# zcode/dsh. Claude's binary is dynamically linked against musl, so the
# Alpine loader is bundled alongside it.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
source scripts/versions.env

ASSETS="$REPO/app/src/main/assets/terminal-extra"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$ASSETS"

echo "== codex $CODEX_VERSION (platform alias @openai/codex@$CODEX_VERSION-linux-arm64)"
META=$(curl -fsSL "https://registry.npmjs.org/@openai/codex/$CODEX_VERSION-linux-arm64")
TARBALL=$(echo "$META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$TARBALL" -o "$ASSETS/codex.tgz"

echo "== claude-code $CLAUDE_VERSION (@anthropic-ai/claude-code-linux-arm64-musl)"
CC_META=$(curl -fsSL "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/$CLAUDE_VERSION")
CC_TARBALL=$(echo "$CC_META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$CC_TARBALL" -o "$ASSETS/claude.tgz"

echo "== musl loader $MUSL_LOADER_VERSION (claude's dynamic loader)"
curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/musl-$MUSL_LOADER_VERSION.apk" -o "$WORK/musl.apk"
mkdir -p "$WORK/musl"
tar -xzf "$WORK/musl.apk" -C "$WORK/musl" 2>/dev/null || true
cp "$WORK/musl/lib/ld-musl-aarch64.so.1" "$ASSETS/ld-musl-aarch64.so.1"
chmod 755 "$ASSETS/ld-musl-aarch64.so.1"

# version manifest the app reads for update checks
echo "{\"codex\":\"$CODEX_VERSION\",\"claude\":\"$CLAUDE_VERSION\"}" > "$ASSETS/versions.json"

ls -la "$ASSETS"
echo "== done"
