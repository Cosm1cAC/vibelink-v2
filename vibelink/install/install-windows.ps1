<#
.SYNOPSIS
  Install / uninstall the vibelink hub + agent (+ optional GUI autostart) on Windows.

.DESCRIPTION
  Layout (per user):
    %LOCALAPPDATA%\vibelink-mesh\bin\vibelink-hub.exe
    %LOCALAPPDATA%\vibelink-mesh\bin\vibelink-agent.exe
    %LOCALAPPDATA%\vibelink-mesh\bin\vibelink-gui.exe
    %LOCALAPPDATA%\vibelink-mesh\run-hub.cmd
    %LOCALAPPDATA%\vibelink-mesh\run-agent.cmd
    %LOCALAPPDATA%\vibelink-mesh\data\            (hub state: token, devices, configs)
  Tasks: VibelinkHub, VibelinkAgent (logon, run as the user, restart 3x/1min).

.EXAMPLES
  # hub host + agent on the same PC
  .\install-windows.ps1 -Device pc

  # client machine (laptop): no hub, point at the PC
  .\install-windows.ps1 -NoHub -HubUrl http://192.168.137.1:18995 -Token <token> -Device laptop

  # uninstall (keeps data)
  .\install-windows.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$HubUrl = 'http://127.0.0.1:18995',
    [int]$HubPort = 18995,
    [string]$Device = '',
    [string]$Token = '',
    [string]$RepoPath = '',
    [string]$FromDir = '',
    [switch]$NoHub,
    [switch]$GuiStartup,
    [switch]$Uninstall,
    [switch]$Purge,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$Root = Join-Path $env:LOCALAPPDATA 'vibelink-mesh'
$Bin = Join-Path $Root 'bin'
$Data = Join-Path $Root 'data'
$Tasks = @('VibelinkHub', 'VibelinkAgent')

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Elevate {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'))
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
        if ($kv.Value -is [switch]) {
            if ($kv.Value.IsPresent) { $argList += ('-' + $kv.Key) }
        } else {
            $argList += @(('-' + $kv.Key), ('"' + $kv.Value + '"'))
        }
    }
    Write-Host '需要管理员权限，正在请求提权…' -ForegroundColor Yellow
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $argList
    exit 0
}

if (-not $DryRun -and -not (Test-Admin)) { Elevate }

function Resolve-Repo {
    if ($RepoPath) { return (Resolve-Path $RepoPath).Path }
    $candidate = Join-Path $PSScriptRoot '..'
    return (Resolve-Path $candidate).Path
}

function Stop-Task([string]$name) {
    $t = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    if ($t) {
        Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
        Write-Host "  已移除任务 $name"
    }
}

function Install-Task([string]$name, [string]$script) {
    if ($DryRun) { Write-Host "  [dry-run] 注册任务 $name -> $script"; return }
    $act = New-ScheduledTaskAction -Execute 'C:\Windows\System32\wscript.exe' -Argument ('"' + (Join-Path $Root 'run-hidden.vbs') + '" "' + $script + '"')
    $trg = New-ScheduledTaskTrigger -AtLogOn
    $set = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
    $prn = New-ScheduledTaskPrincipal -UserId $env:USERNAME -RunLevel Highest -LogonType Interactive
    Register-ScheduledTask -TaskName $name -Action $act -Trigger $trg -Settings $set -Principal $prn -Force | Out-Null
    Write-Host "  已注册任务 $name"
}

# ---------------- uninstall ----------------
if ($Uninstall) {
    Write-Host '卸载 vibelink…'
    foreach ($t in $Tasks) { Stop-Task $t }
    foreach ($name in 'vibelink.lnk', 'mesh-gui.lnk') {
        $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) $name
        if (Test-Path $lnk) { Remove-Item $lnk -Force; Write-Host "  已移除 GUI 自启 $name" }
    }
    if ($Purge -and (Test-Path $Root)) { Remove-Item $Root -Recurse -Force; Write-Host "  已删除 $Root" }
    else { Write-Host "  保留数据目录 $Root（加 -Purge 可删除）" }
    exit 0
}

# ---------------- install ----------------
$release = if ($FromDir) { (Resolve-Path $FromDir).Path } else { Join-Path (Resolve-Repo) 'target\release' }
$needed = @('vibelink-agent.exe')
if (-not $NoHub) { $needed += 'vibelink-hub.exe' }
foreach ($exe in $needed) {
    if (-not (Test-Path (Join-Path $release $exe))) {
        Write-Host "缺少 $exe（来源 $release）" -ForegroundColor Red
        exit 1
    }
}

New-Item -ItemType Directory -Force $Bin, $Data | Out-Null

# remove the pre-rename tasks if they are still around
foreach ($legacy in 'MeshHub', 'MeshAgent') {
    $t = Get-ScheduledTask -TaskName $legacy -ErrorAction SilentlyContinue
    if ($t) {
        Stop-ScheduledTask -TaskName $legacy -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $legacy -Confirm:$false
        Write-Host "  已移除旧任务 $legacy"
    }
}
Get-Process mesh-hub, mesh-agent -ErrorAction SilentlyContinue | Stop-Process -Force
foreach ($exe in 'vibelink-hub.exe', 'vibelink-agent.exe', 'vibelink-gui.exe', 'vibelink.ico') {
    $src = Join-Path $release $exe
    if (Test-Path $src) { Copy-Item $src (Join-Path $Bin $exe) -Force }
}
Write-Host "已安装到 $Bin"

