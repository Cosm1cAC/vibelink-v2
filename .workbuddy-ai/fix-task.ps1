# 管理员下运行：把 PcProxyFailover 的任务动作改成 wscript + pc-proxy-failover.vbs
# 用 Set-ScheduledTask（不走 schtasks /Change，后者会卡在“输入运行密码”提示）。
$ErrorActionPreference = 'Stop'

$log = Join-Path $PSScriptRoot 'fix-task.result.log'
$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$vbs = 'C:\Users\22771\bin\pc-proxy-failover.vbs'

try {
    if (-not (Test-Path -LiteralPath $vbs)) { throw "vbs not found: $vbs" }

    $before = (Get-ScheduledTask -TaskName 'PcProxyFailover').Actions |
        ForEach-Object { "$($_.Execute) $($_.Arguments)" }

    $action = New-ScheduledTaskAction -Execute $wscript -Argument ('"' + $vbs + '"')
    Set-ScheduledTask -TaskName 'PcProxyFailover' -Action $action | Out-Null

    $after = (Get-ScheduledTask -TaskName 'PcProxyFailover').Actions |
        ForEach-Object { "$($_.Execute) $($_.Arguments)" }

    $info = Get-ScheduledTask -TaskName 'PcProxyFailover' | Get-ScheduledTaskInfo

    $out = @(
        "OK $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        "before: $before"
        "after : $after"
        "state=$($info.LastRunTime) next=$($info.NextRunTime) result=$($info.LastTaskResult)"
    )
} catch {
    $out = @(
        "FAIL $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        $_.Exception.Message
    )
}
$out | Set-Content -LiteralPath $log -Encoding UTF8
