#!/system/bin/sh
# Focused re-verification of the three fixes: workflow (sandbox in a sanitized
# env), read_image (sharp wasm fallback), git over HTTPS.
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin:/vendor/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
export TERM=xterm-256color LANG=en_US.UTF-8
export DSH_PERMISSION_MODE=workspace-write
export SHELL="$U/bin/bash"
export GIT_EXEC_PATH="$U/libexec/git-core"
export GIT_TEMPLATE_DIR="$U/share/git-core/templates"
export OPENSSL_CONF="$U/etc/tls/openssl.cnf"
export CURL_CA_BUNDLE="$U/etc/tls/cert.pem"
export SSL_CERT_FILE="$U/etc/tls/cert.pem"
cd "$HOME" || exit 1

PROMPT=$(cat <<'EOF'
Targeted Android re-verification. Run these three checks and report each one's
result plainly, with the exact command and its real output. Do not investigate
anything else, do not ask questions.

1) SANDBOX (was broken: the bwrap shim could not run in a sanitized environment):
   run `bash -c 'echo sandbox-ok && touch /data/user/0/com.phoneagent/files/SBX2.txt; echo escape=$?'`
   Expected now: the echo succeeds and the outside write is denied. Report what
   you observed, including any sandbox denial marker dsh appended.

2) READ_IMAGE (was broken: sharp had no android binary and no wasm fallback):
   create a small PNG with node (or reuse selftest/gradient.png if present) and
   call the read_image tool on it. Report success or the exact error.

3) GIT OVER HTTPS:
   `git ls-remote https://github.com/huanghao680/phone-agent.git HEAD` and then
   `git clone --depth 1 https://github.com/huanghao680/phone-agent.git /tmp/gitclone-probe`
   (if /tmp is not writable use $TMPDIR/gitclone-probe). Report whether the
   clone produced files, and any error verbatim.

Finish with a 4-line summary: sandbox = ?, read_image = ?, git = ?, anything
still broken.
EOF
)

echo "== focused re-verify $(date)" > "$TMPDIR/reverify.log"
dsh --profile headless "$PROMPT" >> "$TMPDIR/reverify.log" 2>&1
echo "EXIT=$?" >> "$TMPDIR/reverify.log"
