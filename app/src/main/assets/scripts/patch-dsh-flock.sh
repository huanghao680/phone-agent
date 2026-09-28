#!/system/bin/sh
# Post-install patch for dsh 0.1.7+ on Android.
#
# Two Android incompatibilities, both already hit and fixed for other agents
# in this app:
#
# 1. flock: dsh 0.1.7 added @deepseek-ai/node-addon-system, which only ships
#    glibc/musl linux-arm64 builds. No android build exists on npm, so the
#    stock module throws "flock is not supported on android-arm64". Writes
#    are serialized by our single Node process anyway, so it is stubbed.
#
# 2. link(): session persistence (dsh-session-persistence-jsonl) creates the
#    session log atomically with link(); Android's SELinux policy denies link
#    for app data on Android 11+ (same failure zcode hit). rename() is allowed,
#    so it is substituted.
#
# Idempotent: guards on markers so re-running is a no-op.

USR="/data/user/0/com.phoneagent/files/usr"
MOD="$USR/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
FLOCK_JS="$MOD/node-addon-system/lib/flock.js"
JSONL_JS="$MOD/dsh-session-persistence-jsonl/lib/index.js"
DSH_DIR="$MOD/dsh"

SED="sed"

# --- 1. flock: stub out the native binding -----------------------------------
if [ -f "$FLOCK_JS" ]; then
  if grep -q "PHONE_AGENT_ANDROID_STUB" "$FLOCK_JS"; then
    echo "[phone-agent] flock already patched."
  else
    echo "[phone-agent] Patching flock for Android (no-op stub)..."
    printf '%s\n' \
      "// PHONE_AGENT_ANDROID_STUB" \
      "// Android has no prebuilt @deepseek-ai/node-addon-system binding." \
      "// Single-process writes are already serialized; acquire is a no-op." \
      "export async function tryLockExclusive(fd) {" \
      "  void fd" \
      "  return" \
      "}" > "$FLOCK_JS"
    echo "[phone-agent] flock patched."
  fi
fi

# --- 2. session persistence: link() -> rename() ------------------------------
if [ -f "$JSONL_JS" ]; then
  if grep -q "PHONE_AGENT_ANDROID_LINK_PATCH" "$JSONL_JS"; then
    echo "[phone-agent] session persistence already patched."
  else
    echo "[phone-agent] Patching session persistence (link -> rename fallback)..."
    # widen the import to include rename and alias link
    $SED -i 's|import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";|import { link as linkNative, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";|' "$JSONL_JS"

    # insert the fallback wrapper right after the import line (line 4)
    TMP="$MOD/dsh-session-persistence-jsonl/lib/tmp-link-patch.js"
    head -n 4 "$JSONL_JS" > "$TMP"
    printf '%s\n' \
      "const link = async (f, t) => {" \
      "  try {" \
      "    await linkNative(f, t)" \
      "  } catch (e) {" \
      "    if (e && (e.code === 'EACCES' || e.code === 'EPERM' || e.code === 'EMLINK' || e.code === 'EXDEV')) {" \
      "      await rename(f, t)" \
      "    } else {" \
      "      throw e" \
      "    }" \
      "  }" \
      "};" \
      "// PHONE_AGENT_ANDROID_LINK_PATCH" >> "$TMP"
    tail -n +5 "$JSONL_JS" >> "$TMP"
    mv "$TMP" "$JSONL_JS"

    if grep -q "PHONE_AGENT_ANDROID_LINK_PATCH" "$JSONL_JS"; then
      echo "[phone-agent] session persistence patched (link -> rename fallback)."
    else
      echo "[phone-agent] WARN: link->rename patch did not apply (import line changed?)"
    fi
  fi
fi

echo "[phone-agent] dsh Android patch done."

# --- 3. shebang prefix rewrite -----------------------------------------------
# npm/pkg and other runtime scripts carry #!/data/data/com.termux/... shebangs
# from the Termux build; rewrite them to our prefix so `npm`/`pkg` work in the
# agent's shell.
COUNT=0
for f in "$USR/bin/"* "$USR/lib/node_modules/"*/bin/* "$USR/lib/node_modules/"*/libexec/*; do
  [ -f "$f" ] || continue
  if head -c 60 "$f" 2>/dev/null | grep -q "com.termux"; then
    sed -i "s|/data/data/com.termux/files/usr|$USR|g" "$f"
    COUNT=$((COUNT+1))
  fi
done
echo "[phone-agent] rewrote $COUNT shebangs to the app prefix."

# --- 4. ripgrep shim for @vscode/ripgrep -------------------------------------
# @vscode/ripgrep resolves @vscode/ripgrep-<platform>-<arch>/bin/rg; Node on
# Android reports platform 'android' and no such package exists on npm. The
# codex tarball ships a static musl rg that runs on Android — expose it under
# the path @vscode/ripgrep expects.
RG_SRC="$USR/../pkg/vendor/aarch64-unknown-linux-musl/codex-path/rg"
RGRG_DIR="$USR/lib/node_modules/@deepseek-ai/dsh/node_modules/@vscode/ripgrep-android-arm64"
if [ -x "$RG_SRC" ] && [ ! -f "$RGRG_DIR/bin/rg" ]; then
  mkdir -p "$RGRG_DIR/bin"
  cp "$RG_SRC" "$RGRG_DIR/bin/rg"
  chmod 755 "$RGRG_DIR/bin/rg"
  printf '%s\n' \
    '{"name":"@vscode/ripgrep-android-arm64","version":"1.18.0","main":"bin/rg"}' > "$RGRG_DIR/package.json"
  echo "[phone-agent] ripgrep shim installed (codex static rg)."
fi
