# Antigravity 打不开：一键诊断
# 用法: pwsh -NoProfile -ExecutionPolicy Bypass -File .\diagnose.ps1
# 只读检查，不修改任何配置（最后会给出建议命令，但不执行）。

$ErrorActionPreference = 'Continue'
$sep = '=' * 62

function Section([string]$Title) {
    Write-Host ''
    Write-Host $sep -ForegroundColor DarkCyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host $sep -ForegroundColor DarkCyan
}

function Problem([string]$Text) { Write-Host "  [X] $Text" -ForegroundColor Red }
function Warn([string]$Text)    { Write-Host "  [!] $Text" -ForegroundColor Yellow }
function Ok([string]$Text)      { Write-Host "  [OK] $Text" -ForegroundColor Green }
function Info([string]$Text)    { Write-Host "      $Text" -ForegroundColor Gray }

$findings = @()

# ---------------------------------------------------------------- 1. 进程
Section '1. Antigravity 进程'

$agy = @(Get-CimInstance Win32_Process -Filter "Name = 'Antigravity.exe' OR Name = 'language_server.exe'" -ErrorAction SilentlyContinue)
if ($agy.Count -eq 0) {
    Warn 'Antigravity / language_server 当前没在运行（下面仍可查配置）'
} else {
    foreach ($p in $agy) {
        Ok ("{0} pid={1}" -f $p.Name, $p.ProcessId)
        # 代理环境变量是否被继承（Go 侧只认进程环境变量）
        $envs = @()
        foreach ($k in 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY') {
            if ($p.CommandLine -and $p.CommandLine -match [regex]::Escape($k)) { $envs += $k }
        }
        if ($envs.Count -eq 0) {
            Warn '    进程环境里看不出代理变量（Go 的 language_server 不吃系统代理）'
            $findings += "language_server.exe 没有代理环境变量：请求会直连 <公网IP>:443 被墙"
        }
    }
}

# ---------------------------------------------------------------- 2. 443 占用
Section '2. 本机 443 端口（Caddy 本地反代是否还活着）'

