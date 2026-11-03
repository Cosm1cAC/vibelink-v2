# 带着代理环境变量启动 Antigravity
# 用法: pwsh -NoProfile -ExecutionPolicy Bypass -File .\launch-with-proxy.ps1
# 原理: language_server.exe 由 Antigravity.exe 拉起，会继承这里设置的环境变量；
#       Go 侧只认进程环境变量（HTTP_PROXY/HTTPS_PROXY/ALL_PROXY），不认系统代理。

param(
    [string]$Proxy = 'http://127.0.0.1:7897',
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'

$exe = Join-Path $env:LOCALAPPDATA 'Programs\antigravity\Antigravity.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    Write-Host "[X] 找不到 Antigravity.exe: $exe" -ForegroundColor Red
    exit 1
}

# 1. 先确认代理端口在听
$listening = @(Get-NetTCPConnection -LocalPort 7897 -State Listen -ErrorAction SilentlyContinue).Count -gt 0
if (-not $listening) {
    Write-Host '[!] 127.0.0.1:7897 没有监听 —— 先启动 wproxy（双击 wproxy\一键启动.cmd）' -ForegroundColor Yellow
    Write-Host '    仍继续启动，但 Antigravity 会连不上网。' -ForegroundColor Yellow
}

# 2. 确认 443 没被本地反代占用（Caddy 自环会让 HTTPS 直接失败）
$l443 = @(Get-NetTCPConnection -LocalPort 443 -State Listen -ErrorAction SilentlyContinue)
if ($l443.Count -gt 0) {
    $owners = ($l443 | ForEach-Object {
        (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    } | Select-Object -Unique) -join ','
    Write-Host ("[!] 443 已被占用: {0}" -f $owners) -ForegroundColor Yellow
    if ($owners -match 'caddy') {
        Write-Host '    caddy 本地反代已退化（自己代理自己），会让所有 HTTPS 失败。' -ForegroundColor Red
        Write-Host '    建议先执行:  Stop-Process -Name caddy -Force' -ForegroundColor Red
    }
}

# 3. 设置代理环境变量
$env:HTTP_PROXY  = $Proxy
$env:HTTPS_PROXY = $Proxy
$env:ALL_PROXY   = $Proxy
$env:NO_PROXY    = 'localhost,127.0.0.1,::1,100.64.0.0/10,*.ts.net,192.168.0.0/16'
$env:no_proxy    = $env:NO_PROXY

Write-Host ("[i] HTTP_PROXY/HTTPS_PROXY/ALL_PROXY = {0}" -f $Proxy) -ForegroundColor Cyan
Write-Host '[i] 正在启动 Antigravity ...' -ForegroundColor Cyan

# 4. 启动（继承当前进程环境）
$p = Start-Process -FilePath $exe -ArgumentList $ExtraArgs -PassThru
Write-Host ("[i] pid = {0}" -f $p.Id) -ForegroundColor Gray

# 5. 等几秒后看一下日志有没有进代理
Start-Sleep -Seconds 12
$lsLog = Join-Path $env:APPDATA 'Antigravity\logs\language_server.log'
if (Test-Path -LiteralPath $lsLog) {
    $tail = Get-Content -LiteralPath $lsLog -Tail 120 -ErrorAction SilentlyContinue
    if (@($tail | Select-String -Pattern 'dial tcp 127\.0\.0\.1:7897').Count -gt 0) {
        Write-Host '[OK] 日志里已出现经 127.0.0.1:7897 的连接' -ForegroundColor Green
    } elseif (@($tail | Select-String -Pattern 'dial tcp (?!127\.0\.0\.1)').Count -gt 0) {
        Write-Host '[!] 日志里仍有直连公网 443 的失败；可能 App 没继承变量，改走快捷方式方式（见 README 第 1 步 A）' -ForegroundColor Yellow
    } else {
        Write-Host '[i] 日志暂时没有明显错误，打开界面确认一下' -ForegroundColor Gray
    }
}
