<#
.SYNOPSIS
  部署新版 vibelink hub / agent，并完成一次性的 token 轮换（可重复运行，幂等）。

.DESCRIPTION
  1. 停掉 hub / agent，备份旧二进制（*.prev.exe），换成仓库 target\release 下的新版本，重新拉起。
  2. 第一次运行时（没有 data\rotated-v2.marker）：
     - 把旧 token 的 SHA-256 写进 data\legacy-gateway.sha256：旧 token 只在 CONNECT 网关
       （18996/18997，局域网 / tailnet）上继续有效，手机重新配对前不断网；HTTP API 立即不认。
     - 给本机 agent 签发 agent 作用域的 token，写进 run-agent.cmd。
     - 轮换管理员 token（data\token.txt），同步到 Cindy 的 %APPDATA%\vibelink\config.json。
  3. 最后提示怎么扫码连接手机（二维码在 vibelink 桌面端「📱 连接手机」里生成）。

  手机装好新 APK 后：VibeLink「远程会话 › 扫码连接」扫桌面端的二维码，在手机上点「连接」。全部手机重配后执行
    .\deploy-secure-hub.ps1 -DropLegacyGateway
  删掉过渡期的旧 token。

.PARAMETER Root
  运行目录，默认 %LOCALAPPDATA%\vibelink-mesh。

.PARAMETER Build
  仓库里的 release 目录，默认本脚本所在仓库的 target\release。
#>
param(
    [string]$Root = (Join-Path $env:LOCALAPPDATA 'vibelink-mesh'),
    [string]$Build = (Join-Path (Split-Path -Parent $PSScriptRoot) 'target\release'),
    [switch]$SkipBinaries,
    [switch]$DropLegacyGateway,
    [string]$PairName = 'phone',
    [int]$Port = 18995,
    # 仅用于测试：不停 / 不启任何进程，不碰 %APPDATA% 下的配置
    [switch]$NoProcessControl
)
$ErrorActionPreference = 'Stop'
$dataRoot = Join-Path $Root 'data'
$data = Join-Path $dataRoot 'dev'
$bin = Join-Path $Root 'bin'
$hubExe = Join-Path $bin 'vibelink-hub.exe'
$port = $Port
$env:VIBELINK_DATA = $dataRoot
$env:VIBELINK_PORT = "$port"

