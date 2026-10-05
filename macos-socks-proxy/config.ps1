$SshTarget = 'kobayashi@100.105.59.31'
$LocalPort = 1080
$KeyPath = Join-Path $env:USERPROFILE '.ssh\id_ed25519'
$SshExePath = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
$ProbeIP = '1.1.1.1'
$ProbePort = 443
$DeepCheckMinutes = 5
$MutexName = 'MacSocksProxyWatchdog'
