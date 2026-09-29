#!/system/bin/sh
# Full self-test driven by the dsh CLI on this device, under the new
# workspace-write sandbox. Writes a report into the workspace and echoes the
# tail of the run to the console.
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

PROMPT=$(cat <<'EOF'
Run a COMPLETE self-test of this Android dsh installation and write the report to
selftest/DSH-android-selftest-v3.md (create the directory if needed). Work
autonomously; do not ask me anything. Be rigorous: every claim needs a command
and its real observed output. Mark anything you could not verify as unverified.

Context: this is a phone-agent APK port. The session runs under the NEW
workspace-write sandbox (proot backend on this kernel). Denials outside the
workspace are EXPECTED results to report as evidence, not failures, and you
should not retry them with wider permissions.

Cover at least:

1. Environment header: dsh --version, node -v, uname -a, process.platform,
   workspace path, DSH_PERMISSION_MODE, and the shell that `bash` uses.

2. Tool matrix — exercise every tool available to you and give the exact
   command plus observed result for each:
   bash (including exit-code reporting and background jobs via job_output /
   job_list / job_kill), read, write, edit, glob, grep, todo_write,
   read_image (create a small PNG with node or bash first), web_search,
   web_fetch, subagent, workflow, skill, create_goal/get_goal.
   Note any tool that is missing or unusable.

3. Android-specific fixes that this port is supposed to provide — verify each
   and report pass/fail with evidence:
   a) glob/grep work at all (they need a static ripgrep behind
      @vscode/ripgrep-android-arm64)
   b) read_image can save an attachment (previously failed with
      EACCES open /data/user/0)
   c) npm / npx / dsh / pkg run (previously "bad interpreter" from stale
      Termux shebangs), and curl can reach HTTPS (CA bundle relocation)
   d) session persistence writes a session log (previously link() EACCES)

4. Sandbox behaviour under workspace-write — report the observed outcome of:
   a) writing and reading a file INSIDE the workspace
   b) writing OUTSIDE the workspace (e.g. $HOME/../outside.txt), including
      whether dsh appends a sandbox denial marker to the tool result
   c) reading a path outside the workspace (systems paths still readable?)
   d) ls /sdcard/ (should not be visible)
   e) writing to /tmp and reading a system binary
   State plainly which operations were allowed and which were denied.

5. Anything that is broken, surprising, or a remaining Android gap — with the
   exact evidence, and your recommendation.

Finish by writing the report file, then reply with a 10-line summary of the
verdict (how many tools verified, how many broken, sandbox verdict, and the
top remaining issues).
EOF
)

echo "== self-test started $(date)" > "$TMPDIR/selftest-v3.log"
dsh --profile headless "$PROMPT" >> "$TMPDIR/selftest-v3.log" 2>&1
echo "EXIT=$?" >> "$TMPDIR/selftest-v3.log"