function Say($m) { Write-Host "[deploy] $m" -ForegroundColor Cyan }
function Sha256Hex([string]$s) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($s.Trim()))
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Wait-Hub {
    for ($i = 0; $i -lt 40; $i++) {
        try { Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2 | Out-Null; return $true } catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}
function Hub-Cli([string[]]$cliArgs) {
    $out = & $hubExe @cliArgs
    if ($LASTEXITCODE -ne 0) { throw "vibelink-hub $($cliArgs -join ' ') failed: $out" }
    return ($out | Out-String | ConvertFrom-Json)
}
function Start-Hidden([string]$cmd) {
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$cmd`"" -WindowStyle Hidden
}
function Replace-Binary([string]$name, [string]$procName, [string]$runCmd) {
    $src = Join-Path $Build $name
    $dst = Join-Path $bin $name
    if (-not (Test-Path $src)) { Say "跳过 $name（$src 不存在）"; return }
    if ((Test-Path $dst) -and ((Get-FileHash $src).Hash -eq (Get-FileHash $dst).Hash)) { Say "$name 已是最新"; return }
    Get-Process $procName -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 1200
    if (Test-Path $dst) { Copy-Item $dst ($dst -replace '\.exe$', '.prev.exe') -Force }
    Copy-Item $src $dst -Force
    Say "已替换 $name"
    if ($runCmd) { Start-Hidden $runCmd }
}

# ---- 1. 二进制 ----
if (-not $SkipBinaries) {
    Replace-Binary 'vibelink-hub.exe' 'vibelink-hub' (Join-Path $Root 'run-hub.cmd')
    Replace-Binary 'vibelink-agent.exe' 'vibelink-agent' (Join-Path $Root 'run-agent.cmd')
    Replace-Binary 'vibelink-gui.exe' 'vibelink-gui' $null
}
if (-not $NoProcessControl -and -not (Get-Process vibelink-hub -ErrorAction SilentlyContinue)) { Start-Hidden (Join-Path $Root 'run-hub.cmd') }
if (-not (Wait-Hub)) { throw "hub 没有起来，看 $data\hub.log" }
Say "hub 在线"

$legacyFile = Join-Path $data 'legacy-gateway.sha256'
if ($DropLegacyGateway) {
    Remove-Item $legacyFile -ErrorAction SilentlyContinue
    Say "已删除过渡期旧 token（网关只认新 token 了）"
    exit 0
}

# ---- 2. 一次性轮换 ----
$marker = Join-Path $data 'rotated-v2.marker'
if (-not (Test-Path $marker)) {
    $old = (Get-Content (Join-Path $data 'token.txt') -Raw).Trim()
    [System.IO.File]::WriteAllText($legacyFile, (Sha256Hex $old), [System.Text.UTF8Encoding]::new($false))
    Say "旧 token 只在 CONNECT 网关上保留（过渡期）"

    $agent = Hub-Cli @('token', 'issue', 'pc', 'agent')
    $runAgent = Join-Path $Root 'run-agent.cmd'
    $txt = Get-Content $runAgent -Raw
    $txt = [regex]::Replace($txt, 'set "VIBELINK_TOKEN=[^"]*"', "set `"VIBELINK_TOKEN=$($agent.token)`"")
    [System.IO.File]::WriteAllText($runAgent, $txt, [System.Text.Encoding]::ASCII)
    if (-not $NoProcessControl) {
        Get-Process vibelink-agent -ErrorAction SilentlyContinue | Stop-Process -Force
        Start-Sleep -Milliseconds 800
        Start-Hidden $runAgent
    }
    Say "本机 agent 改用 agent token（$($agent.info.id)）"

    $rot = Hub-Cli @('token', 'rotate-admin')
    $appData = if ($NoProcessControl) { Join-Path $Root 'appdata' } else { $env:APPDATA }
    $cindyCfg = Join-Path $appData 'vibelink\config.json'
    if (Test-Path $cindyCfg) {
        $c = Get-Content $cindyCfg -Raw | ConvertFrom-Json
        if ($c.token -eq $old) {
            $client = Hub-Cli @('token', 'issue', 'cindy-desktop', 'client')
            $c.token = $client.token
            $c.hub = "http://127.0.0.1:$port"
            [System.IO.File]::WriteAllText($cindyCfg, ($c | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
            Say "Cindy 桌面端改用 client token（$($client.info.id)）"
        }
    }
    $guiCfg = Join-Path $appData 'vibelink-gui\config.json'
    if (Test-Path $guiCfg) {
        $g = Get-Content $guiCfg -Raw | ConvertFrom-Json
        $g.token = $rot.token
        [System.IO.File]::WriteAllText($guiCfg, ($g | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
        Say "vibelink-gui 已换成新的管理员 token"
    }
    Remove-Item (Join-Path $data 'token.pre-rotate.txt') -ErrorAction SilentlyContinue
    [System.IO.File]::WriteAllText($marker, (Get-Date -Format o), [System.Text.UTF8Encoding]::new($false))
    Say "管理员 token 已轮换（data\token.txt）；旧 token 对 HTTP API 立即失效"
}

# ---- 3. 自检 ----
try {
    $r = Invoke-WebRequest -Uri 'https://hub.vibelink.cloud/apk' -MaximumRedirection 0 -UseBasicParsing -TimeoutSec 10 -ErrorAction SilentlyContinue
} catch { $r = $_.Exception.Response }
$loc = if ($r -and $r.Headers) { "$($r.Headers['Location'])" } else { '' }
if ($loc -match 't=') { Write-Warning "公网 /apk 仍然带 token：$($loc -replace 't=[A-Za-z0-9]+','t=<TOKEN>')" } else { Say "公网 /apk 跳转不再带 token" }

Write-Host ""
Write-Host "连接手机：在电脑上打开 vibelink（Cindy 桌面端）点右上角「📱 连接手机」，" -ForegroundColor Yellow
Write-Host "  用手机 VibeLink「远程会话 › 扫码连接」扫二维码，再在手机上点「连接」。" -ForegroundColor Yellow
Write-Host "  没有桌面端时也可以在终端里出码：`$env:VIBELINK_DATA='$data'; & '$hubExe' pair-qr $PairName" -ForegroundColor Gray
Write-Host "其他机器的 agent：在这台 PC 上执行" -ForegroundColor Gray
Write-Host "  `$env:VIBELINK_DATA='$data'; & '$hubExe' token issue laptop agent" -ForegroundColor Gray
Write-Host "把返回的 token 写进那台机器 agent 的 VIBELINK_TOKEN。" -ForegroundColor Gray

# Cindy 的本机服务（:5174）旧版监听所有网卡并把 hub token 明文返回；加固版要重启 Cindy 才生效
$exposed = Get-NetTCPConnection -LocalPort 5174 -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }
if ($exposed) {
    Write-Warning "Cindy 本机服务 :5174 仍监听在 $($exposed[0].LocalAddress)（旧版，会泄露 token）。请退出 Cindy 后重新运行 cindy\start-cindy.ps1。"
}
