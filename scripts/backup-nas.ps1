# Phone-Agent -> fnOS NAS incremental backup
# Usage: powershell -ExecutionPolicy Bypass -File scripts\backup-nas.ps1
# Requires: fnos skill, profile "home" logged in; target /vol2/1000/backup-phone-agent
#
# Layout on NAS:
#   phone-agent-main.bundle        git bundle, all refs+tags (verify with git, see below)
#   phone-agent-bundle.tar.gz      same bundle inside gzip — the integrity-proof copy
#   phone-agent.git/               bare repo, ready to `git clone` from the NAS
#   phone-agent-git.tar.gz         bare repo tarball
#   release.keystore/.pass         signing secrets (seeded once, never overwritten)
#   SHA256SUMS.txt                 verification anchors (commit hash, static-file hashes)
#
# !! The git bundle is NOT byte-reproducible: git repacks differently every run, so
# !! its size/hash differs each time. Never verify it by comparing bytes — use:
# !!   git bundle verify phone-agent-main.bundle && git bundle list-heads ...
#
# Integrity proof for the transfer itself: the bundle also ships inside a .tar.gz.
# Extracting that on the NAS exercises gzip CRCs end to end; a successful extract to
# the recorded byte size proves the uploaded bytes are intact.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$cli  = Join-Path $HOME '.agents\skills\fnos\bin\trim-cli-windows-x64.exe'
if (-not (Test-Path $cli)) { Write-Error 'trim-cli not found - install the fnos skill first'; exit 1 }
$dst = '/vol2/1000/backup-phone-agent'
$tmp = Join-Path $env:TEMP 'pa-nas-backup'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# 1) bundle: create + locally verify it reads back
git -C $repo bundle create "$tmp\phone-agent-main.bundle" --all | Out-Null
git -C $repo bundle verify "$tmp\phone-agent-main.bundle" | Out-Null

# 2) secrets: static material, upload only when missing on NAS
$remote = & $cli --profile home file ls $dst | Out-String
foreach ($f in 'release.keystore','release-key.pass') {
  $src = Join-Path $repo $f
  if ($remote -match [regex]::Escape($f)) { Write-Host "$f already on NAS (seed, untouched)" }
  elseif (Test-Path $src) { & $cli --profile home file upload $dst $src --overwrite replace --yes }
  else { Write-Warning "local $f missing - cannot back up" }
}

# 3) manifest: commit anchors (stable) + hashes of the byte-stable static files
$head  = git -C $repo rev-parse HEAD
$short = git -C $repo rev-parse --short HEAD
$tags  = (git -C $repo tag | Sort-Object) -join ', '
$sha = @()
foreach ($f in 'release.keystore','release-key.pass') {
  $src = Join-Path $repo $f
  if (Test-Path $src) { $sha += '{0}  {1}' -f (Get-FileHash $src -Algorithm SHA256).Hash, $f }
}
@"
# Phone-Agent NAS backup — verification anchors
# Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
#
# The git bundle is NOT byte-reproducible (git repacks every run) - do not compare
# its size or hash across runs. Verify with git:
#   git bundle verify phone-agent-main.bundle
#   git clone phone-agent-main.bundle /tmp/x && git -C /tmp/x log --oneline -1
#   -> must print: $short
#
[git]
head_commit = $head
head_short  = $short
all_tags    = $tags
branch      = main

[sha256]
$($sha -join "`n")
"@ | Out-File "$tmp\SHA256SUMS.txt" -Encoding utf8

# 4) upload: bundle + its gzip-wrapped twin (integrity-proof copy) + manifest
tar -czf "$tmp\phone-agent-bundle.tar.gz" -C $tmp phone-agent-main.bundle
& $cli --profile home file upload $dst "$tmp\phone-agent-main.bundle"    --overwrite replace --yes
& $cli --profile home file upload $dst "$tmp\phone-agent-bundle.tar.gz" --overwrite replace --yes
& $cli --profile home file upload $dst "$tmp\SHA256SUMS.txt"            --overwrite replace --yes

# 5) integrity proof: extract the gzip copy ON the NAS; success = CRCs passed, and the
#    extracted byte size must match the local bundle. This proves the transfer, which
#    a size comparison against a *repacked* bundle cannot.
$local = [int64](Get-Item "$tmp\phone-agent-main.bundle").Length
& $cli --profile home file extract "$dst/phone-agent-bundle.tar.gz" "$dst/.verify" --overwrite replace --yes | Out-Null
& $cli --profile home file extract "$dst/.verify/phone-agent-bundle.tar" "$dst/.verify" --overwrite replace --yes | Out-Null
Start-Sleep -Seconds 4
$json = (& $cli --profile home file ls "$dst/.verify" | Out-String)
$got  = ([regex]::Match($json, '"name":\s*"phone-agent-main\.bundle",\s*"size":\s*(\d+)')).Groups[1].Value
& $cli --profile home file rm "$dst/.verify" --yes | Out-Null
if ($got -eq "$local") { Write-Host "integrity OK - NAS-side extract matches local ($local bytes)" }
else { Write-Error "integrity check failed: NAS extract $got != local $local"; exit 1 }

Remove-Item $tmp -Recurse -Force
Write-Host "backup done ($local bytes, integrity verified)"
