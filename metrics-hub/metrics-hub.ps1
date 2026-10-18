# metrics-hub.ps1 -- 多设备监控采集中心 / 上报代理（Windows，无需管理员）
#
# 用法:
#   Hub（主 Windows，采集本机 + 收其它设备 push + 主动拉 Mac）:
#     powershell -File metrics-hub.ps1 -Mode hub
#   Agent（第二台 Windows，采集本机并 push 到 Hub）:
#     powershell -File metrics-hub.ps1 -Mode agent -Hub http://100.74.71.80:18993
#
# 端点:
#   GET  /devices   聚合所有设备（监控页用）
#   GET  /metrics   本机指标（调试）
#   POST /ingest    Agent 上报
#   GET  /static/…  分发安装脚本（install-agent.ps1 / phone-metrics.sh / metrics-hub.ps1）
#
# 依赖: Windows PowerShell 5.1+，无需管理员权限（监听端口需防火墙放行 tailnet 网段）。

[CmdletBinding()]
param(
    [ValidateSet('hub', 'agent')]
    [string]$Mode = 'hub',
    [string]$Hub = 'http://100.74.71.80:18993',
    [int]$Port = 18993,
    [string]$Id = '',
    [string]$Name = '',
    [int]$IntervalSec = 3
)

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------- utilities

function Get-Prop {
    param($Obj, [string]$Key, [double]$Default = 0)
    if ($null -eq $Obj) { return $Default }
    $p = $Obj.PSObject.Properties[$Key]
    if ($p -and $null -ne $p.Value) { return [double]$p.Value }
    return $Default
}

function Get-Epoch {
    return [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}

function Write-ReqLog {
    param([string]$Line)
    try {
        $logPath = Join-Path $PSScriptRoot 'requests.log'
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 1MB) {
            Remove-Item -LiteralPath $logPath -Force
        }
        ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Line) | Add-Content -LiteralPath $logPath -Encoding UTF8
    } catch { }
}

# ---------------------------------------------------------------- collectors

$script:PrevCpu = $null
$script:PrevNet = $null
$script:Ncpu = [int]$env:NUMBER_OF_PROCESSORS
$script:HostName = $env:COMPUTERNAME
$script:Model = 'Windows'
$script:BootTime = $null
$script:Latency = [ordered]@{}

function Initialize-Static {
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
    $cs = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
    $cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cpu -and $cpu.Name -and $cpu.Name -notmatch '^Genuine Intel\(R\) 0000') { $script:Model = $cpu.Name.Trim() }
    elseif ($cs -and $cs.Model) { $script:Model = $cs.Model }
    if ($cs -and $cs.NumberOfLogicalProcessors) { $script:Ncpu = [int]$cs.NumberOfLogicalProcessors }
    if ($os -and $os.LastBootUpTime) {
        $script:BootTime = ([DateTimeOffset]$os.LastBootUpTime).ToUnixTimeSeconds()
    }
}

function Get-CpuPct {
    $s = Get-CimInstance Win32_PerfRawData_PerfOS_Processor -OperationTimeoutSec 2 -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq '_Total' } | Select-Object -First 1
    if (-not $s) { return $null }
    $busy = [double]$s.PercentProcessorTime
    $t = [double]$s.Timestamp_Sys100NS
    $pct = $null
    if ($script:PrevCpu) {
        $dt = $t - $script:PrevCpu.t
        if ($dt -gt 0) {
            $pct = (($busy - $script:PrevCpu.busy) / ($dt * $script:Ncpu)) * 100.0
            if ($pct -lt 0) { $pct = 0 }
            if ($pct -gt 100) { $pct = 100 }
            $pct = [Math]::Round($pct, 1)
        }
    }
    $script:PrevCpu = @{ busy = $busy; t = $t }
    return $pct
}

