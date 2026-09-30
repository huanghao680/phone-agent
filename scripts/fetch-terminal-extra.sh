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

echo "== opencode $OPENCODE_VERSION (opencode-linux-arm64-musl platform tarball)"
OC_META=$(curl -fsSL "https://registry.npmjs.org/opencode-linux-arm64-musl/$OPENCODE_VERSION")
OC_TARBALL=$(echo "$OC_META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$OC_TARBALL" -o "$ASSETS/opencode.tgz"

# opencode is a Bun single-file executable: it cannot start on its own under
# bionic (its ELF interpreter is the musl loader path) and it needs the GNU C++
# runtime. Both come from Alpine, same build as the musl loader below.
echo "== opencode runtimes (Alpine musl loader + libstdc++/libgcc)"
for spec in "libstdc++:$LIBSTDCXX_APK" "libgcc:$LIBGCC_APK"; do
  name="${spec%%:*}"; apk="${spec#*:}"
  curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/$apk" -o "$WORK/$name.apk"
  mkdir -p "$WORK/$name"
  tar -xzf "$WORK/$name.apk" -C "$WORK/$name" 2>/dev/null || true
done
cp "$WORK/libstdc++"/usr/lib/libstdc++.so.* "$ASSETS/libstdc++.so.6" 2>/dev/null
cp "$WORK/libgcc"/usr/lib/libgcc_s.so.1 "$ASSETS/libgcc_s.so.1" 2>/dev/null
chmod 644 "$ASSETS/libstdc++.so.6" "$ASSETS/libgcc_s.so.1" 2>/dev/null

echo "== musl loader $MUSL_LOADER_VERSION (claude's and opencode's dynamic loader)"
curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/musl-$MUSL_LOADER_VERSION.apk" -o "$WORK/musl.apk"
mkdir -p "$WORK/musl"
tar -xzf "$WORK/musl.apk" -C "$WORK/musl" 2>/dev/null || true
cp "$WORK/musl/lib/ld-musl-aarch64.so.1" "$ASSETS/ld-musl-aarch64.so.1"
chmod 755 "$ASSETS/ld-musl-aarch64.so.1"

# Static musl ripgrep for dsh's glob/grep. Node on Android reports platform
# "android", for which @vscode/ripgrep has no package, so the binary is staged
# behind the platform name its resolver looks for. The linux-arm64 build is
# statically linked and runs as-is on bionic; codex's bundled rg is dynamically
# linked and fails with ENOENT on its loader, so it must not be used here.
echo "== ripgrep $RIPGREP_VERSION (@vscode/ripgrep-linux-arm64, static)"
RG_META=$(curl -fsSL "https://registry.npmjs.org/@vscode/ripgrep-linux-arm64/$RIPGREP_VERSION")
RG_TARBALL=$(echo "$RG_META" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).dist.tarball))")
curl -fsSL "$RG_TARBALL" -o "$WORK/rg.tgz"
mkdir -p "$WORK/rgx"
tar -xzf "$WORK/rg.tgz" -C "$WORK/rgx"
cp "$WORK/rgx/package/bin/rg" "$ASSETS/rg"
chmod 755 "$ASSETS/rg"
echo "== ripgrep staged ($(du -h "$ASSETS/rg" | cut -f1))"

# version manifest the app reads for update checks
echo "{\"codex\":\"$CODEX_VERSION\",\"claude\":\"$CLAUDE_VERSION\",\"ripgrep\":\"$RIPGREP_VERSION\",\"opencode\":\"$OPENCODE_VERSION\"}" > "$ASSETS/versions.json"

ls -la "$ASSETS"
echo "== done"
