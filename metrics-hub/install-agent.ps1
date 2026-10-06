# 在第二台 Windows 主机上运行（普通权限即可，无需管理员）：
#   powershell -NoProfile -Command "[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy; irm http://100.74.71.80:18993/static/install-agent.ps1 | iex"
#
# 作用：下载 metrics-hub.ps1 到 ~\.metrics-agent\，通过「启动文件夹」实现登录自启（免管理员），并立即启动。

$ErrorActionPreference = 'Stop'
$hub = 'http://100.74.71.80:18993'
$dir = Join-Path $env:USERPROFILE '.metrics-agent'
New-Item -ItemType Directory -Path $dir -Force | Out-Null

# 避免系统代理劫持发往 Tailscale 网段的请求
[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy

$scriptPath = Join-Path $dir 'metrics-hub.ps1'
Invoke-WebRequest -Uri "$hub/static/metrics-hub.ps1" -OutFile $scriptPath -UseBasicParsing
if (-not (Test-Path -LiteralPath $scriptPath)) { throw '下载 metrics-hub.ps1 失败' }

$ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

# 登录自启（启动文件夹，普通权限可写）
$startup = [Environment]::GetFolderPath('Startup')
$cmdPath = Join-Path $startup 'metrics-agent.cmd'
$line = '@echo off' + "`r`n" + 'start "" /min "' + $ps + '" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $scriptPath + '" -Mode agent -Hub ' + $hub
Set-Content -LiteralPath $cmdPath -Value $line -Encoding ASCII

# 结束旧的 agent 进程（避免新旧两个进程同时上报）
Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -like '*metrics-hub.ps1*' -and $_.CommandLine -like '*-Mode agent*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 500

# 立即启动
Start-Process -FilePath $ps -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden',
    '-File', $scriptPath, '-Mode', 'agent', '-Hub', $hub
) -WindowStyle Hidden

Write-Host '[OK] MetricsAgent 已安装并启动（登录自启：启动文件夹 metrics-agent.cmd）'
Write-Host '     卸载:'
Write-Host ('     Remove-Item -Force "' + $cmdPath + '"')
Write-Host ('     Remove-Item -Recurse -Force "' + $dir + '"')
Write-Host '     Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like ''*-Mode agent*'' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }'