function Get-GpuPct {
    $now = Get-Epoch
    if ($script:GpuCacheT -and ($now - $script:GpuCacheT) -lt 3) { return $script:GpuCache }
    $script:GpuCacheT = $now
    $eng = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if (-not $eng) { $script:GpuCache = $null; return $null }
    $vals = @($eng | Where-Object { $_.Name -match 'engtype_3D' } | ForEach-Object { [int]$_.UtilizationPercentage })
    if ($vals.Count -eq 0) { $vals = @($eng | ForEach-Object { [int]$_.UtilizationPercentage }) }
    if ($vals.Count -eq 0) { $script:GpuCache = $null; return $null }
    $script:GpuCache = [int]($vals | Measure-Object -Maximum).Maximum
    return $script:GpuCache
}

function Get-MemInfo {
    $os = Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if (-not $os) { return @{} }
    $total = [double]$os.TotalVisibleMemorySize * 1KB
    $avail = [double]$os.FreePhysicalMemory * 1KB
    $cached = $null
    $pm = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if ($pm) {
        $cached = (Get-Prop $pm 'StandbyCacheNormalPriorityBytes') + (Get-Prop $pm 'StandbyCacheReserveBytes') +
                  (Get-Prop $pm 'StandbyCacheCoreBytes') + (Get-Prop $pm 'ModifiedPageListBytes')
    }
    $swap = $null
    $pf = Get-CimInstance Win32_PageFileUsage -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if ($pf) { $swap = [double]($pf | Measure-Object -Property CurrentUsage -Sum).Sum * 1MB }
    return @{
        total      = $total
        used       = $total - $avail
        available  = $avail
        cached     = $cached
        swap_used  = $swap
    }
}

function Get-DiskInfo {
    $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'" -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if (-not $d -or -not $d.Size) { return @{} }
    $total = [double]$d.Size
    $free = [double]$d.FreeSpace
    return @{
        mount    = 'C:'
        total    = $total
        used     = $total - $free
        free     = $free
        used_pct = [Math]::Round((($total - $free) / $total) * 100, 1)
    }
}

function Get-NetInfo {
    $adapters = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' })
    $stats = @(Get-NetAdapterStatistics -ErrorAction SilentlyContinue)
    $map = @{}
    foreach ($a in $adapters) {
        $st = $stats | Where-Object { $_.Name -eq $a.Name } | Select-Object -First 1
        if ($st) {
            $phys = ($a.Name -notmatch 'Tailscale|vEthernet|Loopback|Bluetooth')
            $map[$a.Name] = @{ rx = [double]$st.ReceivedBytes; tx = [double]$st.SentBytes; phys = $phys }
        }
    }
    $now = [DateTime]::UtcNow
    $rx = 0.0; $tx = 0.0; $ifaces = @{}
    if ($script:PrevNet) {
        $dt = ($now - $script:PrevNet.t).TotalSeconds
        if ($dt -gt 0.2) {
            foreach ($k in $map.Keys) {
                if ($script:PrevNet.map.ContainsKey($k)) {
                    $rr = [Math]::Max(0, ($map[$k].rx - $script:PrevNet.map[$k].rx) / $dt)
                    $tt = [Math]::Max(0, ($map[$k].tx - $script:PrevNet.map[$k].tx) / $dt)
                    $ifaces[$k] = @{ rx_bps = [int]$rr; tx_bps = [int]$tt }
                    if ($map[$k].phys) { $rx += $rr; $tx += $tt }
                }
            }
        }
    }
    $script:PrevNet = @{ t = $now; map = $map }
    return @{ rx_bps = [int]$rx; tx_bps = [int]$tx; interfaces = $ifaces }
}

function Test-TcpMs {
    param([string]$TargetHost, [int]$TargetPort, [int]$TimeoutMs = 1500)
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $iar = $c.BeginConnect($TargetHost, $TargetPort, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $null }
        $c.EndConnect($iar)
        $sw.Stop()
        return [Math]::Round($sw.Elapsed.TotalMilliseconds, 1)
    } catch { return $null } finally { $c.Close() }
}

