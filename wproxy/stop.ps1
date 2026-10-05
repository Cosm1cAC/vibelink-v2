$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')
$cli = Join-Path $PSScriptRoot 'wproxy.ps1'

Write-Host ''
Write-Host '  ============================================' -ForegroundColor Cyan
Write-Host '   wproxy 停止' -ForegroundColor Cyan
Write-Host '  ============================================' -ForegroundColor Cyan
Write-Host ''

& $PwshExe -NoProfile -ExecutionPolicy Bypass -File $cli -Action stop

Write-Host ''
Write-Host '  [OK] 已停止（若系统代理指向 wproxy，已自动还原为直连）' -ForegroundColor Green
Write-Host '  启动：双击 一键启动.cmd' -ForegroundColor Gray
Write-Host ''
