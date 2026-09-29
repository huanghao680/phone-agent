#!/system/bin/sh
# Sandbox policy matrix: workspace write, outside write, /sdcard, /tmp, system read.
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
H="$HOME"
cd "$H" || exit 1
OUT=/data/user/0/com.phoneagent/files/SBX-ESCAPE.txt

echo "=== probe (read-only profile)"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent -- true
echo "probe_rc=$?"

echo "=== ws allowed: write inside workspace"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'echo ok > ws-ok.txt && cat ws-ok.txt && rm -f ws-ok.txt'
echo "rc=$?"

echo "=== ws denied: write outside workspace"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c "touch $OUT"
echo "rc=$? (expect nonzero)"
ls "$OUT" 2>&1 | head -1

echo "=== ws denied: write to /data/user/0 root"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'touch /data/user/0/evil.txt'
echo "rc=$?"

echo "=== denied: /sdcard visibility"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'ls /sdcard/' 2>&1 | head -1
echo "rc=$?"

echo "=== allowed: /tmp write + system read"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'echo t > /tmp/x && cat /tmp/x; ls /system/bin/sh >/dev/null && echo sys-read-ok'
echo "rc=$?"
