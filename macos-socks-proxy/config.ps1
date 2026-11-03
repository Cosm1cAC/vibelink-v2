$SshTarget = 'kobayashi@100.105.59.31'
# 本机为对称 NAT，Tailscale 无法直连 Mac（会绕道德国 DERP 且 TCP 全断）。
# 借局域网内 laptop 当跳板：本机 ↔ laptop 是 LAN 直连，laptop ↔ Mac 是 Tailscale 直连。
# 留空则恢复为直连模式（本机 NAT 修好后可清空）。
$JumpHost = 'ADMIN@100.123.253.85'
# 承载 -L 转发的 SSH 终点。留空=沿用 $SshTarget。
# 填 laptop 时：本机 --SSH--> laptop --TCP直连--> Mac:7891（一跳 SSH，最稳）。
# 若设成 $SshTarget + $JumpHost 则变成双跳且 Mac 要回连自己(发夹)，实测会间歇失败。
$TunnelTarget = 'ADMIN@100.123.253.85'
$LocalPort = 1080
$KeyPath = Join-Path $env:USERPROFILE '.ssh\id_ed25519'
$SshExePath = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
$ProbeIP = '1.1.1.1'
$ProbePort = 443
$DeepCheckMinutes = 5
$MutexName = 'MacSocksProxyWatchdog'
