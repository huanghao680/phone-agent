#!/usr/bin/env bash
# Stages the embedded Node.js runtime into app/src/main/assets/runtime.tar.xz.
#
# Downloads the pinned nodejs package plus its full dependency closure from the
# Termux package repository (standard Android arm64 ELF binaries — the app has
# no dependency on the Termux application), verifies SHA256 checksums against
# the repository index, assembles a single /usr prefix tree, prunes build-time
# files, and packs it as a tar.xz the app extracts at first run.
#
# Requires: bash 4+, curl, xz, dpkg-deb (any Linux/WSL). Run in CI and locally
# before `gradle assembleDebug`.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/versions.env

REPO="${TERMUX_REPO:-$TERMUX_REPO}"
ARCH=aarch64
WORK="$(mktemp -d)"
STAGE="$WORK/stage"
ASSETS="app/src/main/assets"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$STAGE" "$ASSETS"

echo "== fetching Packages index from $REPO"
IDX="$WORK/Packages"
if curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages.xz" -o "$WORK/Packages.xz"; then
  xz -dc "$WORK/Packages.xz" > "$IDX"
else
  curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$IDX"
fi

# name|filename|sha256|depends per line
declare -A P_FILE P_SHA P_DEPS
while IFS='|' read -r name file sha deps; do
  P_FILE[$name]="$file"
  P_SHA[$name]="$sha"
  P_DEPS[$name]="$deps"
done < <(awk '
  /^$/ {if (name != "") print name "|" file "|" sha "|" deps; name=file=sha=deps=""; next}
  /^Package: /   {name=$2}
  /^Filename: /  {file=$2}
  /^SHA256: /    {sha=$2}
  /^Depends: /   {deps=substr($0, 10)}
  END {if (name != "") print name "|" file "|" sha "|" deps}
' "$IDX")

RESOLVED=()
need() {
  local pkg="$1"
  [[ -n "${P_FILE[$pkg]:-}" ]] || { echo "!! package not in index: $pkg" >&2; return 1; }
  local r
  for r in "${RESOLVED[@]:-}"; do [ "$r" = "$pkg" ] && return 0; done
  RESOLVED+=("$pkg")
  echo "   + $pkg"
  local deps="${P_DEPS[$pkg]:-}" grp alt clean
  [ -z "$deps" ] && return 0
  while IFS= read -r grp; do
    [ -z "$grp" ] && continue
    while IFS= read -r alt; do
      clean="$(echo "$alt" | sed 's/ *([^)]*)//g' | xargs)"
      [ -z "$clean" ] && continue
      if [[ -n "${P_FILE[$clean]:-}" ]]; then need "$clean"; break; fi
    done <<< "$grp"
  done < <(echo "$deps" | tr ',' '\n')
}

echo "== resolving dependency closure for nodejs + npm"
need nodejs
need npm

echo "== downloading and unpacking ${#RESOLVED[@]} packages"
for pkg in "${RESOLVED[@]}"; do
  url="$REPO/${P_FILE[$pkg]}"
  deb="$WORK/$pkg.deb"
  curl -fsSL "$url" -o "$deb"
  echo "${P_SHA[$pkg]}  $deb" | sha256sum -c - >/dev/null
  echo "   ok $pkg ($(du -h "$deb" | cut -f1))"
  OUT="$WORK/out-$pkg"
  dpkg-deb -x "$deb" "$OUT"
  cp -a "$OUT/data/data/com.termux/files/usr/." "$STAGE/usr/"
done

echo "== pruning build-time files"
rm -rf "$STAGE/usr/share/man" "$STAGE/usr/share/doc" "$STAGE/usr/include" "$STAGE/usr/lib/pkgconfig"
find "$STAGE/usr" -name "*.a" -delete 2>/dev/null || true
find "$STAGE/usr" -name "*.la" -delete 2>/dev/null || true

echo "== packing runtime.tar.xz"
tar -cJf "$ASSETS/runtime.tar.xz" -C "$STAGE" usr
echo "$REPO nodejs-$NODE_VERSION $(date -u +%Y%m%d)" > "$ASSETS/runtime.version"
du -h "$ASSETS/runtime.tar.xz"

echo "== done: $ASSETS/runtime.tar.xz"
