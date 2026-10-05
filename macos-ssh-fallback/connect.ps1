param(
    [string]$Endpoint = '',
    [string]$User = 'kobayashi',
    [string]$KeyPath = "$env:USERPROFILE\.ssh\id_ed25519",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Command
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $PSCommandPath
$hostKey = (Get-Content (Join-Path $dir 'host_key.pub') -Raw).Trim()

if (-not $Endpoint) {
    Write-Host '[*] 未指定 -Endpoint，尝试经 Tailscale 读取 Mac 上 ngrok 当前地址...'
    $json = ssh -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=10 -i $KeyPath "$User@100.105.59.31" 'curl -s --max-time 5 http://127.0.0.1:4040/api/tunnels' 2>$null
    if ($LASTEXITCODE -eq 0 -and $json) {
        $t = $json | ConvertFrom-Json
        $url = $t.tunnels[0].public_url
        if ($url -match '^tcp://(.+):(\d+)$') { $Endpoint = "$($Matches[1]):$($Matches[2])" }
    }
}

if (-not $Endpoint) {
    throw '无法获取 ngrok 地址：Tailscale 不可用。请到 https://dashboard.ngrok.com/endpoints 查看当前 TCP 地址，然后 -Endpoint host:port 传入。'
}

$h, $p = $Endpoint -split ':', 2
$kh = Join-Path $env:TEMP "ngrok_known_hosts_$p.txt"
"[$h]:$p $hostKey" | Set-Content -Path $kh -Encoding ASCII

Write-Host "[*] 连接 $h`:$p (主机指纹已钉住)" -ForegroundColor Cyan
$sshArgs = @(
    '-o', 'IdentitiesOnly=yes',
    '-o', "UserKnownHostsFile=$kh",
    '-o', 'StrictHostKeyChecking=yes',
    '-o', 'ConnectTimeout=15',
    '-i', $KeyPath,
    '-p', $p,
    "$User@$h"
)
if ($Command) {
    & ssh @sshArgs ($Command -join ' ')
} else {
    & ssh @sshArgs
}
exit $LASTEXITCODE
