param(
    [switch]$KeepPassword
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')

Write-Output '== opencode-mesh uninstall (Windows) =='

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output '[OK] scheduled task removed.'
}

$vbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'OpenCodeServer.vbs'
if (Test-Path $vbs) {
    Remove-Item -LiteralPath $vbs -Force
    Write-Output '[OK] startup entry removed.'
}

Get-CimInstance Win32_Process -Filter "name='pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*server.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Output '[OK] watchdog stopped.'

foreach ($port in @($ServerPort, $UpstreamPort)) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        foreach ($item in $conn) { Stop-Process -Id $item.OwningProcess -Force -ErrorAction SilentlyContinue }
        Write-Output ('[OK] listener on port {0} stopped.' -f $port)
    }
}

if (-not $KeepPassword -and (Test-Path $PasswordFile)) {
    Remove-Item -LiteralPath $PasswordFile -Force
    Write-Output '[OK] password file removed.'
}

Write-Output 'Done.'
