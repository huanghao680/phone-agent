#!/system/bin/sh
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
H="$HOME"
cd "$H" || exit 1

echo "=== guest view"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'pwd; ls -ld . ; ls -ld '"$H"' ; id' 2>&1 | head -6

echo "=== absolute path write"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c "touch $H/abs.txt && echo ABS-OK && rm -f $H/abs.txt" 2>&1 | head -2

echo "=== relative path write"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'touch rel.txt && echo REL-OK && rm -f rel.txt' 2>&1 | head -2

echo "=== explicit -w cwd"
bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent --tmpfs /tmp --bind "$H" "$H" -- /system/bin/sh -c 'touch rel2.txt && echo REL2-OK && rm -f rel2.txt' 2>&1 | head -2

echo "=== host check: is H writable?"
touch "$H/host-probe.txt" && echo HOST-WRITABLE && rm -f "$H/host-probe.txt"
ls -ld "$H" "$TMPDIR"
