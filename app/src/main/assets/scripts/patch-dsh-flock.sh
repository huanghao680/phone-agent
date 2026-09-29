#!/system/bin/sh
# Entry point for the dsh Android compatibility patches.
#
# The patch logic lives in patch-dsh-android.mjs next to this file: exact
# string anchors with idempotency markers, which is far less brittle than
# rewriting bundled JavaScript with sed. See that file for the patch list
# (flock stub, link->rename fallback, sandbox platform chain, attachment dir
# fsync tolerance, ripgrep shim, shebang prefixes).

USR="/data/user/0/com.phoneagent/files/usr"
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JS="$HERE/patch-dsh-android.mjs"

if [ ! -f "$JS" ]; then
  echo "[phone-agent] WARN: $JS missing, dsh patches skipped"
  exit 0
fi

LD_LIBRARY_PATH="$USR/lib" "$USR/bin/node" "$JS" "$USR"
