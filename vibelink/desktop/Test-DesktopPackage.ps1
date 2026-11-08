[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$packageRoot=$PSScriptRoot
$manifest=Get-Content (Join-Path $packageRoot manifest.json) -Raw | ConvertFrom-Json
foreach ($file in $manifest.files) {
  if ((Get-FileHash -LiteralPath (Join-Path $packageRoot $file.path) -Algorithm SHA256).Hash -ne $file.sha256) { throw "Checksum failed: $($file.path)" }
}
Write-Host 'Package integrity verified.'
