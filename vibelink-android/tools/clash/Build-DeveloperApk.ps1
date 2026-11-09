[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$PrivateConfiguration,
    [string]$BuildCommit = ''
)
$ErrorActionPreference = 'Stop'
$configuration = (Resolve-Path -LiteralPath $PrivateConfiguration).Path
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$arguments = @(':app:assembleRelease', '-PbetaEdition=false', "-PdevClashConfig=$configuration", '-Pkotlin.daemon.jvmargs=-Xmx4g', '--max-workers=1', '--no-daemon')
if ($BuildCommit) { $arguments += "-PbuildCommit=$BuildCommit" }
Push-Location $androidRoot
try {
    & ./gradlew.bat @arguments
    if ($LASTEXITCODE) { throw 'Developer APK build failed.' }
    Write-Host 'Developer APK built locally. Private configuration must only be distributed through the private developer channel.'
} finally { Pop-Location }
