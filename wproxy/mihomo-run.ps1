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

function Write-Log([string]$Message) {
    Add-Content -LiteralPath $logFile -Value ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) -Encoding UTF8
}

$mutex = New-Object System.Threading.Mutex($false, 'WProxyWatchdog')
$acquired = $false
try { $acquired = $mutex.WaitOne(1000) } catch [System.Threading.AbandonedMutexException] { $acquired = $true }
if (-not $acquired) { Write-Log 'Another watchdog instance is running. Exiting.'; exit 0 }

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

Write-Log ('Watchdog starting. dir={0}' -f $WproxyDir)
$stopRequest = Join-Path $WproxyDir 'stop.request'
Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
Stop-Mihomo
Start-Sleep -Seconds 1

$proc = $null

while ($true) {
    try {
        if (Test-Path -LiteralPath $stopRequest) {
            Write-Log 'stop.request detected; stopping mihomo and exiting'
            Stop-Mihomo
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

        Start-Sleep -Seconds 10
    } catch {
        Write-Log ('Loop error: {0}' -f $_.Exception.Message)
        Start-Sleep -Seconds 10
    }
}