function Test-SocksMs {
    param([string]$ProxyHost, [int]$ProxyPort, [string]$DstHost, [int]$DstPort, [int]$TimeoutMs = 6000)
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $c.BeginConnect($ProxyHost, $ProxyPort, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $null }
        $c.EndConnect($iar)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $s = $c.GetStream()
        $s.ReadTimeout = $TimeoutMs
        $s.WriteTimeout = $TimeoutMs
        $s.Write([byte[]]@(5, 1, 0), 0, 3)
        $r = New-Object byte[] 2
        if ($s.Read($r, 0, 2) -ne 2 -or $r[0] -ne 5 -or $r[1] -ne 0) { return $null }
        $hb = [Text.Encoding]::ASCII.GetBytes($DstHost)
        $req = New-Object 'System.Collections.Generic.List[byte]'
        foreach ($b in @(5, 1, 0, 3, $hb.Length)) { $req.Add([byte]$b) }
        foreach ($b in $hb) { $req.Add($b) }
        $req.Add([byte]($DstPort -shr 8))
        $req.Add([byte]($DstPort -band 255))
        $arr = $req.ToArray()
        $s.Write($arr, 0, $arr.Length)
        $resp = New-Object byte[] 10
        if ($s.Read($resp, 0, 10) -lt 2 -or $resp[1] -ne 0) { return $null }
        # mihomo 对 SOCKS 建连是"先回执后连接"：必须发一次真实请求，才能测到全链路
        $get = "GET /generate_204 HTTP/1.1`r`nHost: ${DstHost}`r`nConnection: close`r`n`r`n"
        $gb = [Text.Encoding]::ASCII.GetBytes($get)
        $s.Write($gb, 0, $gb.Length)
        $b1 = New-Object byte[] 64
        if ($s.Read($b1, 0, $b1.Length) -le 0) { return $null }
        $sw.Stop()
        return [Math]::Round($sw.Elapsed.TotalMilliseconds, 1)
    } catch { return $null } finally { $c.Close() }
}

function Update-Latency {
    # 统一口径：直连国内（223.5.5.5:443 TCP）/ 代理链路（到 cp.cloudflare.com 的真实请求，含握手）
    $lat = [ordered]@{}
    $lat['直连国内'] = Test-TcpMs -TargetHost '223.5.5.5' -TargetPort 443 -TimeoutMs 1200
    $proxyUp = Test-TcpMs -TargetHost '127.0.0.1' -TargetPort 7897 -TimeoutMs 500
    if ($proxyUp) {
        $v = Test-SocksMs -ProxyHost '127.0.0.1' -ProxyPort 7897 -DstHost 'cp.cloudflare.com' -DstPort 80 -TimeoutMs 8000
        # <5ms 视为本机伪响应（如本机代理直答），不报告以免误导
        if ($null -ne $v -and $v -ge 5) { $lat['代理链路'] = $v }
    }
    # 无可用本机代理时，若本机 Tailscale 可直达 Mac，则经 Mac 的 socks 测（如笔记本）
    if (-not $lat.Contains('代理链路')) {
        if (Test-TcpMs -TargetHost '100.105.59.31' -TargetPort 7891 -TimeoutMs 800) {
            $v2 = Test-SocksMs -ProxyHost '100.105.59.31' -ProxyPort 7891 -DstHost 'cp.cloudflare.com' -DstPort 80 -TimeoutMs 8000
            if ($null -ne $v2 -and $v2 -ge 5) { $lat['代理链路'] = $v2 }
        }
    }
    $script:Latency = $lat
}

$script:LinkCache = @{ t = 0; v = $null }

function Get-LinkMbps {
    # 默认路由网卡的链路速率（带宽上限），300s 缓存
    # （Get-NetAdapter/Get-NetRoute 是同步 WMI 调用，偶尔卡秒级；单线程主循环里卡住会让看门狗误判重启）
    $now = Get-Epoch
    if ($script:LinkCache.t -and ($now - $script:LinkCache.t) -lt 300) { return $script:LinkCache.v }
    $v = $null
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -First 1
        $ad = $null
        if ($route) { $ad = Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue }
        if ($ad -and $ad.LinkSpeed) {
            if ($ad.LinkSpeed -match '([\d.]+)\s*Gbps') { $v = [int]([double]$Matches[1] * 1000) }
            elseif ($ad.LinkSpeed -match '([\d.]+)\s*Mbps') { $v = [int][double]$Matches[1] }
        }
    } catch { }
    $script:LinkCache = @{ t = $now; v = $v }
    return $v
}

