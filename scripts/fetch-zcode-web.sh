#!/usr/bin/env bash
# Builds the official ZCode web UI (packages/web) and its HTTP server
# (packages/server) from the pinned upstream ref, and stages them into
# app/src/main/assets/zcode-web/ and .../zcode-server/.
#
# Verified on-device: @zcode/server boots on the embedded Node (recognizes
# platform 'android') and serves the built SPA when ZCODE_WEB_STATIC_ROOT is
# set. Requires pnpm (corepack) and Node >= 24.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
source scripts/versions.env

ZCODE_REF="${ZCODE_REF:-$ZCODE_UPSTREAM_REF}"
WORK="$(mktemp -d)"
ASSETS="app/src/main/assets"
trap 'rm -rf "$WORK"' EXIT

echo "== cloning zai-org/ZCode @ $ZCODE_REF"
git clone --depth 1 --branch "$ZCODE_REF" https://github.com/zai-org/ZCode.git "$WORK/src" 2>&1 | tail -1

cd "$WORK/src"
corepack enable >/dev/null 2>&1 || true
echo "== installing workspace deps (pnpm $(corepack pnpm --version))"
corepack pnpm install --frozen-lockfile --ignore-scripts

echo "== building web SPA"
corepack pnpm --filter @zcode/web build

echo "== building http server"
corepack pnpm --filter @zcode/server build

echo "== staging web SPA"
mkdir -p "$REPO/$ASSETS/zcode-web"
cp -r packages/web/dist/. "$REPO/$ASSETS/zcode-web/"
du -sh "$REPO/$ASSETS/zcode-web"

echo "== staging server runtime (dist + portable npm deps)"
STAGE="$REPO/$ASSETS/zcode-server"
rm -rf "$STAGE"
mkdir -p "$STAGE/dist"
cp -r packages/server/dist/. "$STAGE/dist/"
# strip workspace:/platform-specific deps: workspace packages are inlined by
# the bundle, the @lydell pty packages are replaced by our android build
node -e '
const fs = require("fs");
const src = process.argv[1];
const p = JSON.parse(fs.readFileSync(src, "utf8"));
const d = Object.assign({}, p.dependencies || {});
Object.keys(d).forEach((k) => {
  if (k.includes("@lydell/") || String(d[k]).startsWith("workspace:")) delete d[k];
});
fs.writeFileSync(process.argv[2], JSON.stringify({ name: "zcode-server-runtime", version: "0.0.0", type: "module", dependencies: d }, null, 2));
' packages/server/package.json "$STAGE/package.json"
(cd "$STAGE" && npm install --no-audit --no-fund --ignore-scripts)
# android node-pty binding built by scripts/fetch-native.sh
mkdir -p "$STAGE/node_modules/node-pty/prebuilds/android-arm64"
cp "$REPO/$ASSETS/native/node-pty/pty.node" "$STAGE/node_modules/node-pty/prebuilds/android-arm64/pty.node"
du -sh "$STAGE"

# ship the server tree as one tarball: thousands of small npm files would
# otherwise bloat the APK asset index and slow asset loading
cd "$STAGE"
tar -czf "$STAGE/zcode-server.tgz" dist package.json node_modules
du -sh "$STAGE/zcode-server.tgz"

echo "== done"
