$WproxyDir = $PSScriptRoot
$MixedPort = 7897
$ApiPort = 9097
$ApiSecret = '[REMOVED_CREDENTIAL]'
$ApiBase = "http://127.0.0.1:$ApiPort"
$TaskName = 'WProxy'
$MihomoExe = Join-Path $WproxyDir 'mihomo.exe'
$PwshExe = Join-Path $PSHOME 'pwsh.exe'
