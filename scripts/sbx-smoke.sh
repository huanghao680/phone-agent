#!/system/bin/sh
# smoke test for the bwrap shim on device (run under run-as com.phoneagent)
C=/data/user/0/com.phoneagent/cache
H=/data/user/0/com.phoneagent/files/home
U=$C/sbx-test/usr
export PATH=$U/bin:/system/bin
export LD_LIBRARY_PATH=$U/lib
export TMPDIR=$C/sbx-tmp
export HOME=$H

echo "=== 1 probe (read-only profile, cmd=true)"
time bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent -- true
echo "probe_rc=$?"

echo "=== 2 ws-write: write inside workspace"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'echo sandbox-ok > "$1/ws-ok.txt" && cat "$1/ws-ok.txt"' sh "$H"
echo "rc2=$?"

echo "=== 3 ws-write: write outside workspace (files root)"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'touch /data/user/0/com.phoneagent/files/EVIL.txt' sh x
echo "rc3=$? (expect nonzero)"

echo "=== 4 /sdcard visibility"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'ls /sdcard/' sh x 2>&1 | head -2

echo "=== 5 system read + /tmp write"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'ls /system/bin/sh >/dev/null && echo sys-read-ok; echo tmp-test > /tmp/x && cat /tmp/x' sh x
echo "rc5=$?"

echo "=== 6 HOME remap check (home == ws here, should stay)"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'echo "HOME=$HOME"' sh x

echo "=== done"
