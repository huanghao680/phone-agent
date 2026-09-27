#!/usr/bin/env bash
# Stages the zcode-app-cli and @deepseek-ai/dsh tarballs into
# app/src/main/assets/packages/ so the app can install them offline on the
# device (their remaining registry dependencies are fetched at first run).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/versions.env

ASSETS="app/src/main/assets/packages"
mkdir -p "$ASSETS"

REGFLAGS=()
if [ -n "${NPM_REGISTRY:-}" ]; then
  REGFLAGS=(--registry "$NPM_REGISTRY")
fi

echo "== packing zcode-app-cli@$ZCODE_VERSION"
npm pack "zcode-app-cli@$ZCODE_VERSION" --pack-destination "$ASSETS" "${REGFLAGS[@]}"
echo "== packing @deepseek-ai/dsh@$DSH_VERSION"
npm pack "@deepseek-ai/dsh@$DSH_VERSION" --pack-destination "$ASSETS" "${REGFLAGS[@]}"

ls -lh "$ASSETS"
echo "== done"
