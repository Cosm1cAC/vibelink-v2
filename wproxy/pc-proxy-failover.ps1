# One-shot PC proxy failover; intended for the existing PcProxyFailover task.
# Stage separately, then install as C:\Users\22771\bin\pc-proxy-failover.ps1.
# -ProbeOnly performs discovery/health checks without changing registry or files.
# -DiscoverOnly lists known candidates without health checks or any changes.
[CmdletBinding()]
param(
    [switch]$ProbeOnly,
    [switch]$DiscoverOnly,
    [string]$StateDirectory
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($StateDirectory)) {
    # Windows PowerShell 5.1 does not populate $PSScriptRoot in param defaults.
    $StateDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$LaptopMac = '20C19B828E44'
$LaptopPort = 7897
$LocalProxy = '127.0.0.1:7897'
$RegKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$LogFile = Join-Path $StateDirectory 'pc-proxy-failover.log'
$StateFile = Join-Path $StateDirectory 'pc-proxy-failover.fails'
$CacheFile = Join-Path $StateDirectory 'pc-proxy-failover.addresses.json'
$Clock = [Diagnostics.Stopwatch]::StartNew()
$BudgetSeconds = 45
$Candidates = New-Object 'System.Collections.Generic.List[string]'
$Attempted = New-Object 'System.Collections.Generic.HashSet[string]'
$Curl = (Get-Command curl.exe -ErrorAction Stop).Source

function Write-FailoverLog([string]$Message) {
    if (-not ($ProbeOnly -or $DiscoverOnly)) {
        "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message" |
            Add-Content -LiteralPath $LogFile -Encoding ASCII
    }
}

# 本机所在的 /24 网段（不再写死 192.168.137.x——换路由器就失效）
$script:LocalNets = @()
foreach ($addr in (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue)) {
    if ($addr.IPAddress -eq '127.0.0.1' -or $addr.IPAddress -like '169.254.*') { continue }
    $parts = $addr.IPAddress -split '\.'
    if ($parts.Count -eq 4) {
        $net = "$($parts[0]).$($parts[1]).$($parts[2])"
        if ($script:LocalNets -notcontains $net) { $script:LocalNets += $net }
    }
}

function Test-LanAddress([string]$Address) {
    if ($Address -notmatch '^(\d+\.\d+\.\d+)\.(\d{1,3})$') { return $false }
    $hostPart = [int]$Matches[2]
    if ($hostPart -lt 2 -or $hostPart -gt 254) { return $false }
    return $script:LocalNets -contains $Matches[1]
}
function Add-Candidate([string]$Address) {
    if (((Test-LanAddress $Address) -or $Address -eq '100.123.253.85') -and
        -not $Candidates.Contains($Address)) {
        $Candidates.Add($Address)
    }
}

function Add-MacNeighbors {
    # ARP is local discovery: it never uses the Windows or environment proxy.
    $neighbors = @(Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            ($_.LinkLayerAddress -replace '[:-]', '') -eq $LaptopMac -and
            (Test-LanAddress $_.IPAddress)
        } | Sort-Object @{ Expression = { $_.State -eq 'Reachable' }; Descending = $true })
    foreach ($neighbor in $neighbors) { Add-Candidate $neighbor.IPAddress }
}

function Find-LaptopNeighbors {
    # Refresh ARP only when the known addresses all failed. Concurrent ICMP is
    # bounded to the hotspot /24 and does not alter interfaces or static ARP.
    $pings = New-Object 'System.Collections.Generic.List[object]'
    try {
        for ($suffix = 2; $suffix -le 254; $suffix++) {
            $ping = New-Object System.Net.NetworkInformation.Ping
            try {
                $task = $ping.SendPingAsync("192.168.137.$suffix", 500)
                $pings.Add([pscustomobject]@{ Client = $ping; Task = $task })
            } catch { $ping.Dispose() }
        }
        if ($pings.Count -gt 0) {
            try {
                [void][Threading.Tasks.Task]::WaitAll(
                    [Threading.Tasks.Task[]]@($pings | ForEach-Object { $_.Task }), 1500)
            } catch { }
        }
    } finally {
        foreach ($item in $pings) { $item.Client.Dispose() }
    }
    Add-MacNeighbors
}

function Test-TcpPort([string]$Address) {
    $client = New-Object System.Net.Sockets.TcpClient
    $waitHandle = $null
    try {
        $pending = $client.BeginConnect($Address, $LaptopPort, $null, $null)
        $waitHandle = $pending.AsyncWaitHandle
        if (-not $waitHandle.WaitOne(700)) { return $false }
        $client.EndConnect($pending)
        return $client.Connected
    } catch { return $false }
    finally {
        $client.Close()
        if ($waitHandle) { $waitHandle.Close() }
    }
}

