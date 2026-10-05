$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Get-Verified([string]$Url, [string]$Out, [string]$Sha256) {
    Write-Host ('下载 {0} ...' -f (Split-Path $Url -Leaf))
    Invoke-WebRequest -Uri $Url -OutFile $Out -TimeoutSec 900
    $h = (Get-FileHash $Out -Algorithm SHA256).Hash
    if ($Sha256) {
        if ($h -ne $Sha256) {
            Remove-Item $Out -Force
            throw ('SHA256 校验失败: {0}' -f $Out)
        }
        Write-Host '  校验 OK'
    } else {
        Write-Host ('  SHA256={0}' -f $h)
    }
}

$tmp = Join-Path $env:TEMP ('wproxy-assets-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

Get-Verified 'https://github.com/MetaCubeX/mihomo/releases/download/v1.19.32/mihomo-windows-amd64-v1.19.32.zip' "$tmp\mihomo.zip" '1AC84E795B5B915446E20139677FB6CC013A0778E876E8D4C96F994750B4C4A3'
Expand-Archive "$tmp\mihomo.zip" $tmp -Force
Move-Item (Join-Path $tmp 'mihomo-windows-amd64.exe') (Join-Path $root 'mihomo.exe') -Force

Get-Verified 'https://github.com/Zephyruso/zashboard/releases/download/v3.29.1/dist-no-fonts.zip' "$tmp\zash.zip" '21371CD111B6B3D87774F3EA3DDEACDCB0F6AFF8690A16D652DAA3EA6E3B0145'
Expand-Archive "$tmp\zash.zip" "$tmp\zash" -Force
$ui = Join-Path $root 'ui'
if (Test-Path $ui) { Remove-Item $ui -Recurse -Force }
New-Item -ItemType Directory -Path $ui -Force | Out-Null
Copy-Item (Join-Path $tmp 'zash\dist\*') $ui -Recurse -Force

Get-Verified 'https://www.wintun.net/builds/wintun-0.14.1.zip' "$tmp\wintun.zip" '07C256185D6EE3652E09FA55C0B673E2624B565E02C4B9091C79CA7D2F24EF51'
Expand-Archive "$tmp\wintun.zip" "$tmp\wintun" -Force
Copy-Item (Join-Path $tmp 'wintun\bin\amd64\wintun.dll') (Join-Path $root 'wintun.dll') -Force

Write-Host '下载分流规则数据库 ...'
Invoke-WebRequest -Uri 'https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat' -OutFile (Join-Path $root 'geosite.dat') -TimeoutSec 900
Invoke-WebRequest -Uri 'https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb' -OutFile (Join-Path $root 'geoip.metadb') -TimeoutSec 900

Write-Host '应用 UI 补丁（Mac 监控面板）...'
& (Join-Path $root 'ui-patch\apply-ui-patch.ps1')

Remove-Item $tmp -Recurse -Force
Write-Host '[OK] 全部资源已就位，可以双击 一键启动.cmd' -ForegroundColor Green
