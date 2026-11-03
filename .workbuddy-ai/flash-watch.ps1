# 取证：捕获"闪一下就消失"的控制台窗口
# 只记录与 console/脚本宿主相关的新进程 + 新可见窗口 + 前台抢占。
param([int]$Seconds = 300)

$ErrorActionPreference = 'SilentlyContinue'
$log = Join-Path $PSScriptRoot 'flash-watch.log'
[System.IO.File]::WriteAllText($log, '')

$src = @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public class Fw {
  public delegate bool EnumProc(IntPtr h, IntPtr p);

  [DllImport("ntdll.dll")] public static extern int NtQuerySystemInformation(int cls, IntPtr buf, int len, out int ret);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int idx);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(int access, bool inherit, int pid);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr h);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool ReadProcessMemory(IntPtr h, IntPtr addr, byte[] buf, int size, out IntPtr read);
  [DllImport("ntdll.dll")] public static extern int NtQueryInformationProcess(IntPtr h, int cls, ref PBI pbi, int len, out int ret);

  [StructLayout(LayoutKind.Sequential)]
  public struct PBI { public IntPtr R1; public IntPtr Peb; public IntPtr A; public IntPtr B; public IntPtr Pid; public IntPtr C; }

  public class P { public long Pid; public long PPid; public string Image; }

  public static List<P> Procs() {
    int len = 1 << 20; IntPtr buf = Marshal.AllocHGlobal(len); int ret = 0;
    int st = NtQuerySystemInformation(5, buf, len, out ret);
    while (st == unchecked((int)0xC0000004)) {
      Marshal.FreeHGlobal(buf); len = ret + (1 << 20); buf = Marshal.AllocHGlobal(len);
      st = NtQuerySystemInformation(5, buf, len, out ret);
    }
    var list = new List<P>();
    if (st != 0) { Marshal.FreeHGlobal(buf); return list; }
    IntPtr p = buf;
    while (true) {
      int next = Marshal.ReadInt32(p, 0);
      long pid = Marshal.ReadIntPtr(p, 80).ToInt64();
      long ppid = Marshal.ReadIntPtr(p, 88).ToInt64();
      int imgLen = Marshal.ReadInt16(p, 56);
      IntPtr imgBuf = Marshal.ReadIntPtr(p, 64);
      string img = "";
      if (imgLen > 0 && imgBuf != IntPtr.Zero) img = Marshal.PtrToStringUni(imgBuf, imgLen / 2);
      list.Add(new P { Pid = pid, PPid = ppid, Image = img });
      if (next == 0) break;
      p = IntPtr.Add(p, next);
    }
    Marshal.FreeHGlobal(buf);
    return list;
  }

  public static string CmdLine(int pid) {
    IntPtr h = OpenProcess(0x1000 | 0x0010, false, pid);
    if (h == IntPtr.Zero) return "<no-access>";
    try {
      PBI pbi = new PBI(); int ret;
      if (NtQueryInformationProcess(h, 0, ref pbi, Marshal.SizeOf(typeof(PBI)), out ret) != 0) return "<nqip>";
      IntPtr read; byte[] b8 = new byte[8];
      if (!ReadProcessMemory(h, IntPtr.Add(pbi.Peb, 0x20), b8, 8, out read)) return "<peb>";
      IntPtr pp = (IntPtr)BitConverter.ToInt64(b8, 0);
      byte[] us = new byte[16];
      if (!ReadProcessMemory(h, IntPtr.Add(pp, 0x70), us, 16, out read)) return "<params>";
      ushort len = BitConverter.ToUInt16(us, 0);
      IntPtr buf = (IntPtr)BitConverter.ToInt64(us, 8);
      if (len == 0 || buf == IntPtr.Zero) return "";
      byte[] data = new byte[len];
      if (!ReadProcessMemory(h, buf, data, len, out read)) return "<str>";
      return Encoding.Unicode.GetString(data);
    } catch { return "<ex>"; } finally { CloseHandle(h); }
  }

  public class W { public IntPtr H; public string Cls; public string Title; public int Pid; public int Style; }

  public static List<W> Windows() {
    var res = new List<W>();
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      if (!IsWindowVisible(h)) return true;
      var cs = new StringBuilder(256); GetClassName(h, cs, 256);
      var ts = new StringBuilder(512); GetWindowText(h, ts, 512);
      int pid = 0; GetWindowThreadProcessId(h, out pid);
      res.Add(new W { H = h, Cls = cs.ToString(), Title = ts.ToString(), Pid = pid, Style = GetWindowLong(h, -16) });
      return true;
    }, IntPtr.Zero);
    return res;
  }
}
'@
Add-Type -TypeDefinition $src -ErrorAction Stop

function Log([string]$s) {
  if ($null -eq $s) { $s = '<null>' }
  [System.IO.File]::AppendAllText($log, $s + "`r`n", [System.Text.Encoding]::UTF8)
}

function Get-Table {
  $t = @{}
  foreach ($e in [Fw]::Procs()) { $t[[long]$e.Pid] = $e }
  return $t
}

