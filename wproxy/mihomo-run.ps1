param(
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'

if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'config.ps1' }
. $ConfigPath

$logDir = Join-Path $WproxyDir 'logs'
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$logFile = Join-Path $logDir ('watchdog-{0:yyyyMMdd}.log' -f (Get-Date))
$runLog = Join-Path $logDir 'mihomo.out.log'
$errLog = Join-Path $logDir 'mihomo.err.log'
$hyExe = Join-Path $WproxyDir 'hysteria.exe'
$hyCfg = Join-Path $WproxyDir 'hysteria-client.yaml'
$hyPort = 7893
$hyLog = Join-Path $logDir 'hysteria.out.log'
$hyErrLog = Join-Path $logDir 'hysteria.err.log'
$cfExe = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
$cfHost = 'socks.vibelink.cloud'
$cfPort = 7892
$cfLog = Join-Path $logDir 'cloudflared.out.log'
$cfErrLog = Join-Path $logDir 'cloudflared.err.log'

function Write-Log([string]$Message) {
    Add-Content -LiteralPath $logFile -Value ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) -Encoding UTF8
}

# 2026-10-24：兜底直连此前只写一行本地日志，2026-10-24 断网 4 小时无人知晓。
# 这里加桌面通知（原生 WinRT Toast，无需第三方模块），并对同类告警做冷却去重，
# 避免节点反复抖动时刷屏。
$AlertState = @{}
$AlertCooldownSeconds = 1800

