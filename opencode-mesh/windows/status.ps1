# opencode-mesh / Windows 状态检查
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')

Write-Output '== opencode-mesh status (Windows) =='

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Write-Output ('task:      {0} (last run: {1})' -f $task.State, $info.LastRunTime)
} else {
    Write-Output 'task:      not registered'
}

$vbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'OpenCodeServer.vbs'
Write-Output ('startup:   {0}' -f (Test-Path $vbs))

$watchdog = Get-CimInstance Win32_Process -Filter "name='pwsh.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*server.ps1*' }
if ($watchdog) {
    Write-Output ('watchdog:  running (pid {0})' -f (($watchdog.ProcessId) -join ', '))
} else {
    Write-Output 'watchdog:  not found (may be elevated or stopped)'
}

foreach ($item in @(@{ Port = $ServerPort; Label = 'proxy' }, @{ Port = $UpstreamPort; Label = 'opencode' })) {
    $conn = Get-NetTCPConnection -LocalPort $item.Port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        Write-Output ('{0,-9}  {1}:{2} pid={3} ({4})' -f ($item.Label + ':'), $conn.LocalAddress, $item.Port, $conn.OwningProcess, $proc.ProcessName)
    } else {
        Write-Output ('{0,-9}  not listening (port {1})' -f ($item.Label + ':'), $item.Port)
    }
}

if (Test-Path $PasswordFile) {
    $password = (Get-Content -LiteralPath $PasswordFile -Raw).Trim()
    $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:' + $password))
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$ServerPort/global/health" -Headers @{ Authorization = $auth } -NoProxy -UseBasicParsing -TimeoutSec 5
        Write-Output ('health:    {0}' -f $r.Content)
    } catch {
        Write-Output ('health:    FAILED ({0})' -f $_.Exception.Message)
    }
    try {
        $html = (Invoke-WebRequest -Uri "http://127.0.0.1:$ServerPort/" -Headers @{ Authorization = $auth } -NoProxy -UseBasicParsing -TimeoutSec 5).Content
        Write-Output ('badge:     {0}' -f ($html -match '__mesh/badge.js'))
    } catch {
        Write-Output 'badge:     FAILED'
    }
    Write-Output ('password:  {0}' -f $PasswordFile)
    Write-Output ('url:       http://100.98.206.126:{0}/  (user: opencode)' -f $ServerPort)
} else {
    Write-Output 'password:  not generated (run install.ps1)'
}
