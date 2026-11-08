# Publish a new game update for the Android app.
# Usage (PowerShell, on your own PC):
#   .\publish-mobile.ps1 -Version 2 -File C:\game\mobile\index.html
# Version must be a whole number, bigger than the previous one (the APK ships with 1).
param(
  [Parameter(Mandatory=$true)][int]$Version,
  [Parameter(Mandatory=$true)][string]$File,
  [int]$MinShell = 7,
  [string]$Server = "root@217.18.90.60",
  [int]$Port = 3939
)
if (-not (Test-Path $File)) { Write-Error "File not found: $File"; exit 1 }
$hash = (Get-FileHash $File -Algorithm SHA256).Hash.ToLower()
$json = '{"version":' + $Version + ',"sha256":"' + $hash + '","minShell":' + $MinShell + '}'
$tmp = Join-Path $env:TEMP "version-mobile.json"
[IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
Write-Host "Uploading index.html (version $Version, sha256 $hash)..."
# index.html first, version.json last, so the app never sees a version without its file.
scp -P $Port $File "${Server}:/var/www/mobile/index.html"
if ($LASTEXITCODE -ne 0) { Write-Error "Upload of index.html failed"; exit 1 }
scp -P $Port $tmp "${Server}:/var/www/mobile/version.json"
if ($LASTEXITCODE -ne 0) { Write-Error "Upload of version.json failed"; exit 1 }
Write-Host "Done. Users get version $Version the next time they open the app."
