param(
    [ValidateSet('sysproxy', 'tun')]
    [string]$Mode = 'sysproxy'
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')
$cli = Join-Path $PSScriptRoot 'wproxy.ps1'

Write-Host ''
Write-Host '  ============================================' -ForegroundColor Cyan
Write-Host '   wproxy 一键启动' -ForegroundColor Cyan
Write-Host '  ============================================' -ForegroundColor Cyan
Write-Host ''

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host '[1/4] 首次使用：注册服务（会弹出 UAC，请点“是”）...' -ForegroundColor Yellow
    & $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action install
} else {
    Write-Host '[1/4] 服务已安装（任务状态：' -NoNewline; Write-Host $task.State -NoNewline -ForegroundColor Green; Write-Host '）'
}

Write-Host '[2/4] 启动内核...' -ForegroundColor Yellow
& $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action start | Out-Null
$deadline = (Get-Date).AddSeconds(30)
$up = $false
while ((Get-Date) -lt $deadline) {
    $up = @(Get-NetTCPConnection -LocalPort $MixedPort -State Listen -ErrorAction SilentlyContinue).Count -gt 0
    if ($up) { break }
    Start-Sleep -Seconds 1
}
if (-not $up) {
    Write-Host '[!] 内核启动超时，请查看 logs 目录或运行 status 检查' -ForegroundColor Red
    exit 1
}

Write-Host ('[3/4] 切换模式：{0} ...' -f $Mode) -ForegroundColor Yellow
& $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action mode -Value $Mode | Out-Null

Write-Host '[4/4] 当前状态：' -ForegroundColor Yellow
& $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action status

Write-Host ''
Write-Host '  [OK] 已启动，浏览器等流量将走日本出口。' -ForegroundColor Green
Write-Host '  面板：双击 打开面板.cmd      停止：双击 一键停止.cmd' -ForegroundColor Gray
Write-Host ''
