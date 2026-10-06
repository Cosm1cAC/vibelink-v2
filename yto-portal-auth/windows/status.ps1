# yto-portal-auth / Windows 状态检查
$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
$scriptPath = Join-Path $Root 'portal-auth.ps1'
$ConfigFile = Join-Path $env:USERPROFILE '.config\yto-portal-auth\config.psd1'
$LogDir = Join-Path $Root 'logs'
$TaskName = 'YtoPortalAuth'

Write-Output '== yto-portal-auth status =='

if (Test-Path $ConfigFile) {
    $cfg = Import-PowerShellDataFile -LiteralPath $ConfigFile
    if ($cfg.Username -like 'REPLACE*') {
        Write-Output 'config:    template not filled'
    } else {
        Write-Output ('config:    filled (user: {0})' -f $cfg.Username)
    }
} else {
    Write-Output 'config:    missing (run install.ps1)'
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Write-Output ('task:      {0}' -f $task.State)
} else {
    Write-Output 'task:      not registered'
}

$vbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'YtoPortalAuth.vbs'
Write-Output ('startup:   {0}' -f (Test-Path $vbs))

$watchdog = Get-CimInstance Win32_Process -Filter "name='pwsh.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*portal-auth.ps1*' }
if ($watchdog) {
    Write-Output ('watchdog:  running (pid {0})' -f (($watchdog.ProcessId) -join ', '))
} else {
    Write-Output 'watchdog:  not found (may be elevated or stopped)'
}

$probe = & curl.exe -s -o NUL -w '%{http_code}' --max-time 8 --noproxy '*' 'http://connect.rom.miui.com/generate_204' 2>$null
Write-Output ('probe:     {0} (204=在线)' -f $probe)

if (Test-Path $LogDir) {
    Write-Output 'recent log:'
    Get-ChildItem $LogDir -Filter '*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 | ForEach-Object {
        Get-Content $_.FullName -Tail 8 | ForEach-Object { '  ' + $_ }
    }
}
