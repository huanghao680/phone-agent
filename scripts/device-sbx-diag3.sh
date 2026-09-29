#!/system/bin/sh
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
H="$HOME"
cd "$H" || exit 1

BEFORE=$(ls -d "$TMPDIR"/bwrap-shim-root.* 2>/dev/null | wc -l)
echo "skeletons before: $BEFORE"

echo "=== escape attempt via shim"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'touch /data/user/0/com.phoneagent/files/SBX-ESCAPE.txt; echo rc=$?'
echo "== perms of the newest skeleton chain"
NEW=$(ls -dt "$TMPDIR"/bwrap-shim-root.* 2>/dev/null | head -1)
echo "newest=$NEW"
if [ -n "$NEW" ]; then
  ls -ld "$NEW" "$NEW/data" "$NEW/data/user" "$NEW/data/user/0" "$NEW/data/user/0/com.phoneagent" "$NEW/data/user/0/com.phoneagent/files" 2>&1
  find "$NEW" -name 'SBX-ESCAPE.txt' 2>/dev/null | head -2
fi
AFTER=$(ls -d "$TMPDIR"/bwrap-shim-root.* 2>/dev/null | wc -l)
echo "skeletons after: $AFTER"