function Test-ProxyHealth([string]$Address) {
    if ($Clock.Elapsed.TotalSeconds -ge ($BudgetSeconds - 1)) { return $false }
    if (-not (Test-TcpPort $Address)) { return $false }
    # The China endpoint goes DIRECT through mihomo (GEOSITE/GEOIP cn), so it
    # still answers while the HY2 upstream is saturated by a large download.
    # The two HTTPS endpoints confirm the proxied path when that one is blocked.
    foreach ($url in @('http://connect.rom.miui.com/generate_204',
            'https://www.gstatic.com/generate_204', 'https://cp.cloudflare.com/generate_204')) {
        $remaining = [int][Math]::Floor($BudgetSeconds - $Clock.Elapsed.TotalSeconds)
        if ($remaining -lt 1) { return $false }
        $timeout = [Math]::Min(5, $remaining)
        # Explicit proxy avoids Windows auto-proxy. Explicit noproxy overrides
        # NO_PROXY; a reserved .invalid name ensures the real target is proxied.
        # Do not use --insecure: a listening port alone is not a healthy proxy.
        $status = & $Curl --disable --silent --output NUL --write-out '%{http_code}' `
            --connect-timeout 2 --max-time $timeout --retry 0 `
            --proxy "http://${Address}:$LaptopPort" --noproxy 'never-bypass.invalid' `
            --url $url 2>$null
        if ($LASTEXITCODE -eq 0 -and "$status" -eq '204') { return $true }
    }
    return $false
}

function Find-HealthyLaptop {
    # LAN first, Tailscale last: both reach the laptop, but the hotspot link is
    # about twice as fast as Tailscale's direct path (2026-10-27). The cache may
    # hold Tailscale first after a recovery, so order by kind rather than by the
    # order candidates were discovered.
    $lan = @($Candidates.ToArray() | Where-Object { Test-LanAddress $_ })
    $other = @($Candidates.ToArray() | Where-Object { -not (Test-LanAddress $_) })
    foreach ($address in @($lan + $other)) {
        if ($Clock.Elapsed.TotalSeconds -ge ($BudgetSeconds - 7)) { break }
        if (-not $Attempted.Add($address)) { continue }
        $healthy = Test-ProxyHealth $address
        if ($ProbeOnly) { Write-Information "candidate=$address healthy=$healthy" -InformationAction Continue }
        if ($healthy) { return $address }
    }
    return $null
}

function Set-SystemProxy([string]$Server) {
    if (-not ('PcProxyFailover.Native' -as [type])) {
        Add-Type -Namespace PcProxyFailover -Name Native -MemberDefinition @'
[DllImport("wininet.dll", SetLastError=true)]
public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);
'@
    }
    Set-ItemProperty -LiteralPath $RegKey -Name ProxyServer -Value $Server -Type String
    Set-ItemProperty -LiteralPath $RegKey -Name ProxyEnable -Value 1 -Type DWord
    [void][PcProxyFailover.Native]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0)
    [void][PcProxyFailover.Native]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)
}

# Exact MAC matches are preferred to cached or historic addresses.
Add-MacNeighbors
if (Test-Path -LiteralPath $CacheFile) {
    try {
        $cached = Get-Content -LiteralPath $CacheFile -Raw | ConvertFrom-Json
        # Bound the cache so corrupt/stale data cannot exhaust the probe budget.
        foreach ($address in @($cached.Addresses | Select-Object -First 3)) {
            Add-Candidate ([string]$address)
        }
    } catch { Write-FailoverLog 'address cache unreadable; using discovery' }
}
Add-Candidate '100.123.253.85'
Add-Candidate '192.168.137.104'
Add-Candidate '192.168.137.24'

if ($DiscoverOnly) {
    $Candidates.ToArray()
    return
}

$fails = 0
if (Test-Path -LiteralPath $StateFile) {
    $raw = (Get-Content -LiteralPath $StateFile -Raw).Trim()
    [void][int]::TryParse($raw, [ref]$fails)
    $fails = [Math]::Max(0, [Math]::Min($fails, 1000))
}

# A LAN target whose port still accepts TCP is kept even when the HTTP probe is
# slow under a large download: the local fallback is an order of magnitude
# slower, and mihomo's own AUTO group handles upstream failover. Only this
# cheap liveness check is used for the current LAN target; discovery and the
# 204 probe still decide a new one.
$currentServer = (Get-ItemProperty -LiteralPath $RegKey).ProxyServer
$currentAddress = @($currentServer -split ':')[0]
$laptop = $null
if ((Test-LanAddress $currentAddress) -and (Test-TcpPort $currentAddress)) {
    $laptop = $currentAddress
    if ($ProbeOnly) { Write-Information "keeping current LAN target=$currentAddress" -InformationAction Continue }
} else {
    # Capture only the address as a result; probe diagnostics go to the
    # information stream so they cannot accidentally become a target address.
    $laptop = Find-HealthyLaptop
    if (-not $laptop -and $Clock.Elapsed.TotalSeconds -lt ($BudgetSeconds - 9)) {
        Find-LaptopNeighbors
        $laptop = Find-HealthyLaptop
    }
}

$target = $null
if ($laptop) {
    $fails = 0
    $target = "${laptop}:$LaptopPort"
} else {
    $fails++
    if ($fails -ge 2 -and (Test-ProxyHealth '127.0.0.1')) {
        $target = $LocalProxy
    }
}

if ($ProbeOnly) {
    Write-Output "proposedTarget=$target consecutiveLaptopFailures=$fails (no changes)"
    return
}

Set-Content -LiteralPath $StateFile -Value $fails -Encoding ASCII
if ($laptop) {
    $addresses = @($laptop) + @($Candidates.ToArray() | Where-Object { $_ -ne $laptop } | Select-Object -First 2)
    @{ Addresses = $addresses; UpdatedUtc = [DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath $CacheFile -Encoding ASCII
}
if ($target) {
    $current = Get-ItemProperty -LiteralPath $RegKey
    if ($current.ProxyServer -ne $target -or $current.ProxyEnable -ne 1) {
        Set-SystemProxy $target
        Write-FailoverLog "system proxy -> $target (204 health verified)"
    }
} elseif ($fails -ge 2) {
    Write-FailoverLog 'no healthy proxy verified; preserving current system proxy'
}


