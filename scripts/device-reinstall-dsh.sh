#!/system/bin/sh
# Reinstall the packaged dsh on device (run-as com.phoneagent).
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
export npm_config_registry=https://mirrors.cloud.tencent.com/npm/
cd "$HOME" || exit 1
echo "== reinstalling dsh $(date)" > "$TMPDIR/dsh-reinstall.log"
"$U/bin/node" "$U/lib/node_modules/npm/bin/npm-cli.js" install -g --prefix "$U" \
  --ignore-scripts "file:/data/user/0/com.phoneagent/files/pkg/deepseek-ai-dsh-0.1.7-rc.2.tgz" \
  >> "$TMPDIR/dsh-reinstall.log" 2>&1
echo "EXIT=$?" >> "$TMPDIR/dsh-reinstall.log"
