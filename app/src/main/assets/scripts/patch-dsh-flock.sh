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
