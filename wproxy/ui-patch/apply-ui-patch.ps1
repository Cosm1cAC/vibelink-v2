$ErrorActionPreference = 'Stop'
$patchDir = $PSScriptRoot
$wproxyDir = Split-Path $patchDir -Parent
$ui = Join-Path $wproxyDir 'ui'

if (-not (Test-Path -LiteralPath $ui)) {
    throw 'ui 目录不存在：请先运行 wproxy\download-assets.ps1'
}

Copy-Item -LiteralPath (Join-Path $patchDir 'mac-monitor.js') -Destination (Join-Path $ui 'mac-monitor.js') -Force
Write-Host '[patch] mac-monitor.js 已复制到 ui\'

$indexPath = Join-Path $ui 'index.html'
$html = Get-Content -LiteralPath $indexPath -Raw
if ($html -notmatch 'mac-monitor\.js') {
    $html = $html -replace '</body>', "    <script src=`"./mac-monitor.js`"></script>`r`n  </body>"
    Set-Content -LiteralPath $indexPath -Value $html -Encoding UTF8 -NoNewline
    Write-Host '[patch] index.html 已注入 mac-monitor.js'
} else {
    Write-Host '[patch] index.html 已包含补丁，跳过'
}

# 将 sw.js 替换为自毁版本：让旧 Service Worker 缓存失效，补丁立即生效且不再劫持页面
$sw = @'
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) {
  e.waitUntil((async function () {
    try { await self.registration.unregister(); } catch (err) {}
    try {
      var keys = await caches.keys();
      await Promise.all(keys.map(function (k) { return caches.delete(k); }));
    } catch (err) {}
  })());
});
'@
Set-Content -LiteralPath (Join-Path $ui 'sw.js') -Value $sw -Encoding ASCII
Write-Host '[patch] sw.js 已替换为自毁版（清除 zashboard PWA 缓存）'