function Chain([long]$targetPid, $tbl) {
  $parts = New-Object System.Collections.ArrayList
  $cur = $targetPid
  for ($i = 0; $i -lt 8; $i++) {
    if (-not $tbl.ContainsKey($cur)) { [void]$parts.Add("<$cur exited>"); break }
    $e = $tbl[$cur]
    $img = [string]$e.Image
    $leaf = '?'
    if ($img) {
      $idx = $img.LastIndexOfAny([char[]]@('\', '/'))
      if ($idx -ge 0 -and $idx -lt ($img.Length - 1)) { $leaf = $img.Substring($idx + 1) } else { $leaf = $img }
    }
    [void]$parts.Add(('{0}[{1}]' -f $leaf, $e.Pid))
    if ($e.PPid -eq 0 -or $e.PPid -eq $cur) { break }
    $cur = [long]$e.PPid
  }
  return ([string]::Join(' <- ', $parts.ToArray()))
}

$noise = 'chrome|msedgewebview2|ChatGPT|QQ|WeChatAppEx|steamwebhelper|copilotapp|svchost|RuntimeBroker|backgroundTaskHost|SearchApp|ShellExperienceHost|StartMenuExperienceHost|TextInputHost|LockApp|dllhost|smartscreen|taskhostw|dwm|csrss|fontdrvhost|winlogon|explorer|WidgetService|ApplicationFrameHost|SgrmBroker|SecurityHealthSusp'

$table = Get-Table
$seenPid = @{}
foreach ($k in $table.Keys) { $seenPid[$k] = $true }
$seenWin = @{}
foreach ($w in [Fw]::Windows()) { $seenWin[$w.H.ToString()] = $true }
$lastFg = [IntPtr]::Zero
$lastFgAt = Get-Date
$lastFgDesc = ''

Log ("=== watch start {0} for {1}s ===" -f (Get-Date -Format 'HH:mm:ss'), $Seconds)
$deadline = (Get-Date).AddSeconds($Seconds)
$tick = 0

while ((Get-Date) -lt $deadline) {
  $now = Get-Date
  $tick++
  if ($tick % 6 -eq 0) { $table = Get-Table }

  foreach ($k in @($table.Keys)) {
    if (-not $seenPid.ContainsKey($k)) {
      $seenPid[$k] = $true
      $e = $table[$k]
      $img = [string]$e.Image
      $leaf = '?'
      if ($img) {
        $idx = $img.LastIndexOfAny([char[]]@('\', '/'))
        if ($idx -ge 0 -and $idx -lt ($img.Length - 1)) { $leaf = $img.Substring($idx + 1) } else { $leaf = $img }
      }
      if ($leaf -notmatch "^($noise)$") {
        $cmd = [Fw]::CmdLine([int]$e.Pid)
        Log ("{0}.{1:000} PROC {2}" -f $now.ToString('HH:mm:ss'), $now.Millisecond, (Chain $k $table))
        if ($cmd) { Log ("            cmd: {0}" -f $cmd) }
      }
    }
  }
  $alive = @{}
  foreach ($k in $table.Keys) { $alive[$k] = $true }
  foreach ($k in @($seenPid.Keys)) { if (-not $alive.ContainsKey($k)) { $seenPid.Remove($k) } }

  $wins = [Fw]::Windows()
  foreach ($w in $wins) {
    $key = $w.H.ToString()
    if (-not $seenWin.ContainsKey($key)) {
      $seenWin[$key] = $true
      $fg = [Fw]::GetForegroundWindow()
      $mark = ''
      if ($fg -eq $w.H) { $mark = '  <<< FOREGROUND' }
      Log ("{0}.{1:000} WIN  hwnd={2} class={3} style=0x{4:X8} title='{5}' owner={6}{7}" -f `
           $now.ToString('HH:mm:ss'), $now.Millisecond, $w.H, $w.Cls, $w.Style, $w.Title, (Chain $w.Pid $table), $mark)
    }
  }
  $live = @{}
  foreach ($w in $wins) { $live[$w.H.ToString()] = $true }
  foreach ($k in @($seenWin.Keys)) { if (-not $live.ContainsKey($k)) { $seenWin.Remove($k) } }

  $f = [Fw]::GetForegroundWindow()
  if ($f -ne $lastFg) {
    $dwell = ($now - $lastFgAt).TotalMilliseconds
    $lastFg = $f
    $lastFgAt = $now
    $wp = 0
    [void][Fw]::GetWindowThreadProcessId($f, [ref]$wp)
    $cs = New-Object System.Text.StringBuilder 256
    [void][Fw]::GetClassName($f, $cs, 256)
    $ts = New-Object System.Text.StringBuilder 512
    [void][Fw]::GetWindowText($f, $ts, 512)
    Log ("{0}.{1:000} FG   prev-dwell={2:0}ms class={3} title='{4}' owner={5}" -f `
         $now.ToString('HH:mm:ss'), $now.Millisecond, $dwell, $cs.ToString(), $ts.ToString(), (Chain $wp $table))
  }

  Start-Sleep -Milliseconds 60
}
Log ("=== watch end {0} ===" -f (Get-Date -Format 'HH:mm:ss'))