function Get-Snapshot {
    $queue = $null
    $sys = Get-CimInstance Win32_PerfFormattedData_PerfOS_System -OperationTimeoutSec 2 -ErrorAction SilentlyContinue
    if ($sys -and $sys.PSObject.Properties['ProcessorQueueLength']) { $queue = [int]$sys.ProcessorQueueLength }
    return [ordered]@{
        host      = $script:HostName
        model     = $script:Model
        platform  = 'windows'
        boot_time = $script:BootTime
        uptime_s  = $(if ($script:BootTime) { [int]((Get-Epoch) - $script:BootTime) } else { $null })
        ncpu      = $script:Ncpu
        cpu_pct   = (Get-CpuPct)
        queue     = $queue
        gpu       = @{ util_pct = (Get-GpuPct) }
        mem       = (Get-MemInfo)
        disk      = (Get-DiskInfo)
        net       = (Get-NetInfo)
        link_mbps = (Get-LinkMbps)
    }
}

# ---------------------------------------------------------------- raw http

function Get-RawHttp {
    param([string]$Url, [int]$TimeoutMs = 1500)
    if ($Url -notmatch '^http://([^/:]+)(?::(\d+))?(/.*)?$') { return $null }
    $h = $Matches[1]
    $p = 80
    if ($Matches[2]) { $p = [int]$Matches[2] }
    $path = '/'
    if ($Matches[3]) { $path = $Matches[3] }
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $c.BeginConnect($h, $p, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $null }
        $c.EndConnect($iar)
        $c.ReceiveTimeout = $TimeoutMs
        $s = $c.GetStream()
        $req = "GET $path HTTP/1.1`r`nHost: ${h}:${p}`r`nConnection: close`r`n`r`n"
        $rb = [Text.Encoding]::ASCII.GetBytes($req)
        $s.Write($rb, 0, $rb.Length)
        $ms = New-Object IO.MemoryStream
        $buf = New-Object byte[] 8192
        while ($true) {
            $n = $s.Read($buf, 0, $buf.Length)
            if ($n -le 0) { break }
            $ms.Write($buf, 0, $n)
        }
        $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
        $idx = $text.IndexOf("`r`n`r`n")
        if ($idx -lt 0) { return $null }
        return $text.Substring($idx + 4)
    } catch { return $null } finally { $c.Close() }
}

function Send-RawJson {
    param([string]$Url, [string]$Json, [int]$TimeoutMs = 4000)
    if ($Url -notmatch '^http://([^/:]+)(?::(\d+))?(/.*)?$') { return $false }
    $h = $Matches[1]
    $p = 80
    if ($Matches[2]) { $p = [int]$Matches[2] }
    $path = '/'
    if ($Matches[3]) { $path = $Matches[3] }
    $body = [Text.Encoding]::UTF8.GetBytes($Json)
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $c.BeginConnect($h, $p, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $c.EndConnect($iar)
        $c.ReceiveTimeout = $TimeoutMs
        $s = $c.GetStream()
        $head = "POST $path HTTP/1.1`r`nHost: ${h}:${p}`r`nContent-Type: application/json`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
        $hb = [Text.Encoding]::ASCII.GetBytes($head)
        $s.Write($hb, 0, $hb.Length)
        $s.Write($body, 0, $body.Length)
        $buf = New-Object byte[] 1024
        $null = $s.Read($buf, 0, $buf.Length)
        return $true
    } catch { return $false } finally { $c.Close() }
}

# ---------------------------------------------------------------- http server

