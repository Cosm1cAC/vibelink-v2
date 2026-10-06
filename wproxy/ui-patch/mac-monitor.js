/* Mac 后台监控面板 for zashboard
 *
 * 由 wproxy/ui-patch/apply-ui-patch.ps1 注入到 zashboard 的 index.html。
 * 数据源：Mac 上的 mac-metrics agent（Tailscale 网段内 HTTP JSON）。
 * 使用 Shadow DOM 隔离样式，不干扰 zashboard 自身。
 */
(function () {
  'use strict';

  var CFG = {
    api: 'http://100.105.59.31:18992/metrics',
    refreshMs: 2000,
    // 'settings' = 仅在 #/settings 页显示；'always' = 所有页面显示
    showWhen: 'settings',
    maxPoints: 60
  };

  var MAXPTS = CFG.maxPoints;
  var hist = { cpu: [], gpu: [], mem: [], net: [] };
  var host = null, root = null, els = {};
  var collapsed = localStorage.getItem('macMonitorCollapsed') === '1';
  var memOpen = localStorage.getItem('macMonitorMemOpen') === '1';
  var linkRtt = null, online = null, timer = null, inflight = false;

  var CSS = [
    ':host { all: initial; }',
    '.panel {',
    '  position: fixed; right: 14px; bottom: 14px; z-index: 2147483000;',
    '  width: 252px; box-sizing: border-box;',
    '  font: 12px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;',
    '  color: var(--fg); background: var(--bg);',
    '  border: 1px solid var(--bd); border-radius: 10px;',
    '  box-shadow: 0 8px 28px rgba(0,0,0,.28);',
    '  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);',
    '  overflow: hidden; user-select: none;',
    '}',
    '.panel { --fg:#e8eaed; --dim:#9aa0a6; --bg:rgba(28,30,34,.92); --bd:rgba(255,255,255,.12); --track:rgba(255,255,255,.10); }',
    '@media (prefers-color-scheme: light) {',
    '  .panel { --fg:#202124; --dim:#5f6368; --bg:rgba(255,255,255,.94); --bd:rgba(0,0,0,.10); --track:rgba(0,0,0,.08); }',
    '}',
    'header { display:flex; align-items:center; gap:6px; padding:7px 10px; cursor:pointer; }',
    'header .dot { width:7px; height:7px; border-radius:50%; background:#f0a020; flex:none; }',
    'header .dot.on { background:#34c759; }',
    'header .dot.off { background:#ff453a; }',
    'header .title { flex:1; font-weight:600; letter-spacing:.2px; }',
    'header .chev { color:var(--dim); }',
    '.body { padding:2px 10px 9px; display:grid; gap:6px; }',
    '.row { display:grid; grid-template-columns: 34px 1fr; gap:6px; align-items:center; }',
    '.row .k { color:var(--dim); }',
    '.row.clickable { cursor:pointer; border-radius:6px; }',
    '.row.clickable:hover { background:var(--track); }',
    '.detail { display:grid; gap:2px; padding:0 0 2px; }',
    '.detail .line { display:flex; justify-content:space-between; color:var(--dim); }',
    '.metric { display:flex; align-items:center; gap:6px; min-width:0; }',
    '.val { min-width:52px; text-align:right; font-variant-numeric: tabular-nums; }',
    '.bar { flex:1; height:5px; border-radius:3px; background:var(--track); overflow:hidden; min-width:36px; }',
    '.bar i { display:block; height:100%; width:0; border-radius:3px; transition:width .35s ease; }',
    '.spark { width:56px; height:16px; flex:none; opacity:.9; }',
    '.sub { color:var(--dim); font-size:11px; display:flex; justify-content:space-between; gap:6px; }',
    '.lat { display:grid; gap:2px; }',
    '.lat .line { display:flex; justify-content:space-between; }',
    'canvas { display:block; }',
    '.collapsed .body { display:none; }',
    '.collapsed { width:auto; }'
  ].join('\n');

  var HTML = [
    '<div class="panel' + (collapsed ? ' collapsed' : '') + '" id="mm-panel">',
    '  <header id="mm-head">',
    '    <span class="dot" id="mm-dot"></span>',
    '    <span class="title">Mac 监控 · 大阪</span>',
    '    <span class="chev">' + (collapsed ? '▸' : '▾') + '</span>',
    '  </header>',
    '  <div class="body">',
    '    <div class="row"><span class="k">CPU</span><div class="metric"><span class="val" id="mm-cpu"></span><div class="bar"><i id="mm-cpu-bar" style="background:#4c8dff"></i></div><canvas class="spark" id="mm-cpu-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="row"><span class="k">GPU</span><div class="metric"><span class="val" id="mm-gpu"></span><div class="bar"><i id="mm-gpu-bar" style="background:#a970ff"></i></div><canvas class="spark" id="mm-gpu-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="row clickable" id="mm-mem-row" title="点击查看内存明细"><span class="k">内存</span><div class="metric"><span class="val" id="mm-mem"></span><div class="bar"><i id="mm-mem-bar" style="background:#2fbf71"></i></div><canvas class="spark" id="mm-mem-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="detail" id="mm-mem-detail" style="display:none">',
    '      <div class="line"><span>App</span><span id="mm-mem-app">--</span></div>',
    '      <div class="line"><span>Wired</span><span id="mm-mem-wired">--</span></div>',
    '      <div class="line"><span>压缩内存</span><span id="mm-mem-comp">--</span></div>',
    '      <div class="line"><span>文件缓存</span><span id="mm-mem-cached">--</span></div>',
    '      <div class="line"><span>Swap</span><span id="mm-mem-swap">--</span></div>',
    '    </div>',
    '    <div class="row"><span class="k">硬盘</span><div class="metric"><span class="val" id="mm-disk"></span><div class="bar"><i id="mm-disk-bar" style="background:#e8a33d"></i></div></div></div>',
    '    <div class="row"><span class="k">带宽</span><div class="metric"><span class="val" id="mm-net"></span><div class="bar" style="visibility:hidden"></div><canvas class="spark" id="mm-net-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="lat">',
    '      <div class="line"><span class="k">链路</span><span id="mm-link">--</span></div>',
    '      <div class="line"><span class="k">出口</span><span id="mm-egress">--</span></div>',
    '      <div class="line"><span class="k">负载</span><span id="mm-load">--</span></div>',
    '    </div>',
    '  </div>',
    '</div>'
  ].join('\n');

  function fmtBytes(n) {
    if (n === null || n === undefined || isNaN(n)) return '--';
    var u = ['B', 'K', 'M', 'G', 'T'], i = 0;
    n = Number(n);
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i === 0 || n >= 100 ? n.toFixed(0) : n.toFixed(1)) + u[i];
  }
  function fmtRate(bps) { return fmtBytes(bps) + '/s'; }
  function fmtPct(v) { return (v === null || v === undefined) ? '--' : v + '%'; }
  function barPct(el, v) { el.style.width = Math.max(0, Math.min(100, v || 0)) + '%'; }

  function spark(canvas, data, color) {
    var ctx = canvas.getContext('2d');
    var w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    if (data.length < 2) return;
    var max = 1;
    for (var i = 0; i < data.length; i++) if (data[i] > max) max = data[i];
    ctx.beginPath();
    for (var j = 0; j < data.length; j++) {
      var x = j * (w - 1) / (MAXPTS - 1);
      var y = h - 3 - (Math.max(0, data[j]) / max) * (h - 6);
      if (j === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.stroke();
    ctx.lineTo((data.length - 1) * (w - 1) / (MAXPTS - 1), h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fillStyle = color + '22'; ctx.fill();
  }

  function push(arr, v) {
    arr.push(Math.max(0, v || 0));
    if (arr.length > MAXPTS) arr.shift();
  }

  function setOnline(on, text) {
    if (online === on && !text) return;
    online = on;
    els.dot.className = 'dot' + (on === null ? '' : (on ? ' on' : ' off'));
    if (text && els.link) els.link.textContent = text;
  }

  function renderStatus() {
    if (online === false) els.link.textContent = 'Mac 监控离线';
  }

  function render(m) {
    if (!m || m.ts === undefined) return;

    var cpu = m.cpu_pct;
    push(hist.cpu, cpu);
    els.cpu.textContent = fmtPct(cpu);
    barPct(els.cpuBar, cpu);
    spark(els.cpuSp, hist.cpu, '#4c8dff');

    var gpu = m.gpu && m.gpu.util_pct;
    push(hist.gpu, gpu);
    els.gpu.textContent = fmtPct(gpu);
    barPct(els.gpuBar, gpu);
    spark(els.gpuSp, hist.gpu, '#a970ff');

    var mem = m.mem || {};
    var memPct = mem.total ? Math.round(mem.used / mem.total * 1000) / 10 : null;
    push(hist.mem, memPct);
    els.mem.textContent = mem.total ? fmtBytes(mem.used) + '/' + fmtBytes(mem.total) : '--';
    barPct(els.memBar, memPct);
    spark(els.memSp, hist.mem, '#2fbf71');
    els.memApp.textContent = fmtBytes(mem.app);
    els.memWired.textContent = fmtBytes(mem.wired);
    els.memComp.textContent = fmtBytes(mem.compressed);
    els.memCached.textContent = fmtBytes(mem.cached);
    els.memSwap.textContent = fmtBytes(mem.swap_used === null || mem.swap_used === undefined ? null : mem.swap_used);

    var disk = m.disk || {};
    els.disk.textContent = disk.total ? fmtBytes(disk.used) + '/' + fmtBytes(disk.total) : '--';
    barPct(els.diskBar, disk.used_pct);

    var net = m.net || {};
    var rx = net.rx_bps || 0, tx = net.tx_bps || 0;
    push(hist.net, (rx + tx) / 1024);
    els.net.textContent = '↓' + fmtRate(rx) + ' ↑' + fmtRate(tx);
    spark(els.netSp, hist.net, '#3ac9d6');

    var lat = m.latency || {};
    var egress = [];
    ['1.1.1.1:443', '8.8.8.8:443', '223.5.5.5:443'].forEach(function (k) {
      if (lat[k] !== undefined) egress.push(k.split(':')[0] + ' ' + (lat[k] === null ? '--' : lat[k] + 'ms'));
    });
    els.egress.textContent = egress.join(' · ') || '--';

    els.link.textContent = linkRtt === null ? '--' : linkRtt + 'ms';

    var load = m.load;
    var up = m.uptime_s;
    var upText = '--';
    if (up !== null && up !== undefined) {
      upText = up >= 86400 ? (up / 86400).toFixed(1) + 'd' : (up / 3600).toFixed(1) + 'h';
    }
    els.load.textContent = (load ? load.map(function (v) { return v.toFixed(2); }).join(' ') : '--') + ' · ' + upText;
  }

  function routeVisible() {
    if (CFG.showWhen === 'always') return true;
    return (location.hash || '').indexOf('#/settings') === 0;
  }

  function updateVisibility() {
    if (!host) return;
    host.style.display = routeVisible() ? 'block' : 'none';
  }

  function toggleMem() {
    memOpen = !memOpen;
    localStorage.setItem('macMonitorMemOpen', memOpen ? '1' : '0');
    els.memDetail.style.display = memOpen ? 'grid' : 'none';
  }

  function toggle() {
    collapsed = !collapsed;
    localStorage.setItem('macMonitorCollapsed', collapsed ? '1' : '0');
    els.panel.classList.toggle('collapsed', collapsed);
    els.chev.textContent = collapsed ? '▸' : '▾';
  }

  function poll() {
    if (inflight) return;
    inflight = true;
    var t0 = performance.now();
    fetch(CFG.api + '?_=' + Date.now(), { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (m) {
        linkRtt = Math.max(1, Math.round(performance.now() - t0));
        setOnline(true);
        render(m);
      })
      .catch(function () {
        setOnline(false);
        renderStatus();
      })
      .finally(function () { inflight = false; });
  }

  function build() {
    host = document.createElement('div');
    host.id = 'mac-monitor-host';
    document.body.appendChild(host);
    root = host.attachShadow({ mode: 'open' });
    root.innerHTML = '<style>' + CSS + '</style>' + HTML;
    els = {
      panel: root.getElementById('mm-panel'),
      head: root.getElementById('mm-head'),
      dot: root.getElementById('mm-dot'),
      chev: root.querySelector('.chev'),
      cpu: root.getElementById('mm-cpu'), cpuBar: root.getElementById('mm-cpu-bar'), cpuSp: root.getElementById('mm-cpu-sp'),
      gpu: root.getElementById('mm-gpu'), gpuBar: root.getElementById('mm-gpu-bar'), gpuSp: root.getElementById('mm-gpu-sp'),
      mem: root.getElementById('mm-mem'), memBar: root.getElementById('mm-mem-bar'), memSp: root.getElementById('mm-mem-sp'),
      memRow: root.getElementById('mm-mem-row'), memDetail: root.getElementById('mm-mem-detail'),
      memApp: root.getElementById('mm-mem-app'), memWired: root.getElementById('mm-mem-wired'),
      memComp: root.getElementById('mm-mem-comp'), memCached: root.getElementById('mm-mem-cached'),
      memSwap: root.getElementById('mm-mem-swap'),
      disk: root.getElementById('mm-disk'), diskBar: root.getElementById('mm-disk-bar'),
      net: root.getElementById('mm-net'), netSp: root.getElementById('mm-net-sp'),
      link: root.getElementById('mm-link'),
      egress: root.getElementById('mm-egress'),
      load: root.getElementById('mm-load')
    };
    els.head.addEventListener('click', toggle);
    els.memRow.addEventListener('click', toggleMem);
    els.memDetail.style.display = memOpen ? 'grid' : 'none';
    updateVisibility();
  }

  function start() {
    build();
    window.addEventListener('hashchange', updateVisibility);
    poll();
    timer = setInterval(poll, CFG.refreshMs);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
