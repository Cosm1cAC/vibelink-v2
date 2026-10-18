# 在主 Windows 主机上运行：注册/更新登录自启任务，并（未运行时）立即启动 Hub。
# 防火墙放行（管理员，仅一次）见脚本末尾输出。
#
# 任务动作走 wscript + hub-start.vbs（窗口样式 0）：
#   Task Scheduler 直接执行 powershell.exe -WindowStyle Hidden 时，每次启动都会新建
#   conhost，黑窗一闪；Hub 被 hub-watchdog 重启时同样会闪。改由 wscript（GUI 程序、
#   不分配控制台）以窗口样式 0 拉起即可彻底静默（与 hub-watchdog.vbs 同一做法）。
# 任务已存在时更新动作需要管理员，本脚本会自动请求一次 UAC。

param([switch]$RegisterTaskOnly)

$ErrorActionPreference = 'Stop'
$vbs = Join-Path $PSScriptRoot 'hub-start.vbs'
$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$argument = '"' + $vbs + '"'
$tr = '"' + $wscript + '" ' + $argument
$port = 18993

function Register-HubTask {
    if (Get-ScheduledTask -TaskName 'MetricsHub' -ErrorAction SilentlyContinue) {
        Set-ScheduledTask -TaskName 'MetricsHub' -Action (New-ScheduledTaskAction -Execute $wscript -Argument $argument) -ErrorAction Stop | Out-Null
        return
    }
    schtasks /Create /TN MetricsHub /TR $tr /SC ONLOGON /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw ('schtasks /Create 失败（exit {0}）' -f $LASTEXITCODE) }
}

if ($RegisterTaskOnly) {
    Register-HubTask
    Write-Host '[OK] MetricsHub 任务动作已更新为 wscript 静默启动'
    exit 0
}

try {
    Register-HubTask
    Write-Host '[OK] MetricsHub 已注册（登录自启，wscript 静默启动）'
} catch {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($isAdmin) { throw }
    Write-Host '[..] 更新任务动作需要管理员，正在请求 UAC ...'
    $elev = Start-Process -FilePath (Get-Process -Id $PID).Path -Verb RunAs -Wait -PassThru -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-RegisterTaskOnly'
    )
    if ($elev.ExitCode -ne 0) { throw ('提权注册失败（exit {0}）' -f $elev.ExitCode) }
}

# 仅在未运行时启动，避免与已有实例抢 18993（抢不到会进重试循环，留下僵尸进程）
if (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -gt 0) {
    Write-Host ('[SKIP] Hub 已在运行（0.0.0.0:{0} 已监听），不重复启动' -f $port)
} else {
    Start-Process -FilePath $wscript -ArgumentList $argument
    Write-Host '[OK] Hub 已静默启动'
}

Write-Host ''
Write-Host '若第二台 Windows / 手机需要上报，还需一次管理员操作（UAC）放行 tailnet 网段：'
Write-Host '  New-NetFirewallRule -DisplayName "wproxy-metrics-hub" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18993 -RemoteAddress 100.64.0.0/10'
Write-Host ''
Write-Host '卸载: schtasks /Delete /TN MetricsHub /F; Remove-NetFirewallRule -DisplayName "wproxy-metrics-hub"'