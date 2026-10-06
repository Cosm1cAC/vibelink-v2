# opencode-mesh / Windows 看门狗：保活 opencode(127.0.0.1:4097) + 徽标代理(0.0.0.0:4096)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'config.ps1')

$mutex = New-Object System.Threading.Mutex($false, $MutexName)
if (-not $mutex.WaitOne(0)) { exit 0 }

New-Item -ItemType Directory -Force -Path $ConfigDir, $LogDir | Out-Null
if (-not (Test-Path $PasswordFile)) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $password = -join (1..24 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
    Set-Content -LiteralPath $PasswordFile -Value $password -Encoding ASCII
}
$env:OPENCODE_SERVER_PASSWORD = (Get-Content -LiteralPath $PasswordFile -Raw).Trim()
# fff 拒绝把 home 目录作为索引根，会导致搜索永远为空（上游 bug #41456），回退到 ripgrep 实现
$env:OPENCODE_DISABLE_FFF = '1'

$log = Join-Path $LogDir ('server-{0:yyyyMMdd}.log' -f (Get-Date))
function Write-Log([string]$Message) {
    ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) | Add-Content -LiteralPath $log -Encoding utf8
}

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $task.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($task)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

$ocArgs = @('serve', '--hostname', '127.0.0.1', '--port', "$UpstreamPort")
foreach ($origin in $CorsOrigins) { $ocArgs += @('--cors', $origin) }
$proxyArgs = @($ProxyScript, '--listen', "0.0.0.0:$ServerPort", '--upstream', "127.0.0.1:$UpstreamPort")

Write-Log ('watchdog started (pid {0}, public {1}, upstream {2})' -f $PID, $ServerPort, $UpstreamPort)
while ($true) {
    if (-not (Test-Port $UpstreamPort)) {
        Write-Log ('starting opencode on 127.0.0.1:{0}' -f $UpstreamPort)
        Start-Process -FilePath $OpencodeExe -ArgumentList $ocArgs -WorkingDirectory $WorkDir -WindowStyle Hidden | Out-Null
    }
    if (-not (Test-Port $ServerPort)) {
        Write-Log ('starting proxy on 0.0.0.0:{0}' -f $ServerPort)
        Start-Process -FilePath $PythonExe -ArgumentList $proxyArgs -WindowStyle Hidden | Out-Null
    }
    Start-Sleep -Seconds 10
}
