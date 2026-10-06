# yto-portal-auth / Windows 卸载
param(
    [switch]$KeepConfig
)

$ErrorActionPreference = 'Continue'
$TaskName = 'YtoPortalAuth'
$ConfigDir = Join-Path $env:USERPROFILE '.config\yto-portal-auth'

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output '[OK] scheduled task removed.'
}

$vbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'YtoPortalAuth.vbs'
if (Test-Path $vbs) {
    Remove-Item -LiteralPath $vbs -Force
    Write-Output '[OK] startup entry removed.'
}

Get-CimInstance Win32_Process -Filter "name='pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*portal-auth.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Output '[OK] watchdog stopped.'

if (-not $KeepConfig -and (Test-Path $ConfigDir)) {
    Remove-Item -LiteralPath $ConfigDir -Recurse -Force
    Write-Output '[OK] config removed.'
}

Write-Output 'Done.'
