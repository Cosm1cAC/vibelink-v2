param(
    [switch]$RegisterTaskOnly,
    [switch]$UseStartup,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'config.ps1')
$scriptPath = Join-Path $PSScriptRoot 'ssh-proxy.ps1'
$pwshExe = Join-Path $PSHOME 'pwsh.exe'
$taskName = 'MacSocksProxy'

function Register-ProxyTask {
    $action = New-ScheduledTaskAction -Execute $pwshExe -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $scriptPath)
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Description 'SSH SOCKS5 proxy watchdog to macOS' -Force | Out-Null
}

function Install-StartupEntry {
    $startup = [Environment]::GetFolderPath('Startup')
    $vbs = Join-Path $startup 'MacSocksProxy.vbs'
    $cmdline = '"{0}" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{1}"' -f $pwshExe, $scriptPath
    $content = 'CreateObject("WScript.Shell").Run "' + $cmdline.Replace('"', '""') + '", 0, False'
    Set-Content -LiteralPath $vbs -Value $content -Encoding Unicode
    return $vbs
}

if ($RegisterTaskOnly) {
    Register-ProxyTask
    exit 0
}

$taskReady = $false
if (-not $UseStartup) {
    try {
        Register-ProxyTask
        $taskReady = $true
        Write-Output '[OK] Scheduled task registered.'
    } catch {
        Write-Output ('[!] Direct task registration failed: {0}' -f $_.Exception.Message)
        Write-Output '[..] Requesting elevation (UAC) ...'
        try {
            $elev = Start-Process -FilePath $pwshExe -Verb RunAs -Wait -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-RegisterTaskOnly')
            if ($elev.ExitCode -eq 0 -and (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue)) {
                $taskReady = $true
                Write-Output '[OK] Scheduled task registered with admin rights.'
            } else {
                Write-Output '[!] Elevated registration did not complete.'
            }
        } catch {
            Write-Output ('[!] Elevation failed or was cancelled: {0}' -f $_.Exception.Message)
        }
    }
}

if (-not $taskReady) {
    $vbs = Install-StartupEntry
    Write-Output ('[OK] Fallback startup entry installed: {0}' -f $vbs)
}

if (-not $NoStart) {
    if ($taskReady) {
        Start-ScheduledTask -TaskName $taskName
        Write-Output '[OK] Service started (watchdog running in background).'
    } else {
        Start-Process -FilePath 'wscript.exe' -ArgumentList @($vbs)
        Write-Output '[OK] Service started from startup entry.'
    }
}