# hidden launcher: wscript run-hidden.vbs "<cmd file>"
$vbs = @'
Dim sh, cmd
Set sh = CreateObject("WScript.Shell")
cmd = """" & WScript.Arguments(0) & """"
sh.Run "cmd /c " & cmd, 0, True
'@
Set-Content -Path (Join-Path $Root 'run-hidden.vbs') -Value $vbs -Encoding ASCII

# hub launcher
$hubCmd = @"
@echo off
set "VIBELINK_PORT=$HubPort"
set "VIBELINK_DATA=$Data"
"$Bin\vibelink-hub.exe" >> "$Data\hub.log" 2>&1
"@
Set-Content -Path (Join-Path $Root 'run-hub.cmd') -Value $hubCmd -Encoding ASCII

if (-not $NoHub) {
    Install-Task 'VibelinkHub' (Join-Path $Root 'run-hub.cmd')
    if (-not $DryRun) { Start-ScheduledTask -TaskName 'VibelinkHub' }
    Write-Host '  等待 hub 生成 token…'
    for ($i = 0; $i -lt 20 -and -not (Test-Path (Join-Path $Data 'token.txt')); $i++) { Start-Sleep -Milliseconds 500 }
}

if (-not $Token) {
    $tf = Join-Path $Data 'token.txt'
    if (Test-Path $tf) { $Token = (Get-Content $tf -Raw).Trim() }
}
if (-not $Token) {
    Write-Host '没有 token：客户端机器请用 -Token 指定 hub 的 token' -ForegroundColor Red
    exit 1
}
if (-not $Device) { $Device = $env:COMPUTERNAME.ToLower() }

$agentCmd = @"
@echo off
set "VIBELINK_HUB=$HubUrl"
set "VIBELINK_TOKEN=$Token"
set "VIBELINK_DEVICE=$Device"
"$Bin\vibelink-agent.exe"
"@
# 不要在这里 `>>` 重定向：agent 自己写日志（而且命令行重定向的句柄会被它拉起的
# opencode 继承，日志文件被锁死之后 agent 再也起不来，自更新换完版本就失联）。
Set-Content -Path (Join-Path $Root 'run-agent.cmd') -Value $agentCmd -Encoding ASCII

Install-Task 'VibelinkAgent' (Join-Path $Root 'run-agent.cmd')
if (-not $DryRun) { Start-ScheduledTask -TaskName 'VibelinkAgent' }

if ($GuiStartup) {
    $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'vibelink.lnk'
    $s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
    $s.TargetPath = Join-Path $Bin 'vibelink-gui.exe'
    $s.Arguments = '--minimized'
    $s.IconLocation = (Join-Path $Bin 'vibelink.ico') + ',0'
    $s.WorkingDirectory = $Bin
    $s.Save()
    Write-Host '  已添加 GUI 开机自启（最小化到托盘）'
}

# Start menu shortcut, so "vibelink" is searchable from the Start menu
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
New-Item -ItemType Directory -Force $startMenu | Out-Null
$sm = Join-Path $startMenu 'vibelink.lnk'
$s2 = (New-Object -ComObject WScript.Shell).CreateShortcut($sm)
$s2.TargetPath = Join-Path $Bin 'vibelink-gui.exe'
$s2.IconLocation = (Join-Path $Bin 'vibelink.ico') + ',0'
$s2.WorkingDirectory = $Bin
$s2.Description = 'vibelink 多设备控制面'
$s2.IconLocation = (Join-Path $Bin 'vibelink-gui.exe') + ',0'
$s2.Save()
Write-Host "  已创建开始菜单快捷方式 $sm"

# disable the old Node-based startup shortcuts, otherwise both agents run
$startup = [Environment]::GetFolderPath('Startup')
if ($DryRun) { Write-Host '  [dry-run] 跳过停用旧自启'; $startup = $null }
if ($startup) { foreach ($old in 'mesh-hub.lnk', 'mesh-agent.lnk', 'phone-config-http.lnk') {
    $p = Join-Path $startup $old
    if (Test-Path $p) {
        Move-Item $p ($p + '.disabled') -Force
        Write-Host "  已停用旧自启 $old"
    }
} }

Start-Sleep -Seconds 3
Write-Host ''
Write-Host '安装完成：'
Get-ScheduledTask -TaskName $Tasks -ErrorAction SilentlyContinue | ForEach-Object {
    $info = Get-ScheduledTaskInfo -TaskName $_.TaskName
    '  {0,-10} {1,-9} last={2} result={3}' -f $_.TaskName, $_.State, $info.LastRunTime, $info.LastTaskResult
}
foreach ($p in (Get-Process vibelink-hub, vibelink-agent -ErrorAction SilentlyContinue)) {
    '  {0,-10} pid={1} rss={2}MB' -f $p.ProcessName, $p.Id, [math]::Round($p.WorkingSet64 / 1MB, 1)
}
Write-Host ''
Write-Host "Hub:   $HubUrl  (token: $Token)"
Write-Host "Agent: $Device"










