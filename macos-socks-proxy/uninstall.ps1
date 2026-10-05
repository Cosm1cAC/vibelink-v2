$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')
$taskName = 'MacSocksProxy'

try {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction Stop
    Write-Output '[OK] Scheduled task removed.'
} catch {
    Write-Output ('[!] Task not removed (if it exists, run this script as admin): {0}' -f $_.Exception.Message)
}

$vbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'MacSocksProxy.vbs'
if (Test-Path -LiteralPath $vbs) {
    Remove-Item -LiteralPath $vbs -Force
    Write-Output '[OK] Startup entry removed.'
}

Get-CimInstance Win32_Process -Filter "Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*ssh-proxy.ps1*' } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Output ('[OK] Stopped watchdog pid {0}' -f $_.ProcessId)
    }

Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($SshTarget) } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Output ('[OK] Stopped ssh pid {0}' -f $_.ProcessId)
    }

Write-Output 'Done.'