function Write-Response {
    param($Stream, [int]$Code, [string]$ContentType, [byte[]]$Body)
    $reason = 'OK'
    switch ($Code) {
        204 { $reason = 'No Content' }
        400 { $reason = 'Bad Request' }
        404 { $reason = 'Not Found' }
        405 { $reason = 'Method Not Allowed' }
        500 { $reason = 'Internal Server Error' }
    }
    $head = "HTTP/1.1 $Code $reason`r`nContent-Type: $ContentType`r`nContent-Length: $($Body.Length)`r`n" +
            "Access-Control-Allow-Origin: *`r`nAccess-Control-Allow-Methods: GET, POST, OPTIONS`r`n" +
            "Access-Control-Allow-Headers: Content-Type`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
    $hb = [Text.Encoding]::ASCII.GetBytes($head)
    $Stream.Write($hb, 0, $hb.Length)
    if ($Body.Length -gt 0) { $Stream.Write($Body, 0, $Body.Length) }
    $Stream.Flush()
}

function Read-HttpRequest {
    param($Stream)
    $ms = New-Object IO.MemoryStream
    $buf = New-Object byte[] 4096
    $headerEnd = -1
    while ($headerEnd -lt 0) {
        $n = $Stream.Read($buf, 0, $buf.Length)
        if ($n -le 0) { return $null }
        $ms.Write($buf, 0, $n)
        $arr = $ms.ToArray()
        for ($i = 0; $i -le $arr.Length - 4; $i++) {
            if ($arr[$i] -eq 13 -and $arr[$i + 1] -eq 10 -and $arr[$i + 2] -eq 13 -and $arr[$i + 3] -eq 10) { $headerEnd = $i + 4; break }
        }
        if ($ms.Length -gt 262144) { return $null }
    }
    $arr = $ms.ToArray()
    $headerText = [Text.Encoding]::ASCII.GetString($arr, 0, $headerEnd)
    $lines = $headerText -split "`r`n"
    $reqLine = $lines[0] -split ' '
    if ($reqLine.Count -lt 2) { return $null }
    $headers = @{}
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $p = $lines[$i] -split ':', 2
        if ($p.Count -eq 2) { $headers[$p[0].Trim().ToLower()] = $p[1].Trim() }
    }
    $len = 0
    if ($headers.ContainsKey('content-length')) { [void][int]::TryParse($headers['content-length'], [ref]$len) }
    $body = New-Object byte[] $len
    $got = $arr.Length - $headerEnd
    if ($got -gt 0) { [Array]::Copy($arr, $headerEnd, $body, 0, [Math]::Min($got, $len)) }
    while ($got -lt $len) {
        $n = $Stream.Read($body, $got, $len - $got)
        if ($n -le 0) { break }
        $got += $n
    }
    return @{ method = $reqLine[0]; path = $reqLine[1]; headers = $headers; body = $body }
}

$script:Devices = @{}
$script:SelfMetrics = $null
$script:SelfSeen = 0
$script:SelfTsIp = $null
$script:MacMetrics = $null
$script:MacSeen = 0

