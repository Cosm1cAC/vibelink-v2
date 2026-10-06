# opencode-mesh / Windows 端配置
$ServerPort = 4096
$UpstreamPort = 4097
$WorkDir = $env:USERPROFILE
$ConfigDir = Join-Path $env:USERPROFILE '.config\opencode-server'
$PasswordFile = Join-Path $ConfigDir 'password'
$LogDir = Join-Path $PSScriptRoot 'logs'
$TaskName = 'OpenCodeServer'
$MutexName = 'OpenCodeMeshServerWatchdog'

# 徽标代理（注入「直连/中继」角标），opencode 本体退到 127.0.0.1:$UpstreamPort
$ProxyScript = Join-Path (Split-Path $PSScriptRoot -Parent) 'mesh-proxy.py'
$PythonExe = (Get-Command python -ErrorAction Stop).Source

# 允许哪些来源的浏览器跨机访问本机 server（CORS 白名单，跨机访问必需）
$CorsOrigins = @(
    'http://100.74.71.80:4096',   # 本机 haoshao（Tailscale）
    'http://100.105.59.31:4096',  # Mac liangmac-mini（Tailscale）
    'http://192.168.85.47:4096',  # 本机局域网
    'http://localhost:4096',
    'http://127.0.0.1:4096'
)

$NpmExe = Join-Path $env:APPDATA 'npm\node_modules\opencode-ai\bin\opencode.exe'
if (Test-Path $NpmExe) {
    $OpencodeExe = $NpmExe
} else {
    $OpencodeExe = (Get-Command opencode -ErrorAction Stop).Source
}
