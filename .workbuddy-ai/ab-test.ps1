# A/B 对照实验：同样一个后台脚本，两种启动方式，看哪个会抢前台
param([ValidateSet('task','vbs')][string]$Mode = 'task', [int]$Wait = 6)

$ErrorActionPreference = 'SilentlyContinue'
$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class Ab {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
}
'@
Add-Type -TypeDefinition $src -ErrorAction Stop

$ps = 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe'
$script = 'C:\Users\22771\bin\pc-proxy-failover.ps1'
$vbs = 'C:\Users\22771\bin\pc-proxy-failover.vbs'

function Desc {
  $h = [Ab]::GetForegroundWindow()
  $cs = New-Object System.Text.StringBuilder 256
  [void][Ab]::GetClassName($h, $cs, 256)
  $ts = New-Object System.Text.StringBuilder 256
  [void][Ab]::GetWindowText($h, $ts, 256)
  $pid2 = 0
  [void][Ab]::GetWindowThreadProcessId($h, [ref]$pid2)
  $pn = (Get-Process -Id $pid2).ProcessName
  return ("class={0} title='{1}' proc={2}" -f $cs.ToString(), $ts.ToString(), $pn)
}

"=== baseline: $(Desc)"
$sw = [Diagnostics.Stopwatch]::StartNew()
if ($Mode -eq 'task') {
  # 完全复刻任务计划器的动作
  Start-Process -FilePath $ps -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File', $script) | Out-Null
} else {
  Start-Process -FilePath 'C:\WINDOWS\System32\wscript.exe' -ArgumentList @('"' + $vbs + '"') | Out-Null
}

$prev = ''
$events = @()
while ($sw.Elapsed.TotalSeconds -lt $Wait) {
  $d = Desc
  if ($d -ne $prev) {
    $events += ('  {0,7:0.000}s  {1}' -f $sw.Elapsed.TotalSeconds, $d)
    $prev = $d
  }
  Start-Sleep -Milliseconds 40
}
"=== foreground timeline ($Mode) ==="
$events | ForEach-Object { $_ }
"=== end: $(Desc)"