function Send-Alert([string]$Key, [string]$Title, [string]$Body) {
    $now = Get-Date
    if ($AlertState.ContainsKey($Key)) {
        $last = $AlertState[$Key]
        if (($now - $last).TotalSeconds -lt $AlertCooldownSeconds) { return }
    }
    $AlertState[$Key] = $now
    Write-Log ('[alert] {0} :: {1}' -f $Title, $Body)

    # 1) 控制台/日志兜底（Toast 失败也不至于静默）
    Write-Host ''
    Write-Host ('  [!] ' + $Title) -ForegroundColor Red
    Write-Host ('      ' + $Body) -ForegroundColor Yellow
    Write-Host ''

    # 2) Windows 原生 Toast
    #    pwsh 7 没有 WinRT 投影（[Windows.UI.Notifications...] 类型找不到），
    #    因此转交 Windows PowerShell 5.1 子进程发送；用 -EncodedCommand(UTF-16LE)
    #    传中文，避免 LoadXml 因编码问题抛 0xC00CE56D。
    try {
        # 单引号字符串内注入：转义 XML 特殊字符，并避免破坏外层单引号
        $esc = { param($s) $s -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;' -replace "'", '&apos;' }
        $tSafe = & $esc $Title
        $bSafe = & $esc $Body
        $XmlSafe = '<toast><visual><binding template="ToastGeneric"><text>' + $tSafe + '</text><text>' + $bSafe + '</text></binding></visual><audio src="ms-winsoundevent:Notification.Reminder"/></toast>'

        $ps51 = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (-not (Test-Path -LiteralPath $ps51)) { throw "powershell.exe 5.1 not found: $ps51" }

        $inner = @"
try {
  [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
  [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
  `$x = New-Object Windows.Data.Xml.Dom.XmlDocument
  `$x.LoadXml('$XmlSafe')
  `$toast = New-Object Windows.UI.Notifications.ToastNotification `$x
  [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe').Show(`$toast)
} catch { }
"@
        $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($inner))
        $p = Start-Process -FilePath $ps51 -ArgumentList @('-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', $enc) -WindowStyle Hidden -PassThru -Wait
        if ($p.ExitCode -ne 0) { Write-Log ('[alert] toast exit {0}' -f $p.ExitCode) }
    } catch {
        Write-Log ('[alert] toast failed: {0}' -f $_.Exception.Message)
    }
}

$mutex = New-Object System.Threading.Mutex($false, 'WProxyWatchdog')
$acquired = $false
try { $acquired = $mutex.WaitOne(1000) } catch [System.Threading.AbandonedMutexException] { $acquired = $true }
if (-not $acquired) { Write-Log 'Another watchdog instance is running. Exiting.'; exit 0 }

# ---- auto-switch policy ----
# Keeps the fastest healthy node selected: primary = phone relay (CMFA), fallbacks below.
# When every node is unreachable, falls back to DIRECT so traffic degrades instead of erroring.
# Any manually selected node outside this list pauses automatic switching.
$PrimaryNode = 'Japan-MacHy2'
$FallbackNodes = @('Japan-CF')
$FailSafeNode = 'DIRECT'
$SwitchCheckSeconds = 30
$FailThreshold = 2
$RecoverThreshold = 2
$NodeTestUrl = [uri]::EscapeDataString('http://cp.cloudflare.com/generate_204')
$NodeTestTimeoutMs = 8000
# 2026-10-24：节点"活着但慢"同样等于断网（实测 Japan-MacMini 探测 184–1478ms 抖动、吞吐仅 5–25KB/s，
# 用户侧表现为"开代理就断网"）。判定分两条，连续 FailThreshold 次即切换：
#   ① 延迟 ≥ HardSlowDelayMs；② 经本机 mixed 口实测吞吐 < MinThroughputKBps。
# 吞吐探测默认每 ThroughputEveryChecks 次检查（2×30s）做一次，样本 256KB（约 7MB/h 后台流量）；
# 延迟已 ≥ DegradedDelayMs 时每次都测。
# 限制：只代表到 speed.cloudflare.com 的单流表现，特定站点限速仍可能漏判。
$DegradedDelayMs = 800
$HardSlowDelayMs = 1200
$BetterRatio = 0.5
$MinThroughputKBps = 60
$ThroughputUrl = 'https://speed.cloudflare.com/__down?bytes=262144'
$ThroughputTimeoutSec = 8
$ThroughputEveryChecks = 2
$CurlExe = Join-Path $env:SystemRoot 'System32\curl.exe'

$apiHeaders = @{ Authorization = "Bearer $ApiSecret" }
$primaryFails = 0
$primaryRecovers = 0
$currentFails = 0
$checkCount = 0
# 启动时先假设"上一次的节点已经降级"：第一次探测到慢就立即切，避免重启后还要再熬 60s。
$slowCount = $FailThreshold - 1
$nextSwitchCheck = (Get-Date).AddSeconds(15)

function Get-CurrentNode {
    try { return (Invoke-RestMethod -Uri ($ApiBase + '/proxies/PROXY') -Headers $apiHeaders -TimeoutSec 10).now } catch { return $null }
}

function Test-NodeDelay([string]$Name) {
    try {
        $r = Invoke-RestMethod -Uri ($ApiBase + ('/proxies/{0}/delay?url={1}&timeout={2}' -f $Name, $NodeTestUrl, $NodeTestTimeoutMs)) -Headers $apiHeaders -TimeoutSec 12
        return [int]$r.delay
    } catch { return -1 }
}

function Set-CurrentNode([string]$Name) {
    Invoke-RestMethod -Method Put -Uri ($ApiBase + '/proxies/PROXY') -Headers $apiHeaders -Body (@{ name = $Name } | ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 10 | Out-Null
}

function Select-AliveFallback {
    # 校园网丢包 15%+ 时单次探测经常假死：全部测一遍，取延迟最低的存活节点
    $alive = @()
    foreach ($n in $FallbackNodes) {
        $d = Test-NodeDelay $n
        if ($d -ge 0) { $alive += @{ name = $n; delay = $d } }
    }
    if ($alive.Count -eq 0) { return $null }
    return ($alive | Sort-Object { $_.delay } | Select-Object -First 1)
}

function Test-PrimaryHealthy {
    # 延迟低才算"健康"：慢到降级线以上的 primary 不放行，否则会和降级切换互相踢皮球
    $d = Test-NodeDelay $PrimaryNode
    if ($d -ge 0 -and $d -lt $DegradedDelayMs) { return $d }
    return -1
}

function Test-NodeThroughput {
    # 经本机 mixed 口（当前节点）下载固定大小样本，返回 KB/s；-1 = 无法测量
    if (-not (Test-Path -LiteralPath $CurlExe)) { return -1 }
    $out = & $CurlExe -x ('http://127.0.0.1:{0}' -f $MixedPort) -o NUL -s --max-time $ThroughputTimeoutSec -w '%{speed_download}' $ThroughputUrl 2>$null
    $text = ([string]$out).Trim()
    if (-not $text) { return -1 }
    $v = 0.0
    if (-not [double]::TryParse($text, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$v)) { return -1 }
    return [int][math]::Round($v / 1024)
}

function Invoke-DegradedSwitch([string]$Name, [int]$Delay, [string]$Reason) {
    $script:slowCount++
    Write-Log ('[auto] current {0} degraded: {1} ({2}/{3})' -f $Name, $Reason, $script:slowCount, $FailThreshold)
    if ($script:slowCount -lt $FailThreshold) { return }
    $fb = Select-AliveFallback
    if ($fb -and $fb.name -ne $Name) {
        $ratioOk = ($fb.delay -le ($Delay * $BetterRatio))
        $throughputOk = ($Reason -like 'throughput*') -and ($fb.delay -le $DegradedDelayMs)
        if ($ratioOk -or $throughputOk) {
            Set-CurrentNode $fb.name
            Write-Log ('[auto] {0} -> {1} ({2}ms; was {3})' -f $Name, $fb.name, $fb.delay, $Reason)
        } else {
            Write-Log ('[auto] {0} degraded ({1}) but no clearly faster fallback (best={2} {3}ms); staying' -f $Name, $Reason, $fb.name, $fb.delay)
        }
    } else {
        Write-Log ('[auto] {0} degraded ({1}) and no alternate fallback alive; staying' -f $Name, $Reason)
    }
    $script:slowCount = 0
}

function Invoke-AutoSwitchCheck {
    $current = Get-CurrentNode
    if (-not $current) { return }
    $managed = @($PrimaryNode) + $FallbackNodes + @($FailSafeNode)
    if ($current -notin $managed) { return }

    if ($current -eq $PrimaryNode) {
        $d = Test-PrimaryHealthy
        if ($d -ge 0) { $script:primaryFails = 0; $script:slowCount = 0; return }
        $raw = Test-NodeDelay $PrimaryNode
        if ($raw -ge 0) { Invoke-DegradedSwitch $PrimaryNode $raw ('delay {0}ms' -f $raw); return }
        $script:primaryFails++
        Write-Log ('[auto] primary {0} check failed ({1}/{2})' -f $PrimaryNode, $script:primaryFails, $FailThreshold)
        if ($script:primaryFails -ge $FailThreshold) {
            $fb = Select-AliveFallback
            if ($fb) { Set-CurrentNode $fb.name; Write-Log ('[auto] switched to fallback {0} ({1}ms)' -f $fb.name, $fb.delay) }
            else {
                Set-CurrentNode $FailSafeNode
                Write-Log ('[auto] primary {0} down and no fallback reachable; fail-safe -> {1}' -f $PrimaryNode, $FailSafeNode)
                Send-Alert 'failsafe' '代理已全部失效，已回退直连' '所有日本节点都不可达，境外网站将无法访问。请检查 Mac mini 是否在线。'
            }
            $script:primaryFails = 0
            $script:primaryRecovers = 0
            $script:slowCount = 0
        }
        return
    }

    # 当前是兜底节点或 DIRECT：能切回健康的 primary 就切，否则评估当前节点是否降级
    $pd = Test-PrimaryHealthy
    if ($pd -ge 0) {
        $script:primaryRecovers++
        if ($script:primaryRecovers -ge $RecoverThreshold) {
            Set-CurrentNode $PrimaryNode
            Write-Log ('[auto] switched back to primary {0} ({1}ms)' -f $PrimaryNode, $pd)
            # 从兜底直连恢复时也通知一次，让用户知道代理已恢复
            if ($AlertState.ContainsKey('failsafe')) {
                Send-Alert 'recovered' '代理已恢复' ('已切回 {0}（{1}ms），境外访问恢复正常。' -f $PrimaryNode, $pd)
                $AlertState.Remove('failsafe')
            }
            $script:primaryRecovers = 0
            $script:currentFails = 0
            $script:slowCount = 0
        }
        return
    }
    $script:primaryRecovers = 0

    $cd = Test-NodeDelay $current
    if ($cd -lt 0) {
        $script:slowCount = 0
        # 已经在兜底直连上，没有可切的节点了，别刷日志
        if ($current -eq $FailSafeNode) { return }
        $script:currentFails++
        Write-Log ('[auto] current {0} check failed ({1}/{2})' -f $current, $script:currentFails, $FailThreshold)
        if ($script:currentFails -ge $FailThreshold) {
            $fb = Select-AliveFallback
            if ($fb -and $fb.name -ne $current) { Set-CurrentNode $fb.name; Write-Log ('[auto] {0} dead {1}x; switched to {2} ({3}ms)' -f $current, $script:currentFails, $fb.name, $fb.delay) }
            elseif (-not $fb) {
                Set-CurrentNode $FailSafeNode
                Write-Log ('[auto] {0} dead and no fallback reachable; fail-safe -> {1}' -f $current, $FailSafeNode)
                Send-Alert 'failsafe' '代理已全部失效，已回退直连' '所有日本节点都不可达，境外网站将无法访问。请检查 Mac mini 是否在线。'
            }
            $script:currentFails = 0
        }
        return
    }
    $script:currentFails = 0
    if ($current -eq $FailSafeNode) { $script:slowCount = 0; return }

    $script:checkCount++
    $reason = $null
    if ($cd -ge $HardSlowDelayMs) { $reason = ('delay {0}ms' -f $cd) }
    $probe = ($cd -ge $DegradedDelayMs) -or (($script:checkCount % $ThroughputEveryChecks) -eq 1)
    if (-not $reason -and $probe) {
        $kb = Test-NodeThroughput
        if ($kb -ge 0 -and $kb -lt $MinThroughputKBps) { $reason = ('throughput {0}KB/s' -f $kb) }
    }
    if (-not $reason) { $script:slowCount = 0; return }
    Invoke-DegradedSwitch $current $cd $reason
}

# ---- 系统代理归属：笔记本优先，其次本机 mihomo，都不可用才直连 ----
$Reg = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$LaptopProxy = '192.168.5.5:7897'
$LocalProxy = '127.0.0.1:7897'

function Test-ProxyUrl([string]$addr) {
    if (-not (Test-Path -LiteralPath $CurlExe)) { return $false }
    $code = & $CurlExe --disable --silent --output NUL --write-out '%{http_code}' --connect-timeout 2 --max-time 6 `
        --proxy ('http://' + $addr) --noproxy 'never-bypass.invalid' `
        --url 'https://www.gstatic.com/generate_204' 2>$null
    return ($LASTEXITCODE -eq 0 -and "$code" -eq '204')
}

function Set-SystemProxy([string]$addr) {
    if ($addr) {
        Set-ItemProperty $Reg ProxyEnable 1 -Type DWord
        Set-ItemProperty $Reg ProxyServer $addr
    } else {
        Set-ItemProperty $Reg ProxyEnable 0 -Type DWord
    }
    $sig = 'using System;using System.Runtime.InteropServices;public class WR{[DllImport("wininet.dll")]public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l);public static void Go(){InternetSetOption(IntPtr.Zero,39,IntPtr.Zero,0);InternetSetOption(IntPtr.Zero,37,IntPtr.Zero,0);}}'
    try { if (-not ('WR' -as [type])) { Add-Type -TypeDefinition $sig }; [WR]::Go() } catch {}
}

$script:laptopMiss = 0
function Invoke-ProxyCheck {
    # 2026-11-03 起：笔记本通道暂时废弃，出口固定走本机 mihomo（Japan-MacHy2）
    $on = (Get-ItemProperty $Reg).ProxyEnable
    $cur = (Get-ItemProperty $Reg).ProxyServer
    if (Test-ProxyUrl $LocalProxy) {
        if ($on -ne 1 -or $cur -ne $LocalProxy) { Set-SystemProxy $LocalProxy; Write-Log ('[sysproxy] -> ' + $LocalProxy) }
        return
    }
    if ($on -ne 0) { Set-SystemProxy $null; Write-Log '[sysproxy] -> DIRECT' }
}
function Get-MihomoProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'mihomo.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains('wproxy') }
}

function Stop-Mihomo {
    foreach ($p in Get-MihomoProcesses) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Log ('Stopped mihomo pid {0}' -f $p.ProcessId)
    }
}

function Get-HysteriaProcesses {
    # 2026-10-24：原来只用 CommandLine 过滤（要求包含 'wproxy'）。看门狗以
    # LeastPrivilege 运行时，hysteria.exe 的 CommandLine 常为 NULL（提权/跨会话进程
    # 读不到命令行），导致 alive 永远 False -> 看门狗每 10 秒杀一次并重启 hysteria，
    # 反复刷 "hysteria check: alive=False listening=True"。
    # 改为：优先按命令行匹配；命令行不可读时退化为按进程名匹配。
    Get-CimInstance Win32_Process -Filter "Name = 'hysteria.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            if ($_.CommandLine) { $_.CommandLine.Contains('wproxy') }
            else { $true }
        }
}

function Stop-Hysteria {
    foreach ($p in Get-HysteriaProcesses) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Log ('Stopped hysteria pid {0}' -f $p.ProcessId)
    }
}

