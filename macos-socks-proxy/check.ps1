$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')

Write-Output '=== Scheduled task ==='
Get-ScheduledTask -TaskName 'MacSocksProxy' -ErrorAction SilentlyContinue | Select-Object TaskName, State | Format-Table -AutoSize

Write-Output '=== Watchdog process ==='
Get-CimInstance Win32_Process -Filter "Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*ssh-proxy.ps1*' } |
    Select-Object ProcessId | Format-Table -AutoSize

Write-Output '=== SSH tunnel process ==='
Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($SshTarget) } |
    Select-Object ProcessId | Format-Table -AutoSize

Write-Output '=== SOCKS port ==='
Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize

Write-Output '=== Egress check ==='
& (Join-Path $env:SystemRoot 'System32\curl.exe') -s --max-time 20 --socks5-hostname ('127.0.0.1:{0}' -f $LocalPort) https://api.ipify.org
Write-Output ''

Write-Output '=== Recent log ==='
$log = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'logs') -Filter '*.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($log) { Get-Content -LiteralPath $log.FullName -Tail 15 } else { Write-Output '(no logs yet)' }
