[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$SourceRoot,
  [string]$RuntimeRoot = (Join-Path $env:LOCALAPPDATA 'vibelink-mesh')
)
$ErrorActionPreference = 'Stop'
$notificationAppId = 'cloud.vibelink.desktop'
$notificationActivator = [Guid]'A38202ED-758B-552E-8722-E5CFC5918AFF'
# Keep the existing safe wrapper target and arguments. Windows notifications
# identify the Start Menu link by these two Shell properties, not its target.
if (-not ('VibeLinkShellIdentity' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

public static class VibeLinkShellIdentity {
    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    public struct Key { public Guid Format; public uint Id; public Key(uint id) { Format = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"); Id = id; } }
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    public struct Value { [FieldOffset(0)] public ushort Type; [FieldOffset(8)] public IntPtr Pointer; }
    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface Store {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out Key key);
        [PreserveSig] int GetValue(ref Key key, out Value value);
        [PreserveSig] int SetValue(ref Key key, ref Value value);
        [PreserveSig] int Commit();
    }
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref Value value);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern void SHChangeNotify(uint change, uint flags, string item, IntPtr unused);

    public static void Set(string path, string appId, Guid activator) {
        object link = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("00021401-0000-0000-C000-000000000046"), true));
        Value name = new Value { Type = 31, Pointer = Marshal.StringToCoTaskMemUni(appId) };
        Value clsid = new Value { Type = 72, Pointer = Marshal.AllocCoTaskMem(16) };
        try {
            Marshal.StructureToPtr(activator, clsid.Pointer, false);
            IPersistFile file = (IPersistFile)link;
            file.Load(path, 2);
            Store properties = (Store)link;
            Key app = new Key(5), toast = new Key(26);
            Marshal.ThrowExceptionForHR(properties.SetValue(ref app, ref name));
            Marshal.ThrowExceptionForHR(properties.SetValue(ref toast, ref clsid));
            Marshal.ThrowExceptionForHR(properties.Commit());
            file.Save(path, true);
            SHChangeNotify(0x00002000, 0x0005, path, IntPtr.Zero);
        } finally {
            PropVariantClear(ref name);
            PropVariantClear(ref clsid);
            if (link != null) Marshal.FinalReleaseComObject(link);
        }
    }
}
'@
}
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
$iconRoot = Join-Path $desktopRoot 'assets'
New-Item -ItemType Directory -Path $iconRoot -Force | Out-Null
foreach ($asset in @('vibelink.ico','vibelink.png')) {
    $previousIcon = Join-Path $iconRoot $asset
    if (Test-Path -LiteralPath $previousIcon) { Copy-Item -LiteralPath $previousIcon -Destination (Join-Path $backup ('icon-before-' + $asset)) }
}
$icon = Join-Path $iconRoot 'vibelink.ico'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'overlay/apps/desktop/resources/vibelink-icon.ico') -Destination $icon
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'overlay/apps/desktop/resources/vibelink-icon.png') -Destination (Join-Path $iconRoot 'vibelink.png')
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
# Electron validates the notification shortcut's exe/cwd but preserves its args.
# This tiny app keeps those native requirements while forwarding every ordinary
# Start Menu launch to the same safe wrapper, profile and source checkout.
$notificationLauncher = Join-Path $desktopRoot 'notification-launcher'
New-Item -ItemType Directory -Path $notificationLauncher -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $notificationLauncher 'package.json'), '{"name":"vibelink-notification-launcher","version":"1.0.0","main":"main.cjs"}', [Text.UTF8Encoding]::new($false))
$notificationBootstrap = @'
const { app } = require('electron');
const { spawn } = require('node:child_process');
const path = require('node:path');
spawn(path.join(process.env.WINDIR || 'C:\\Windows', 'System32', 'wscript.exe'),
  [path.join(__dirname, '..', 'Open-VibeLinkDesktop.vbs')],
  { detached: true, windowsHide: true, stdio: 'ignore' }).unref();
