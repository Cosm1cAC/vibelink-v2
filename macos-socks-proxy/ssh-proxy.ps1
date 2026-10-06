param(
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'

if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'config.ps1' }
. $ConfigPath

$logDir = Join-Path $PSScriptRoot 'logs'
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$logFile = Join-Path $logDir ('proxy-{0:yyyyMMdd}.log' -f (Get-Date))

function Write-Log([string]$Message) {
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8
}

Get-ChildItem -LiteralPath $logDir -Filter '*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
    Remove-Item -Force -ErrorAction SilentlyContinue

$mutex = New-Object System.Threading.Mutex($false, $MutexName)
$acquired = $false
try { $acquired = $mutex.WaitOne(1000) } catch [System.Threading.AbandonedMutexException] { $acquired = $true }
if (-not $acquired) { Write-Log 'Another watchdog instance is already running. Exiting.'; exit 0 }

function Get-ProxySshProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($SshTarget) }
}

function Stop-ProxySsh {
    foreach ($p in Get-ProxySshProcesses) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Log ('Stopped ssh process {0}' -f $p.ProcessId)
    }
}

function Test-SocksPort {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $task = $client.ConnectAsync('127.0.0.1', $LocalPort)
        if ($task.Wait(2500) -and $client.Connected) { $client.Close(); return $true }
        $client.Close()
        return $false
    } catch { return $false }
}

function Test-SocksDeep {
    try {
        $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', $LocalPort)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 6000
        $stream.WriteTimeout = 6000
        $greeting = [byte[]]@(5, 1, 0)
        $stream.Write($greeting, 0, $greeting.Length)
        $reply = New-Object byte[] 2
        if (($stream.Read($reply, 0, 2)) -ne 2 -or $reply[0] -ne 5 -or $reply[1] -ne 0) { $client.Close(); return $false }
        $request = New-Object 'System.Collections.Generic.List[byte]'
        foreach ($b in @(5, 1, 0, 1)) { $request.Add([byte]$b) }
        foreach ($b in ([System.Net.IPAddress]::Parse($ProbeIP).GetAddressBytes())) { $request.Add([byte]$b) }
        $request.Add([byte]([int]$ProbePort -shr 8))
        $request.Add([byte]([int]$ProbePort -band 255))
        $buffer = $request.ToArray()
        $stream.Write($buffer, 0, $buffer.Length)
        $resp = New-Object byte[] 16
        $n = $stream.Read($resp, 0, 16)
        $ok = ($n -ge 2 -and $resp[0] -eq 5 -and $resp[1] -eq 0)
        $client.Close()
        return $ok
    } catch { return $false }
}

function Invoke-MacRelayCheck {
    try {
        $remote = 'msgs=""; netstat -an | grep -q "\.7891" || { launchctl kickstart -k gui/$(id -u)/com.socks-relay >/dev/null 2>&1 || launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist >/dev/null 2>&1; launchctl kickstart -k gui/$(id -u)/com.socks-relay >/dev/null 2>&1; msgs="$msgs relay"; }; launchctl list | grep -q com.ssh-fallback || { launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.ssh-fallback.plist >/dev/null 2>&1; launchctl kickstart -k gui/$(id -u)/com.ssh-fallback >/dev/null 2>&1; msgs="$msgs ssh-fallback"; }; launchctl list | grep -q com.ngrok-ssh || { launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.ngrok-ssh.plist >/dev/null 2>&1; launchctl kickstart -k gui/$(id -u)/com.ngrok-ssh >/dev/null 2>&1; msgs="$msgs ngrok"; }; launchctl list | grep -q com.mac-metrics || { launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.mac-metrics.plist >/dev/null 2>&1; launchctl kickstart -k gui/$(id -u)/com.mac-metrics >/dev/null 2>&1; msgs="$msgs mac-metrics"; }; launchctl list | grep -q com.phone-config-http || { launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.phone-config-http.plist >/dev/null 2>&1; launchctl kickstart -k gui/$(id -u)/com.phone-config-http >/dev/null 2>&1; msgs="$msgs phone-config-http"; }; curl -s --max-time 5 http://127.0.0.1:4040/api/tunnels | grep -q "tcp" || { launchctl kickstart -k gui/$(id -u)/com.ngrok-ssh >/dev/null 2>&1; msgs="$msgs ngrok-tunnel"; }; echo "RELCHECK:${msgs:-ok}"'
        $out = & ssh -o BatchMode=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -i $KeyPath $SshTarget $remote 2>&1 | Out-String
        $text = $out.Trim()
        if ($text -notmatch 'RELCHECK:ok') {
            Write-Log ('Mac relay check: {0}' -f ($text -replace '\s+', ' '))
        }
    } catch {
        Write-Log ('Mac relay check error: {0}' -f $_.Exception.Message)
    }
}