function Get-CloudflaredProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($cfHost) }
}

function Stop-Cloudflared {
    foreach ($p in Get-CloudflaredProcesses) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Log ('Stopped cloudflared pid {0}' -f $p.ProcessId)
    }
}

Write-Log ('Watchdog starting. dir={0}' -f $WproxyDir)
$stopRequest = Join-Path $WproxyDir 'stop.request'
Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
Stop-Mihomo
Stop-Hysteria
Stop-Cloudflared
Start-Sleep -Seconds 1

$proc = $null

while ($true) {
    try {
        if (Test-Path -LiteralPath $stopRequest) {
            Write-Log 'stop.request detected; stopping mihomo and exiting'
            Stop-Mihomo
            Stop-Hysteria
            Stop-Cloudflared
            Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
            exit 0
        }
        $needStart = $false
        if ($null -eq $proc) {
            $needStart = $true
        } elseif ($proc.HasExited) {
            Write-Log ('mihomo exited with code {0}' -f $proc.ExitCode)
            $needStart = $true
        } else {
            $listening = @(Get-NetTCPConnection -LocalPort $MixedPort -State Listen -ErrorAction SilentlyContinue).Count -gt 0
            if (-not $listening) {
                Write-Log 'mixed port is not listening; restarting'
                Stop-Mihomo
                $proc = $null
                $needStart = $true
            }
        }

        if ($needStart) {
            Stop-Mihomo
            Write-Log 'Starting mihomo'
            try {
                $proc = Start-Process -FilePath $MihomoExe -ArgumentList @('-d', $WproxyDir) -WorkingDirectory $WproxyDir -PassThru -WindowStyle Hidden -RedirectStandardOutput $runLog -RedirectStandardError $errLog
            } catch {
                Write-Log ('Failed to start: {0}' -f $_.Exception.Message)
                $proc = $null
                Start-Sleep -Seconds 10
                continue
            }
            Start-Sleep -Seconds 3
            if ($proc.HasExited) {
                Write-Log ('mihomo failed to start, exit code {0}' -f $proc.ExitCode)
                $proc = $null
                Start-Sleep -Seconds 10
            } else {
                Write-Log ('mihomo running pid {0}' -f $proc.Id)
            }
        }

        # hysteria2 client (transport for the Japan-MacHy2 node)
        $hyAlive = @(Get-HysteriaProcesses).Count -gt 0
        $hyListening = @(Get-NetTCPConnection -LocalPort $hyPort -State Listen -ErrorAction SilentlyContinue).Count -gt 0
        if (-not ($hyAlive -and $hyListening)) {
            Write-Log ('hysteria check: alive={0} listening={1}' -f $hyAlive, $hyListening)
            Stop-Hysteria
            if (Test-Path -LiteralPath $hyExe) {
                try {
                    Start-Process -FilePath $hyExe -ArgumentList @('client', '-c', $hyCfg) -WorkingDirectory $WproxyDir -WindowStyle Hidden -RedirectStandardOutput $hyLog -RedirectStandardError $hyErrLog | Out-Null
                    Write-Log 'Started hysteria client'
                } catch {
                    Write-Log ('Failed to start hysteria: {0}' -f $_.Exception.Message)
                }
            } else {
                Write-Log ('hysteria.exe not found: {0}' -f $hyExe)
            }
        }

        # cloudflared access (transport for the Japan-CF / PORN-VIDEO node)
        $cfListening = @(Get-NetTCPConnection -LocalPort $cfPort -State Listen -ErrorAction SilentlyContinue).Count -gt 0
        if (-not $cfListening) {
            Write-Log 'cloudflared check: 7892 not listening'
            Stop-Cloudflared
            if (Test-Path -LiteralPath $cfExe) {
                try {
                    # 让 cloudflared 走本机 mihomo：mihomo 的 hosts 把隧道域名解析到优选的 HKG 边缘 IP
                    $oldHttpsProxy = $env:HTTPS_PROXY
                    $env:HTTPS_PROXY = 'http://127.0.0.1:7897'
                    Start-Process -FilePath $cfExe -ArgumentList @('access', 'tcp', '--hostname', $cfHost, '--url', ('127.0.0.1:{0}' -f $cfPort)) -WorkingDirectory $WproxyDir -WindowStyle Hidden -RedirectStandardOutput $cfLog -RedirectStandardError $cfErrLog | Out-Null
                    $env:HTTPS_PROXY = $oldHttpsProxy
                    Write-Log 'Started cloudflared access (via mihomo 127.0.0.1:7897)'
                } catch {
                    Write-Log ('Failed to start cloudflared: {0}' -f $_.Exception.Message)
                }
            } else {
                Write-Log ('cloudflared.exe not found: {0}' -f $cfExe)
            }
        }

        if ((Get-Date) -ge $nextSwitchCheck) {
            Invoke-AutoSwitchCheck
            Invoke-ProxyCheck
            $nextSwitchCheck = (Get-Date).AddSeconds($SwitchCheckSeconds)
        }

        Start-Sleep -Seconds 10
    } catch {
        Write-Log ('Loop error: {0}' -f $_.Exception.Message)
        Start-Sleep -Seconds 10
    }
}