app.exit(0);
'@
[IO.File]::WriteAllText((Join-Path $notificationLauncher 'main.cjs'), $notificationBootstrap, [Text.UTF8Encoding]::new($false))
$notificationExecutable = Join-Path $SourceRoot 'node_modules\electron\dist\electron.exe'
if (!(Test-Path -LiteralPath $notificationExecutable)) { throw 'Install the prepared desktop dependencies before creating its notification entry.' }
$notificationRegistry = 'HKCU:\Software\Classes\AppUserModelId\' + $notificationAppId
if (Test-Path -LiteralPath $notificationRegistry) {
    Get-ItemProperty -LiteralPath $notificationRegistry | Export-Clixml -LiteralPath (Join-Path $backup 'notification-identity.xml')
}
New-Item -Path $notificationRegistry -Force | Out-Null
New-ItemProperty -LiteralPath $notificationRegistry -Name DisplayName -Value 'VibeLink' -PropertyType String -Force | Out-Null
New-ItemProperty -LiteralPath $notificationRegistry -Name IconUri -Value $icon -PropertyType String -Force | Out-Null
New-ItemProperty -LiteralPath $notificationRegistry -Name CustomActivator -Value ('{' + $notificationActivator.ToString() + '}') -PropertyType String -Force | Out-Null
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
    if ($folder -eq [Environment]::GetFolderPath('Programs')) {
        $link.TargetPath = $notificationExecutable
        $link.Arguments = '"' + $notificationLauncher + '"'
        $link.WorkingDirectory = Split-Path $notificationExecutable -Parent
    }
    $link.Description = 'VibeLink: conversations, workspace, devices and connections'
    $link.IconLocation = $icon + ',0'
    $link.Save()
    [VibeLinkShellIdentity]::Set($target, $notificationAppId, $notificationActivator)
}
# Unpackaged Electron uses its executable product name for its own shortcut,
# even after app.setName(). Seed a hidden compatible link so its lazy notifier
# accepts the fixed executable/cwd/identity and preserves the safe stub args.
$programsFolder = [Environment]::GetFolderPath('Programs')
$compatibilityLink = Join-Path $programsFolder (([IO.Path]::GetFileNameWithoutExtension($notificationExecutable)) + '.lnk')
$manageCompatibility = $true
if (Test-Path -LiteralPath $compatibilityLink) {
    $shellFolder = (New-Object -ComObject Shell.Application).Namespace($programsFolder)
    $item = $shellFolder.ParseName((Split-Path $compatibilityLink -Leaf))
    $existingId = [string]$item.ExtendedProperty('System.AppUserModel.ID')
    $existingActivator = [string]$item.ExtendedProperty('System.AppUserModel.ToastActivatorCLSID')
    $manageCompatibility = $existingId -eq $notificationAppId -and $existingActivator.Trim('{}') -ieq $notificationActivator.ToString()
    if ($manageCompatibility) {
        Copy-Item -LiteralPath $compatibilityLink -Destination (Join-Path $backup 'notification-compatibility.lnk')
    }
}
if ($manageCompatibility) {
    $link = $shell.CreateShortcut($compatibilityLink)
    $link.TargetPath = $notificationExecutable
    $link.Arguments = '"' + $notificationLauncher + '"'
    $link.WorkingDirectory = Split-Path $notificationExecutable -Parent
    $link.Description = 'VibeLink notification compatibility entry'
    $link.IconLocation = $icon + ',0'
    $link.Save()
    [VibeLinkShellIdentity]::Set($compatibilityLink, $notificationAppId, $notificationActivator)
    [IO.File]::SetAttributes($compatibilityLink, ([IO.File]::GetAttributes($compatibilityLink) -bor [IO.FileAttributes]::Hidden))
} else {
    Write-Warning 'An existing Electron entry belongs to another application; preserved it. VibeLink source notifications remain unavailable until that identity conflict is resolved.'
}
$pinnedRoot = Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar'
$pinnedUpdated = 0
if (Test-Path -LiteralPath $pinnedRoot) {
    $pinnedFolder = (New-Object -ComObject Shell.Application).Namespace($pinnedRoot)
    foreach ($pinnedFile in Get-ChildItem -LiteralPath $pinnedRoot -Filter '*.lnk' -File) {
        $pinnedItem = $pinnedFolder.ParseName($pinnedFile.Name)
        if ([string]$pinnedItem.ExtendedProperty('System.AppUserModel.ID') -ne $notificationAppId) { continue }
        Copy-Item -LiteralPath $pinnedFile.FullName -Destination (Join-Path $backup ('pinned-' + $pinnedFile.Name))
        $pinnedLink = $shell.CreateShortcut($pinnedFile.FullName)
        $pinnedLink.IconLocation = $icon + ',0'
        $pinnedLink.Save()
        [VibeLinkShellIdentity]::Set($pinnedFile.FullName, $notificationAppId, $notificationActivator)
        $pinnedUpdated++
    }
}
Write-Host "Refreshed $pinnedUpdated existing taskbar entries owned by VibeLink. Icon: $icon"
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
