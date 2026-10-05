$ErrorActionPreference = 'Stop'
$patchDir = $PSScriptRoot
$wproxyDir = Split-Path $patchDir -Parent
$ui = Join-Path $wproxyDir 'ui'

if (-not (Test-Path -LiteralPath $ui)) {
    throw 'ui 目录不存在：请先运行 wproxy\download-assets.ps1'
}

$indexPath = Join-Path $ui 'index.html'
$html = Get-Content -LiteralPath $indexPath -Raw

foreach ($name in @('lang-zh-cn.js', 'mac-monitor.js')) {
    Copy-Item -LiteralPath (Join-Path $patchDir $name) -Destination (Join-Path $ui $name) -Force
    Write-Host ('[patch] {0} 已复制到 ui\' -f $name)
    if ($html -notmatch [regex]::Escape($name)) {
        $html = $html -replace '</body>', "    <script src=`"./$name`"></script>`r`n  </body>"
        Write-Host ('[patch] index.html 注入 {0}' -f $name)
    } else {
        Write-Host ('[patch] index.html 已包含 {0}，跳过' -f $name)
    }
}

Set-Content -LiteralPath $indexPath -Value $html -Encoding UTF8 -NoNewline

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
