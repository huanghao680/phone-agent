#!/system/bin/sh
# Sandbox end-to-end check: run a real dsh headless session under the
# workspace-write policy and report what bash is allowed to do.
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin:/vendor/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
export TERM=xterm-256color LANG=en_US.UTF-8
export DSH_PERMISSION_MODE=workspace-write
export OPENSSL_CONF="$U/etc/tls/openssl.cnf"
export CURL_CA_BUNDLE="$U/etc/tls/cert.pem"
export SSL_CERT_FILE="$U/etc/tls/cert.pem"
export npm_config_registry=https://mirrors.cloud.tencent.com/npm/
cd "$HOME" || exit 1
echo "== headless sandbox run $(date)" > "$TMPDIR/sbx-e2e.log"
dsh --profile headless \
  'Run exactly this single bash command and report its stdout verbatim, no commentary: echo WS-OK > sbx-test.txt && cat sbx-test.txt; touch /data/user/0/com.phoneagent/files/SBX-ESCAPE.txt; echo escape-rc=$?; ls /sdcard/ 2>&1 | head -1' \
  >> "$TMPDIR/sbx-e2e.log" 2>&1
echo "EXIT=$?" >> "$TMPDIR/sbx-e2e.log"