$sshExe = $SshExePath
if (-not $sshExe -or -not (Test-Path -LiteralPath $sshExe)) { $sshExe = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe' }

$sshArgs = @(
    '-N'
    '-D', [string]$LocalPort
    '-i', $KeyPath
    '-o', 'BatchMode=yes'
    '-o', 'IdentitiesOnly=yes'
    '-o', 'ExitOnForwardFailure=yes'
    '-o', 'ServerAliveInterval=30'
    '-o', 'ServerAliveCountMax=3'
    '-o', 'TCPKeepAlive=yes'
    '-o', 'StrictHostKeyChecking=accept-new'
    $SshTarget
)

Write-Log ('Watchdog starting. target={0} localPort={1}' -f $SshTarget, $LocalPort)
Stop-ProxySsh
Start-Sleep -Seconds 1

$proc = $null
$failCount = 0
$lastDeepCheck = Get-Date
$loopCount = 0

while ($true) {
    try {
        $restart = $false
        if ($null -eq $proc) {
            $restart = $true
        } elseif ($proc.HasExited) {
            Write-Log ('ssh exited with code {0}' -f $proc.ExitCode)
            $restart = $true
        } elseif (-not (Test-SocksPort)) {
            Write-Log 'SOCKS port is not listening'
            Stop-ProxySsh
            $proc = $null
            $restart = $true
        }

        if ($restart) {
            Stop-ProxySsh
            $listener = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($listener) {
                $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
                if ($owner -and $owner.ProcessName -notlike 'ssh*') {
                    Write-Log ('Port {0} is occupied by {1} (pid {2}); waiting 30s' -f $LocalPort, $owner.ProcessName, $owner.Id)
                    Start-Sleep -Seconds 30
                    continue
                }
            }
            if ($failCount -gt 0) {
                $delay = [int][Math]::Min(60, 5 * [Math]::Pow(2, [Math]::Min($failCount, 4)))
                Write-Log ('Retrying in {0}s (failCount={1})' -f $delay, $failCount)
                Start-Sleep -Seconds $delay
            }
            Write-Log 'Starting ssh tunnel'
            $proc = Start-Process -FilePath $sshExe -ArgumentList $sshArgs -PassThru -WindowStyle Hidden
            $up = $false
            for ($i = 0; $i -lt 15; $i++) {
                Start-Sleep -Seconds 1
                if ($proc.HasExited) { break }
                if (Test-SocksPort) { $up = $true; break }
            }
            if ($up) {
                Write-Log ('Tunnel is up (ssh pid {0})' -f $proc.Id)
                $failCount = 0
                $lastDeepCheck = Get-Date
            } else {
                Write-Log 'Tunnel failed to start'
                if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
                $proc = $null
                $failCount++
            }
        } elseif (((Get-Date) - $lastDeepCheck).TotalMinutes -ge $DeepCheckMinutes) {
            if (Test-SocksDeep) {
                $lastDeepCheck = Get-Date
            } else {
                Write-Log 'Deep connectivity check failed; restarting tunnel'
                Stop-ProxySsh
                $proc = $null
                $failCount++
            }
        }

        $loopCount++
        if ($loopCount -eq 6 -or ($loopCount % 30) -eq 0) {
            Invoke-MacRelayCheck
        }

        Start-Sleep -Seconds 10
    } catch {
        Write-Log ('Loop error: {0}' -f $_.Exception.Message)
        Start-Sleep -Seconds 10
    }
}
