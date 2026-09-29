#!/system/bin/sh
# Diagnose why the frozen skeleton blocks allowed writes.
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
H="$HOME"
C="$TMPDIR"
P="$U/bin/proot"
export PROOT_LOADER="$U/libexec/proot/loader" PROOT_TMP_DIR="$C/tmp"

mk() {  # $1 = skel dir, $2 = freeze(0/1)
  rm -rf "$1"; mkdir -p "$1"
  mkdir -p "$1/system" "$1/dev" "$1/proc" "$1/sys" "$1/tmp" "$1$U" "$1$H" "$1$C"
  [ "$2" = 1 ] && chmod -R a-w "$1"
  return 0
}

echo "=== A: skeleton writable, absolute path in ws"
mk "$C/dA" 0
"$P" -r "$C/dA" -b /system:/system -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$C/tmp:/tmp" -b "$H:$H" /system/bin/sh -c "touch $H/abs.txt && echo A-OK && rm -f $H/abs.txt" 2>&1 | head -2

echo "=== B: skeleton FROZEN, absolute path in ws"
mk "$C/dB" 1
"$P" -r "$C/dB" -b /system:/system -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$C/tmp:/tmp" -b "$H:$H" /system/bin/sh -c "touch $H/abs.txt && echo B-OK && rm -f $H/abs.txt" 2>&1 | head -2

echo "=== C: skeleton FROZEN, relative path (cwd=$H)"
mk "$C/dC" 1
cd "$H" || exit 1
"$P" -r "$C/dC" -b /system:/system -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$C/tmp:/tmp" -b "$H:$H" /system/bin/sh -c 'touch rel.txt && echo C-OK && rm -f rel.txt' 2>&1 | head -2

echo "=== D: skeleton FROZEN except mount points, absolute path"
mk "$C/dD" 1
chmod 755 "$C/dD$H" "$C/dD/tmp" 2>/dev/null
"$P" -r "$C/dD" -b /system:/system -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$C/tmp:/tmp" -b "$H:$H" /system/bin/sh -c "touch $H/abs.txt && echo D-OK && rm -f $H/abs.txt" 2>&1 | head -2
rm -rf "$C/dA" "$C/dB" "$C/dC" "$C/dD"
