#!/system/bin/sh
# Plugin-system verification via the dsh CLI itself: use the plugin_manager
# tool in a danger-full-access headless session to inspect + install one
# appearance plugin and one balance plugin, then verify they load.
U=/data/user/0/com.phoneagent/files/usr
export PATH="$U/bin:/system/bin:/vendor/bin"
export LD_LIBRARY_PATH="$U/lib"
export TMPDIR=/data/user/0/com.phoneagent/cache
export HOME=/data/user/0/com.phoneagent/files/home
export TERM=xterm-256color LANG=en_US.UTF-8
export DSH_PERMISSION_MODE=danger-full-access
export SHELL="$U/bin/bash"
export GIT_EXEC_PATH="$U/libexec/git-core"
export GIT_TEMPLATE_DIR="$U/share/git-core/templates"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=http.sslCAInfo GIT_CONFIG_VALUE_0="$U/etc/tls/cert.pem"
export OPENSSL_CONF="$U/etc/tls/openssl.cnf"
export CURL_CA_BUNDLE="$U/etc/tls/cert.pem"
export SSL_CERT_FILE="$U/etc/tls/cert.pem"
export npm_config_registry=https://mirrors.cloud.tencent.com/npm/
cd "$HOME" || exit 1

PROMPT=$(cat <<'EOF'
Verify the dsh plugin system end to end using the plugin_manager tool ONLY for
its intended operations. Do exactly this sequence and nothing else:

1. listBundles — record how many bundles are loaded and whether any show an
   error right now (baseline).
2. inspect the install spec "dsh-balance" — record the answer (name, version,
   whether it declares a bundle, registry that answered, or problem).
3. installBundle "dsh-balance" — record the outcome (applied or failedAt +
   failure kind + the last ~10 lines of pnpm output if it failed).
4. listBundles again — is dsh-balance present, enabled, and WITHOUT an error?
5. inspect and installBundle "@eternalnight/dsh-theme" the same way, then
   listBundles again for it.
6. For each installed bundle, report whether its declared plugin rows appear
   (names only).

Do not use bash to hand-edit the profile files; the manager must do the work.
If an install fails, report the failure verbatim and move on to the next step.
Finish with a 6-line summary: baseline bundles, dsh-balance install result +
load state, theme install result + load state, total pnpm runs observed in
install-log output, any error text verbatim, and your verdict on whether the
plugin system works.
EOF
)

echo "== plugin e2e $(date)" > "$TMPDIR/plugin-e2e.log"
dsh --profile headless "$PROMPT" >> "$TMPDIR/plugin-e2e.log" 2>&1
echo "EXIT=$?" >> "$TMPDIR/plugin-e2e.log"
