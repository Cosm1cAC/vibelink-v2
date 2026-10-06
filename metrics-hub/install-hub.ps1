# 在主 Windows 主机上以普通权限运行：注册登录自启任务并立即启动 Hub。
# 防火墙放行（管理员，仅一次）见脚本末尾输出。

$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'metrics-hub.ps1'
$ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$tr = '"' + $ps + '" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $scriptPath + '" -Mode hub'

schtasks /Create /TN MetricsHub /TR $tr /SC ONLOGON /F | Out-Null
Start-Process -FilePath $ps -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden',
    '-File', $scriptPath, '-Mode', 'hub'
) -WindowStyle Hidden

Write-Host '[OK] MetricsHub 已注册（登录自启）并启动'
Write-Host ''
Write-Host '若第二台 Windows / 手机需要上报，还需一次管理员操作（UAC）放行 tailnet 网段：'
Write-Host '  New-NetFirewallRule -DisplayName "wproxy-metrics-hub" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18993 -RemoteAddress 100.64.0.0/10'
Write-Host ''
Write-Host '卸载: schtasks /Delete /TN MetricsHub /F; Remove-NetFirewallRule -DisplayName "wproxy-metrics-hub"'
