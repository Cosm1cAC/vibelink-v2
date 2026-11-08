[CmdletBinding()]
param(
  [string]$SourceRoot = (Join-Path $env:LOCALAPPDATA 'vibelink-mesh\desktop\source'),
  [string]$Profile = 'vibelink',
  [ValidateSet('cn','global')][string]$Region = 'cn',
  [switch]$Mock
)
$ErrorActionPreference = 'Stop'
if ($Profile -notmatch '^[a-zA-Z0-9_-]{1,32}$') { throw 'Profile must be a short directory-safe name.' }
if (!(Test-Path -LiteralPath (Join-Path $SourceRoot 'apps/desktop/src/main/vibelink/embeddedServer.cjs'))) { throw 'Prepare the fused source before launching.' }
$previousMode = $env:CINDY_VIBELINK_EMBEDDED
$previousLinker = $env:PNPM_CONFIG_NODE_LINKER
try {
  $env:CINDY_VIBELINK_EMBEDDED = '1'
  # Current pnpm config get defaults to global config; Forge queries that key.
  # Match Cindy's checked-in hoisted layout for this child process only.
  $env:PNPM_CONFIG_NODE_LINKER = 'hoisted'
  $arguments = @('--dir', $SourceRoot, 'restart:desktop:remote', "--region=$Region", "--isolated=$Profile")
  if ($Mock) { $arguments += '--mock' }
  & pnpm @arguments
  if ($LASTEXITCODE) { throw 'Desktop startup failed; check DESKTOP_DEV_VERDICT above.' }
} finally {
  $env:CINDY_VIBELINK_EMBEDDED = $previousMode
  $env:PNPM_CONFIG_NODE_LINKER = $previousLinker
}
