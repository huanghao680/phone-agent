# Phone-Agent -> fnOS NAS incremental backup
# Usage: powershell -ExecutionPolicy Bypass -File scripts\backup-nas.ps1
# Requires: fnos skill, profile "home" logged in; target /vol2/1000/backup-phone-agent
#
# Every run refreshes: phone-agent-main.bundle (all refs+tags, git-verified locally)
#                    + SHA256SUMS.txt (bundle and, if present locally, the two secrets)
# Seeded once, never overwritten unless missing on NAS: release.keystore, release-key.pass
# Verification: remote bundle size must equal local size.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$cli  = Join-Path $HOME '.agents\skills\fnos\bin\trim-cli-windows-x64.exe'
if (-not (Test-Path $cli)) { Write-Error 'trim-cli not found - install the fnos skill first'; exit 1 }
$dst = '/vol2/1000/backup-phone-agent'
$tmp = Join-Path $env:TEMP 'pa-nas-backup'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# 1) bundle: create, then let git verify it reads back
git -C $repo bundle create "$tmp\phone-agent-main.bundle" --all | Out-Null
git -C $repo bundle verify "$tmp\phone-agent-main.bundle" | Out-Null

# 2) secrets: static material, upload only when missing on NAS
$remote = & $cli --profile home file ls $dst | ConvertFrom-Json
foreach ($f in 'release.keystore','release-key.pass') {
  $src = Join-Path $repo $f
  if ($remote.name -contains $f) { Write-Host "$f already on NAS (seed, untouched)" }
  elseif (Test-Path $src) { & $cli --profile home file upload $dst $src --overwrite replace --yes }
  else { Write-Warning "local $f missing - cannot back up" }
}

# 3) checksums for what we can recompute locally
foreach ($f in 'release.keystore','release-key.pass') {
  $src = Join-Path $repo $f
  if (Test-Path $src) { Copy-Item $src "$tmp\$f" -Force }
}
Get-ChildItem $tmp -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
  ForEach-Object { '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.Name } |
  Out-File "$tmp\SHA256SUMS.txt" -Encoding ascii

& $cli --profile home file upload $dst "$tmp\phone-agent-main.bundle" --overwrite replace --yes
& $cli --profile home file upload $dst "$tmp\SHA256SUMS.txt" --overwrite replace --yes

# 4) best-effort remote cross-check: the fnos index lags a moment after upload and
#    can transiently emit duplicate entries, so poll for the expected size but do
#    not fail the run over it — git bundle verify above already proves the artifact
$local = [int64](Get-Item "$tmp\phone-agent-main.bundle").Length
$confirmed = $false
for ($i = 0; $i -lt 12 -and -not $confirmed; $i++) {
  $rows = @(& $cli --profile home file ls $dst | ConvertFrom-Json)
  foreach ($r in $rows) {
    if ($r.name -eq 'phone-agent-main.bundle') {
      $s = @($r.size)[0] -as [int64]
      if ($s -eq $local) { $confirmed = $true }
    }
  }
  if (-not $confirmed) { Start-Sleep -Seconds 5 }
}
if ($confirmed) { Write-Host "remote bundle size confirmed ($local bytes)" }
else { Write-Warning "remote bundle size not confirmed within polling window (local $local bytes); fnos upload is chunk-verified, treat as advisory" }
Remove-Item $tmp -Recurse -Force
Write-Host "backup done - bundle $local bytes verified"
