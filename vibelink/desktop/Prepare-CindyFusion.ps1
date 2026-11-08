[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$CindyRepository,
  [string]$Destination = (Join-Path $env:LOCALAPPDATA 'vibelink-mesh\desktop\source')
)
$ErrorActionPreference = 'Stop'
$packageRoot = $PSScriptRoot
$manifest = Get-Content (Join-Path $packageRoot 'manifest.json') -Raw | ConvertFrom-Json
foreach ($file in $manifest.files) {
  $full = [IO.Path]::GetFullPath((Join-Path $packageRoot $file.path))
  if (!$full.StartsWith(([IO.Path]::GetFullPath($packageRoot) + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid package path' }
  if ((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash -ne $file.sha256) { throw "Package checksum failed: $($file.path)" }
}
if (Test-Path -LiteralPath $Destination) { throw 'Destination already exists. Choose a new empty worktree path; existing source/data will not be overwritten.' }
& git -C $CindyRepository cat-file -e "$($manifest.commit)^{commit}"
if ($LASTEXITCODE) { throw 'The pinned upstream commit is unavailable in this local Cindy repository.' }
& git -C $CindyRepository worktree add --detach $Destination $manifest.commit
if ($LASTEXITCODE) { throw 'Cannot create isolated Cindy worktree.' }
& git -C $Destination apply --check (Join-Path $packageRoot 'cindy-fusion.patch')
if ($LASTEXITCODE) { throw 'Integration patch does not apply; isolated worktree retained for inspection.' }
& git -C $Destination apply (Join-Path $packageRoot 'cindy-fusion.patch')
if ($LASTEXITCODE) { throw 'Integration patch failed.' }
foreach ($file in $manifest.files) {
  if (!$file.path.StartsWith('overlay/')) { continue }
  $relative = $file.path.Substring(8)
  $target = Join-Path $Destination $relative
  New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $packageRoot $file.path) -Destination $target
}
Write-Host "Prepared VibeLink desktop source at $Destination"
Write-Host 'Run pnpm install --frozen-lockfile there, then Start-VibeLinkDesktop.ps1 -SourceRoot <path>.'