function Get-SelfTsIp {
    $ts = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
    if (-not $ts) { $ts = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe' }
    if (-not (Test-Path -LiteralPath $ts)) { return $null }
    try {
        $ip = (& $ts ip -4 2>$null | Select-Object -First 1)
        if ($ip) { return $ip.Trim() }
    } catch { }
    return $null
}

function Get-TsLink {
    param($Tailnet, [string]$TsIp)
    if (-not $Tailnet -or -not $TsIp) { return $null }
    $p = $Tailnet.PSObject.Properties[$TsIp]
    if ($p) { return $p.Value }
    return $null
}

function Pull-Mac {
    $text = Get-RawHttp -Url 'http://100.105.59.31:18992/metrics' -TimeoutMs 1500
    if (-not $text) { return }
    try {
        $obj = $text | ConvertFrom-Json
        if ($obj.ts) {
            $script:MacMetrics = $obj
            $script:MacSeen = Get-Epoch
        }
    } catch { }
}

function Build-Devices {
    $now = Get-Epoch
    $list = New-Object System.Collections.ArrayList
    $selfMetrics = $script:SelfMetrics
    if ($selfMetrics) { $selfMetrics = $selfMetrics + @{ latency = $script:Latency } }
    $macTailnet = $null
    if ($script:MacMetrics -and $script:MacMetrics.PSObject.Properties['tailnet']) {
        $macTailnet = $script:MacMetrics.tailnet
    }
    [void]$list.Add(@{
        id = 'win-' + $script:HostName.ToLower(); name = $script:HostName; platform = 'windows'
        online = $true; last_seen = $script:SelfSeen; metrics = $selfMetrics
        link = (Get-TsLink $macTailnet $script:SelfTsIp)
    })
    if ($script:MacMetrics) {
        [void]$list.Add(@{
            id = 'mac-mini'; name = $script:MacMetrics.host; platform = 'macos'
            online = (($now - $script:MacSeen) -lt 15); last_seen = $script:MacSeen; metrics = $script:MacMetrics
            link = (Get-TsLink $macTailnet $script:SelfTsIp)
        })
    }
    foreach ($d in @($script:Devices.Values | Sort-Object { $_.id })) {
        [void]$list.Add(@{
            id = $d.id; name = $d.name; platform = $d.platform
            online = (($now - $d.last_seen) -lt 15); last_seen = $d.last_seen; metrics = $d.metrics
            link = (Get-TsLink $macTailnet $d.ts_ip)
        })
    }
    return @{ ts = $now; devices = $list }
}

function Handle-Request {
    param($Req, $Stream, $Remote)
    $path = ($Req.path -split '\?')[0]
    if ($Req.method -eq 'OPTIONS') { Write-Response $Stream 204 'text/plain' ([byte[]]@()); return 204 }
    if ($path -eq '/healthz') { Write-Response $Stream 200 'text/plain; charset=utf-8' ([Text.Encoding]::UTF8.GetBytes('ok')); return 200 }
    if (($path -eq '/' -or $path -eq '/m') -and $Req.method -eq 'GET') {
        $file = Join-Path $PSScriptRoot 'mobile.html'
        if (Test-Path -LiteralPath $file) {
            $bytes = [IO.File]::ReadAllBytes($file)
            Write-Response $Stream 200 'text/html; charset=utf-8' $bytes; return 200
        }
    }
    if ($path -eq '/devices' -and $Req.method -eq 'GET') {
        $json = (Build-Devices) | ConvertTo-Json -Depth 8 -Compress
        Write-Response $Stream 200 'application/json; charset=utf-8' ([Text.Encoding]::UTF8.GetBytes($json)); return 200
    }
    if ($path -eq '/metrics' -and $Req.method -eq 'GET') {
        $snap = $script:SelfMetrics
        if ($snap) { $snap = $snap + @{ latency = $script:Latency; ts = (Get-Epoch) } }
        $json = $snap | ConvertTo-Json -Depth 8 -Compress
        Write-Response $Stream 200 'application/json; charset=utf-8' ([Text.Encoding]::UTF8.GetBytes($json)); return 200
    }
    if ($path -eq '/ingest' -and $Req.method -eq 'POST') {
        $text = ''
        try {
            $text = [Text.Encoding]::UTF8.GetString($Req.body)
            $obj = $text | ConvertFrom-Json
            if ($obj.id -and $obj.metrics) {
                $script:Devices[[string]$obj.id] = @{
                    id = [string]$obj.id; name = [string]$obj.name; platform = [string]$obj.platform
                    last_seen = Get-Epoch; metrics = $obj.metrics
                    ts_ip = (($Remote -split ':')[0])
                }
                Write-Response $Stream 200 'application/json' ([Text.Encoding]::UTF8.GetBytes('{"ok":true}')); return 200
            }
            Write-ReqLog ('ingest 400 (no id/metrics): ' + $text.Substring(0, [Math]::Min(400, $text.Length)))
        } catch {
            Write-ReqLog ('ingest 400 (bad json): ' + $text.Substring(0, [Math]::Min(400, $text.Length)))
        }
        Write-Response $Stream 400 'application/json' ([Text.Encoding]::UTF8.GetBytes('{"ok":false}')); return 400
    }
    if ($path -like '/static/*') {
        $map = @{
            '/static/metrics-hub.ps1'  = (Join-Path $PSScriptRoot 'metrics-hub.ps1')
            '/static/install-agent.ps1' = (Join-Path $PSScriptRoot 'install-agent.ps1')
            '/static/phone-metrics.sh' = (Join-Path $PSScriptRoot 'phone-metrics.sh')
            '/static/mobile.html'      = (Join-Path $PSScriptRoot 'mobile.html')
            '/static/cmfa-custom.apk'  = 'C:\Users\22771\dev\dist\cmfa-custom.apk'
            '/static/phone-config.yaml' = 'C:\Users\22771\dev\dist\phone-config.yaml'
        }
        if ($map.ContainsKey($path) -and (Test-Path -LiteralPath $map[$path])) {
            $bytes = [IO.File]::ReadAllBytes($map[$path])
            $ct = 'text/plain; charset=utf-8'
            if ($path -like '*.apk') { $ct = 'application/vnd.android.package-archive' }
            elseif ($path -like '*.yaml') { $ct = 'text/yaml; charset=utf-8' }
            elseif ($path -like '*.html') { $ct = 'text/html; charset=utf-8' }
            Write-Response $Stream 200 $ct $bytes; return 200
        }
    }
    Write-Response $Stream 404 'text/plain' ([Text.Encoding]::UTF8.GetBytes('not found'))
    return 404
}

function Run-Hub {
    Initialize-Static
    $script:SelfMetrics = Get-Snapshot
    $script:SelfSeen = Get-Epoch
    $script:SelfTsIp = Get-SelfTsIp
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
    $listener.Start()
    Write-Host ("[hub] listening on 0.0.0.0:{0} (id={1})" -f $Port, ('win-' + $script:HostName.ToLower()))
    $lastFast = [DateTime]::UtcNow
    $lastLat = [DateTime]::MinValue
    $lastMac = [DateTime]::MinValue
    while ($true) {
        try {
            if ($listener.Pending()) {
                $client = $listener.AcceptTcpClient()
                $remote = ''
                try { $remote = $client.Client.RemoteEndPoint.ToString() } catch { }
                try {
                    $client.ReceiveTimeout = 5000
                    $client.SendTimeout = 5000
                    $stream = $client.GetStream()
                    $req = Read-HttpRequest $stream
                    if ($req) {
                        $code = Handle-Request $req $stream $remote
                        Write-ReqLog ("{0} {1} {2} -> {3}" -f $remote, $req.method, ($req.path -split '\?')[0], $code)
                    }
                } catch { } finally { $client.Close() }
            }
            $now = [DateTime]::UtcNow
            if (($now - $lastFast).TotalSeconds -ge 2) {
                $script:SelfMetrics = Get-Snapshot
                $script:SelfSeen = Get-Epoch
                $lastFast = $now
            }
            if (($now - $lastLat).TotalSeconds -ge 30) { Update-Latency; $lastLat = $now }
            if (($now - $lastMac).TotalSeconds -ge 5) { Pull-Mac; $lastMac = $now }
            Start-Sleep -Milliseconds 50
        } catch {
            Start-Sleep -Seconds 2
        }
    }
}

function Run-Agent {
    Initialize-Static
    if (-not $Id) { $Id = 'win-' + $script:HostName.ToLower() }
    if (-not $Name) { $Name = $script:HostName }
    Write-Host ("[agent] pushing to {0} as {1}" -f $Hub, $Id)
    $lastLat = [DateTime]::MinValue
    while ($true) {
        try {
            $now = [DateTime]::UtcNow
            if (($now - $lastLat).TotalSeconds -ge 10) { Update-Latency; $lastLat = $now }
            $snap = Get-Snapshot
            $snap['latency'] = $script:Latency
            $payload = @{
                id = $Id; name = $Name; platform = 'windows'
                ts = (Get-Epoch); metrics = $snap
            } | ConvertTo-Json -Depth 8 -Compress
            [void](Send-RawJson -Url ($Hub.TrimEnd('/') + '/ingest') -Json $payload)
        } catch { }
        Start-Sleep -Seconds $IntervalSec
    }
}

while ($true) {
    try {
        if ($Mode -eq 'hub') { Run-Hub } else { Run-Agent }
    } catch {
        Write-Host ("[fatal] {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 5
    }
}
