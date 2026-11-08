[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$SourceRoot,
  [string]$RuntimeRoot = (Join-Path $env:LOCALAPPDATA 'vibelink-mesh')
)
$ErrorActionPreference = 'Stop'
$SourceRoot = [IO.Path]::GetFullPath($SourceRoot)
if (!(Test-Path -LiteralPath (Join-Path $SourceRoot 'apps/desktop/src/main/vibelink/embeddedServer.cjs'))) { throw 'Prepare the fused source first.' }
& (Join-Path $PSScriptRoot 'Test-DesktopPackage.ps1')
$runtime = [IO.Path]::GetFullPath($RuntimeRoot)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$desktopRoot = Join-Path $runtime 'desktop'
$package = Join-Path $desktopRoot ('integration-' + $stamp)
$backup = Join-Path $desktopRoot ('entry-before-' + $stamp)
New-Item -ItemType Directory -Path $package,$backup -Force | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json') -Raw | ConvertFrom-Json
foreach ($file in $manifest.files) {
    $target = Join-Path $package $file.path
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file.path) -Destination $target
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'manifest.json') -Destination $package
foreach ($name in @('launch.json','Open-VibeLinkDesktop.ps1','Open-VibeLinkDesktop.vbs')) {
    $previous = Join-Path $desktopRoot $name
    if (Test-Path -LiteralPath $previous) { Copy-Item -LiteralPath $previous -Destination $backup }
}
@{sourceRoot=$SourceRoot; package=$package} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $desktopRoot 'launch.json') -Encoding UTF8
$wrapper = @'
$ErrorActionPreference = 'Stop'
$settings = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'launch.json') -Raw | ConvertFrom-Json
$binary = Join-Path $settings.sourceRoot 'node_modules\electron\dist\electron.exe'
# Reopen this checkout's existing window instead of recompiling on each click.
$running = @(Get-CimInstance Win32_Process -Filter "Name = 'electron.exe'" | Where-Object { $_.ExecutablePath -eq $binary })
foreach ($item in $running) {
    $window = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
    if ($window.MainWindowHandle -ne 0 -and $window.MainWindowTitle -in @('VibeLink','Cindy')) {
        $shell = New-Object -ComObject WScript.Shell
        $shell.AppActivate([int]$item.ProcessId) | Out-Null
        exit 0
    }
}
& (Join-Path $settings.package 'Start-VibeLinkDesktop.ps1') -SourceRoot $settings.sourceRoot -Profile vibelink -Region cn
'@
$openScript = Join-Path $desktopRoot 'Open-VibeLinkDesktop.ps1'
[IO.File]::WriteAllText($openScript, $wrapper, [Text.UTF8Encoding]::new($true))
$vbs = Join-Path $desktopRoot 'Open-VibeLinkDesktop.vbs'
$vbsBody = 'CreateObject("Wscript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""' + $openScript.Replace('"','""') + '""", 0, False'
[IO.File]::WriteAllText($vbs, $vbsBody, [Text.Encoding]::Unicode)
$cmd = Join-Path $runtime 'run-cindy-desktop.cmd'
if (Test-Path -LiteralPath $cmd) { Copy-Item -LiteralPath $cmd -Destination $backup }
[IO.File]::WriteAllText($cmd, ('@echo off' + "`r`n" + 'wscript.exe "%~dp0desktop\Open-VibeLinkDesktop.vbs"' + "`r`n"), [Text.Encoding]::ASCII)
$shell = New-Object -ComObject WScript.Shell
$folders = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))
foreach ($folder in $folders) {
    $target = Join-Path $folder 'VibeLink.lnk'
    if (Test-Path -LiteralPath $target) { Copy-Item -LiteralPath $target -Destination (Join-Path $backup ((Split-Path $folder -Leaf) + '-VibeLink.lnk')) }
    $link = $shell.CreateShortcut($target)
    $link.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
    $link.Arguments = '"' + $vbs + '"'
    $link.WorkingDirectory = $desktopRoot
    $link.Description = 'VibeLink: conversations, workspace, devices and connections'
    $icon = Join-Path $runtime 'bin\vibelink-gui.exe'
    if (Test-Path -LiteralPath $icon) { $link.IconLocation = $icon + ',0' }
    $link.Save()
}
$startupEntry = Join-Path ([Environment]::GetFolderPath('Startup')) 'vibelink.lnk'
if (Test-Path -LiteralPath $startupEntry) {
    $oldStartup = $shell.CreateShortcut($startupEntry)
    if ($oldStartup.TargetPath -eq (Join-Path $runtime 'bin\vibelink-gui.exe')) {
        # The existing logon task now opens the fused app. Preserve this obsolete
        # second autostart entry so login cannot bring back the replaced GUI.
        Move-Item -LiteralPath $startupEntry -Destination (Join-Path $backup 'Startup-VibeLink.lnk')
    }
}
Write-Host "Desktop and Start Menu entries installed. Previous entries: $backup"
Write-Host 'The existing CindyVibelinkDesktop logon task uses the updated launcher. Legacy relay remains disabled.'
