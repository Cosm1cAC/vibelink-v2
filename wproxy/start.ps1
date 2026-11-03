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
    Write-Host '[1/4] 首次使用：注册服务（会弹出 UAC，请点"是"）...' -ForegroundColor Yellow
    & $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action install
} else {
    Write-Host '[1/4] 服务已安装（任务状态：' -NoNewline; Write-Host $task.State -NoNewline -ForegroundColor Green; Write-Host '）'
}

Write-Host '[2/4] 启动内核...' -ForegroundColor Yellow
& $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action start | Out-Null
$deadline = (Get-Date).AddSeconds(40)
$up = $false
while ((Get-Date) -lt $deadline) {
    $up = @(Get-NetTCPConnection -LocalPort $MixedPort -State Listen -ErrorAction SilentlyContinue).Count -gt 0
    if ($up) { break }
    Start-Sleep -Seconds 1
}
if (-not $up) {
    Write-Host '[!] 内核启动超时，请查看 logs 目录或运行 status 检查' -ForegroundColor Red
    Write-Host ''
    exit 1
}
Write-Host ('      内核已就绪（127.0.0.1:{0} LISTEN）' -f $MixedPort) -ForegroundColor Green

Write-Host ('[3/4] 切换模式：{0} ...' -f $Mode) -ForegroundColor Yellow
$modeOut = & $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action mode -Value $Mode
$modeOut | ForEach-Object { Write-Host ('      {0}' -f $_) }
if ($modeOut -match '^\[!\]') {
    Write-Host ''
    Write-Host '[!] 模式切换失败，以上为内核返回原因。代理未接管流量。' -ForegroundColor Red
    Write-Host ''
    exit 1
}

Write-Host '[4/4] 校验生效状态...' -ForegroundColor Yellow
$reg = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
$problems = @()
if ($Mode -eq 'sysproxy') {
    if ($reg.ProxyEnable -ne 1 -or $reg.ProxyServer -ne ('127.0.0.1:{0}' -f $MixedPort)) {
        $problems += ('系统代理未生效（enable={0} server={1}）' -f $reg.ProxyEnable, $reg.ProxyServer)
    }
    if (Get-NetAdapter -Name 'wproxy' -ErrorAction SilentlyContinue) {
        $problems += '检测到 wproxy 虚拟网卡，TUN 似乎仍在运行'
    }
}

$egress = & "$env:SystemRoot\System32\curl.exe" -s --max-time 20 -x ('http://127.0.0.1:{0}' -f $MixedPort) https://api.ipify.org
if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host ('[!] {0}' -f $_) -ForegroundColor Red }
    Write-Host ''
    exit 1
}
Write-Host ('      系统代理已接管 -> 127.0.0.1:{0}' -f $MixedPort) -ForegroundColor Green
Write-Host ('      出口 IP: {0}' -f $egress) -ForegroundColor Green

Write-Host ''
Write-Host '  ============================================' -ForegroundColor Green
Write-Host '   已启动。国内直连，需要外网的走日本出口。' -ForegroundColor Green
Write-Host '  ============================================' -ForegroundColor Green
Write-Host '  面板：双击 打开面板.cmd        停止：双击 一键停止.cmd' -ForegroundColor Gray
Write-Host ''