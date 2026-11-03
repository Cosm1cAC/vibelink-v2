# vibelink 看门狗：登录后常驻，hub / agent / tailscale 掉了就自动拉起
# 每 20 秒检查一次；不依赖计划任务（那个在部分机器上会被外部终止）
# 单实例保护：命名互斥体。不要用「扫命令行」——父 shell 的命令里也可能带脚本名。
$mutex = New-Object System.Threading.Mutex($false, 'Local\vibelink-watchdog')
if (-not $mutex.WaitOne(0)) { exit 0 }
$root = Join-Path $env:LOCALAPPDATA 'vibelink-mesh'
$log = Join-Path $root 'watchdog.log'
$hubExe = Join-Path $root 'bin\vibelink-hub.exe'
$agentExe = Join-Path $root 'bin\vibelink-agent.exe'
$tsExe = 'C:\Program Files\Tailscale\tailscale.exe'
$tsIpn = 'C:\Program Files\Tailscale\tailscale-ipn.exe'

function Say($m) {
    "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m | Out-File $log -Append -Encoding utf8
}

function Port-Alive([int]$port, [int]$timeoutMs = 800) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $ok = $c.ConnectAsync('127.0.0.1', $port).Wait($timeoutMs) -and $c.Connected
        $c.Dispose()
        return $ok
    } catch { return $false }
}

function Start-Hub {
    if (-not (Test-Path $hubExe)) { Say "hub exe missing"; return }
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$root\run-hub.cmd`"" -WindowStyle Hidden
    Say "started hub"
}

function Start-Agent {
    if (-not (Test-Path $agentExe)) { Say "agent exe missing"; return }
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$root\run-agent.cmd`"" -WindowStyle Hidden
    Say "started agent"
}

# Tailscale 在 Windows 上有个坑：后端（tailscaled）跟着 IPN 客户端走，
# 托盘 tailscale-ipn.exe 一退出，BackendState 就回到 NoState。
# 所以这里不仅要保证服务在跑，还要保证托盘进程在跑。
$script:lastTsNudge = [datetime]::MinValue
$script:lastTsWarn = [datetime]::MinValue

function Tailscale-State {
    if (-not (Test-Path $tsExe)) { return 'missing' }
    try {
        $raw = & $tsExe status --json 2>$null
        if (-not $raw) { return 'unknown' }
        return [string](($raw | ConvertFrom-Json).BackendState)
    } catch { return 'unknown' }
}

function Ensure-Tailscale {
    $state = Tailscale-State
    if ($state -eq 'Running' -or $state -eq 'missing') { return $state }

    if (-not (Get-Process tailscale-ipn -ErrorAction SilentlyContinue)) {
        if (Test-Path $tsIpn) {
            Start-Process $tsIpn -ErrorAction SilentlyContinue
            Say "started tailscale-ipn (state=$state)"
        }
        return $state
    }

    # 托盘在、后端却不在：轻推一下。每 5 分钟最多一次，避免和用户在 GUI 里的操作打架。
    if ($state -eq 'NoState' -and ((Get-Date) - $script:lastTsNudge).TotalMinutes -ge 5) {
        $script:lastTsNudge = Get-Date
        & $tsExe up 2>$null | Out-Null
        Say "tailscale up (state=$state)"
    }
    if ($state -eq 'NeedsLogin' -and ((Get-Date) - $script:lastTsWarn).TotalMinutes -ge 30) {
        $script:lastTsWarn = Get-Date
        Say "tailscale needs login: run 'tailscale up --force-reauth'"
    }
    return $state
}

Say "watchdog up"
$hubMiss = 0
$lastTsState = ''
while ($true) {
    $hub = Get-Process vibelink-hub -ErrorAction SilentlyContinue
    if (-not $hub) {
        Start-Hub
        Start-Sleep 5
        $hubMiss = 0
    } elseif (-not (Port-Alive 18995)) {
        $hubMiss++
        Say "hub pid=$($hub.Id) port 18995 not answering ($hubMiss)"
        if ($hubMiss -ge 3) {
            Say "hub hung, restarting"
            $hub | Stop-Process -Force
            Start-Sleep 2
            Start-Hub
            $hubMiss = 0
        }
    } else {
        $hubMiss = 0
    }

    if (-not (Get-Process vibelink-agent -ErrorAction SilentlyContinue)) {
        Start-Agent
    }

    $tsState = Ensure-Tailscale
    if ($tsState -ne $lastTsState) {
        Say "tailscale: $lastTsState -> $tsState"
        $lastTsState = $tsState
    }

    Start-Sleep -Seconds 20
}
