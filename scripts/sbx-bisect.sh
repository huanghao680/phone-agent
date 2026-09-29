#!/system/bin/sh
# dump + bisect the shim's proot argv
C=/data/user/0/com.phoneagent/cache
H=/data/user/0/com.phoneagent/files/home
U=$C/sbx-test/usr
export PATH=$U/bin:/system/bin
export LD_LIBRARY_PATH=$U/lib
export TMPDIR=$C/sbx-tmp
export HOME=$H

SKEL="$TMPDIR/bwrap-shim-root.$$"
rm -rf "$SKEL"; mkdir -p "$SKEL"
for _d in system vendor product apex odm system_ext linkerconfig; do
	[ -d "/$_d" ] && mkdir -p "$SKEL/$_d"
done
mkdir -p "$SKEL/dev" "$SKEL/proc" "$SKEL/sys" "$SKEL$U" "$SKEL$TMPDIR" "$SKEL/tmp" "$SKEL$H" "$TMPDIR/tmp"

P=$U/bin/proot
export PROOT_LOADER=$U/libexec/proot/loader
export PROOT_TMP_DIR=$TMPDIR/tmp

LOG=$TMPDIR/bisect.log
: > $LOG
t() {
	echo "--- $1" >> $LOG
	shift
	"$P" "$@" >> $LOG 2>&1
	echo "rc=$?" >> $LOG
}

t full -r "$SKEL" -b /system:/system -b /vendor:/vendor -b /product:/product -b /apex:/apex -b /odm:/odm -b /system_ext:/system_ext -b /linkerconfig:/linkerconfig -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$TMPDIR:$TMPDIR" -b "$TMPDIR/tmp:/tmp" -b "$H:$H" /system/bin/sh -c 'echo OK-full'
t no-tmpdir-bind -r "$SKEL" -b /system:/system -b /vendor:/vendor -b /product:/product -b /apex:/apex -b /odm:/odm -b /system_ext:/system_ext -b /linkerconfig:/linkerconfig -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$TMPDIR/tmp:/tmp" -b "$H:$H" /system/bin/sh -c 'echo OK-no-tmpdir'
t no-tmp-alias -r "$SKEL" -b /system:/system -b /vendor:/vendor -b /product:/product -b /apex:/apex -b /odm:/odm -b /system_ext:/system_ext -b /linkerconfig:/linkerconfig -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$U:$U" -b "$TMPDIR:$TMPDIR" -b "$H:$H" /system/bin/sh -c 'echo OK-no-alias'
t no-usrtmp-binds -r "$SKEL" -b /system:/system -b /vendor:/vendor -b /product:/product -b /apex:/apex -b /odm:/odm -b /system_ext:/system_ext -b /linkerconfig:/linkerconfig -b /dev:/dev -b /proc:/proc -b /sys:/sys -b "$H:$H" /system/bin/sh -c 'echo OK-min'
cat $LOG
rm -rf "$SKEL"
