$ErrorActionPreference = 'SilentlyContinue'

function Test-Hub {
    $code = & curl.exe -sS -m 20 -x '' -o NUL -w '%{http_code}' 'http://127.0.0.1:18993/devices' 2>$null
    return ($code -eq '200')
}

for ($i = 0; $i -lt 3; $i++) {
    if (Test-Hub) { exit 0 }
    Start-Sleep -Seconds 10
}

Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object { $_.CommandLine -match 'metrics-hub\.ps1' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Stop-ScheduledTask -TaskName 'MetricsHub' -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Start-ScheduledTask -TaskName 'MetricsHub'