$l443 = @(Get-NetTCPConnection -LocalPort 443 -State Listen -ErrorAction SilentlyContinue)
$caddyProc = @(Get-Process caddy -ErrorAction SilentlyContinue)
$ourIps = @()
try {
    $ourIps += (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress
} catch {}
$loopbackOnly = $true

if ($l443.Count -eq 0) {
    Ok '443 无监听 —— Caddy 本地反代已停，不会截走 HTTPS'
} else {
    foreach ($c in $l443) {
        $owner = (Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        Write-Host ("      {0}:{1}  <- {2} (pid {3})" -f $c.LocalAddress, $c.LocalPort, $owner, $c.OwningProcess) -ForegroundColor White
        if ($ourIps -notcontains $c.LocalAddress -and $c.LocalAddress -ne '0.0.0.0' -and $c.LocalAddress -ne '::') {
            $loopbackOnly = $false
        }
    }
    if ($caddyProc.Count -gt 0) {
        Problem 'caddy 正在运行并且占用 443 —— 所有走 :443 的请求都会先撞上它'
        $findings += 'Caddy 本地反代仍在监听 443（配置已退化成自己代理自己）'
    } else {
        Warn '443 被占用，但不是 caddy，请核对上面列出的进程'
    }
}

# ---------------------------------------------------------------- 3. Caddy 配置
Section '3. Caddy 配置与证书'

$caddyDir = Join-Path $env:APPDATA 'Caddy'
$autosave = Join-Path $caddyDir 'autosave.json'
if (-not (Test-Path -LiteralPath $autosave)) {
    Ok '没有 %APPDATA%\Caddy\autosave.json'
} else {
    try {
        $raw = Get-Content -LiteralPath $autosave -Raw
        $cfg = $raw | ConvertFrom-Json
        $routes = $cfg.apps.http.servers.srv0.routes
        $hosts = @()
        $upstreams = @()
        foreach ($r in $routes) {
            if ($r.match -and $r.match.host) { $hosts += $r.match.host }
            $h = $r.handle | Select-Object -First 1
            if ($h.handle -and $h.handle[0].transport -and $h.handle[0].transport.versions) {
                $upstreams += $h.handle[0].upstreams.dial
            }
        }
        Info ('匹配域名: ' + (($hosts | Select-Object -Unique) -join ', '))
        Info ('上游: ' + (($upstreams | Select-Object -Unique) -join ', '))

        $bad = @($upstreams | Where-Object { $_ -like '*{http.request.host}*' })
        if ($bad.Count -gt 0) {
            Problem 'upstream 用了 {http.request.host}:443 —— 等于把请求代理回它自己'
            $findings += 'Caddy upstream 指向 {http.request.host}，配合空 hosts 形成 :443 自环'
        }
    } catch {
        Warn ('autosave.json 解析失败: ' + $_.Exception.Message)
    }
}

$certDir = Join-Path $caddyDir 'certificates\local'
if (Test-Path -LiteralPath $certDir) {
    $expired = 0
    $total = 0
    foreach ($crt in Get-ChildItem -LiteralPath $certDir -Filter '*.crt' -Recurse -ErrorAction SilentlyContinue) {
        $total++
        try {
            $bytes = [System.IO.File]::ReadAllBytes($crt.FullName)
            $pem = [System.Text.Encoding]::ASCII.GetString($bytes)
            $b64 = ($pem -split '-----BEGIN CERTIFICATE-----')[1]
            $b64 = ($b64 -split '-----END CERTIFICATE-----')[0] -replace '\s', ''
            $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new([Convert]::FromBase64String($b64))
            if ($cert.NotAfter -lt (Get-Date)) {
                $expired++
                Info ("{0}: 已于 {1:yyyy-MM-dd HH:mm} 过期" -f $cert.Subject, $cert.NotAfter)
            }
        } catch {}
    }
    if ($total -gt 0) {
        Warn ("本地 CA 证书 {0} 张，其中 {1} 张已过期" -f $total, $expired)
        if ($expired -eq $total) { $findings += 'Caddy 本地证书全部过期' }
    }
} else {
    Ok '没有 Caddy 本地证书（说明反代早已不用）'
}

# ---------------------------------------------------------------- 4. hosts
Section '4. hosts 映射'

$hostsFile = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
$watchHosts = 'daily-cloudcode-pa.googleapis.com', 'daily-cloudcode-pa.sandbox.googleapis.com',
              'oauth2.googleapis.com', 'play.googleapis.com', 'antigravity-unleash.goog',
              'www.googleapis.com', 'googleapis.com', 'lh3.googleusercontent.com'
$hits = @()
if (Test-Path -LiteralPath $hostsFile) {
    $lines = Get-Content -LiteralPath $hostsFile -ErrorAction SilentlyContinue
    foreach ($l in $lines) {
        $t = $l.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        foreach ($h in $watchHosts) {
            if ($t -match [regex]::Escape($h)) { $hits += $t }
        }
    }
}
if ($hits.Count -eq 0) {
    Warn 'hosts 里这些域名一条都没有'
    $findings += 'hosts 里没有任何 Antigravity 相关条目，但 Caddy 反代仍然开着 → 自环'
} else {
    $hits | ForEach-Object { Info $_ }
}

# ---------------------------------------------------------------- 5. 系统代理 + 出口
Section '5. 系统代理 / wproxy 出口'

$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$reg = Get-ItemProperty -Path $key -ErrorAction SilentlyContinue
Info ("ProxyEnable = {0}" -f $reg.ProxyEnable)
Info ("ProxyServer = {0}" -f $reg.ProxyServer)
if ($reg.ProxyEnable -ne 1) {
    Warn '系统代理未开启；Electron 外壳（自动更新）会直连，language_server 更不会走代理'
}

$curl = Join-Path $env:SystemRoot 'System32\curl.exe'
if (Test-Path -LiteralPath $curl) {
    $eg = & $curl -s --max-time 12 -x 'http://127.0.0.1:7897' https://api.ipify.org 2>$null
    if ($eg) { Ok ("经 7897 出口 IP: {0}" -f $eg) }
    else {
        Problem '经 127.0.0.1:7897 拿不到出口 IP —— wproxy 内核没在跑或节点全挂'
        $findings += 'wproxy 7897 无法出网'
    }
}

# ---------------------------------------------------------------- 6. Antigravity 日志
Section '6. Antigravity 日志（最近失败形态）'

$lsLog = Join-Path $env:APPDATA 'Antigravity\logs\language_server.log'
if (Test-Path -LiteralPath $lsLog) {
    $tail = Get-Content -LiteralPath $lsLog -Tail 400 -ErrorAction SilentlyContinue
    $direct = @($tail | Select-String -Pattern 'dial tcp (?!127\.0\.0\.1)' | Select-Object -Last 2)
    $viaProxy = @($tail | Select-String -Pattern 'dial tcp 127\.0\.0\.1:7897' | Select-Object -Last 2)

    if ($viaProxy.Count -gt 0) {
        Ok '日志里已经出现经 127.0.0.1:7897 的连接 —— 请求进代理了'
    } elseif ($direct.Count -gt 0) {
        $last = ($tail | Select-String -Pattern '^ERROR' | Select-Object -Last 1).Line
        Problem '仍在直连公网 443（未经 7897）'
        if ($last) { Info ('最后一条: ' + $last.Substring(0, [Math]::Min(160, $last.Length))) }
        $findings += 'Antigravity 请求没进代理，仍在直连被墙'
    } else {
        Ok '最近 400 行里没有明显的直连失败'
    }
} else {
    Warn '找不到 language_server.log'
}

$mainLog = Join-Path $env:APPDATA 'Antigravity\logs\main.log'
if (Test-Path -LiteralPath $mainLog) {
    $lines = Get-Content -LiteralPath $mainLog -ErrorAction SilentlyContinue
    $ver = ($lines | Select-String -Pattern 'Starting app \(v([\d.]+)\)' | Select-Object -Last 1)
    if ($ver) { Info ('最后启动版本: ' + $ver.Matches[0].Groups[1].Value) }
    $upd = ($lines | Select-String -Pattern 'Update available: ([\d.]+)' | Select-Object -Last 1)
    if ($upd) {
        $newVer = $upd.Matches[0].Groups[1].Value
        $pend = Join-Path $env:LOCALAPPDATA ('antigravity-updater\pending\Antigravity-x64.exe')
        if (Test-Path -LiteralPath $pend) {
            Warn ("已下载 {0} 但尚未安装（pending 目录里躺着安装包）" -f $newVer)
            $findings += "新版 $newVer 已下载未安装，当前仍是旧版"
        }
    }
}

# ---------------------------------------------------------------- 结论
Section '结论'
if ($findings.Count -eq 0) {
    Ok '没发现上面几类问题。若仍打不开，请贴 language_server.log 最后 50 行。'
} else {
    $i = 0
    foreach ($f in ($findings | Select-Object -Unique)) {
        $i++
        Problem ("{0}. {1}" -f $i, $f)
    }
}

Write-Host ''
Write-Host $sep -ForegroundColor DarkCyan
Write-Host '  建议动作（按顺序，脚本不会自动执行）' -ForegroundColor Cyan
Write-Host $sep -ForegroundColor DarkCyan
Write-Host @'
  1) 停掉退化 Caddy（若第 2 段确认它在跑 443）:
       Copy-Item "$env:APPDATA\Caddy\autosave.json" "$env:APPDATA\Caddy\autosave.json.bak"
       Stop-Process -Name caddy -Force
  2) 给 Antigravity 注入代理并启动:
       .\launch-with-proxy.ps1
  3) 装掉已下载的新版（关掉 App 后）:
       Start-Process "$env:LOCALAPPDATA\antigravity-updater\pending\Antigravity-x64.exe" -ArgumentList '/S','--updated' -Wait
  4) 重跑本脚本验收：第 5 段有出口 IP、第 6 段无"直连公网 443"
'@ -ForegroundColor Yellow
Write-Host ''
