/* 多设备监控面板 for zashboard
 *
 * 数据源：Windows 上的 metrics-hub（聚合本机 / 第二台 Windows / Mac / 安卓）
 * 由 wproxy/ui-patch/apply-ui-patch.ps1 注入 index.html。
 * 使用 Shadow DOM 隔离样式，不干扰 zashboard 自身。
 */
(function () {
  'use strict';

  var CFG = {
    hub: 'http://127.0.0.1:18993/devices',
    refreshMs: 2000,
    // 'settings' = 仅在 #/settings 页显示；'always' = 所有页面显示
    showWhen: 'settings',
    maxPoints: 60
  };

  var MAXPTS = CFG.maxPoints;
  var hist = {};              // hist[deviceId] = {cpu:[],gpu:[],mem:[],net:[]}
  var host = null, root = null, els = {};
  var collapsed = localStorage.getItem('macMonitorCollapsed') === '1';
  var memOpen = localStorage.getItem('macMonitorMemOpen') === '1';
  var selectedId = localStorage.getItem('deviceMonitorSelected') || '';
  var devices = [];
  var lastData = null, lastDataTs = 0;
  var linkRtt = null, hubOk = null, inflight = false;

  var PLATFORM_LABEL = { macos: 'Mac', windows: 'Windows', android: 'Android' };

  var CSS = [
    ':host { all: initial; }',
    '.panel {',
    '  position: fixed; right: 14px; bottom: 14px; z-index: 2147483000;',
    '  width: 262px; box-sizing: border-box;',
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
    '.chips { display:flex; flex-wrap:wrap; gap:4px; padding:0 10px 6px; }',
    '.chip { display:flex; align-items:center; gap:4px; padding:2px 7px; border-radius:999px; border:1px solid var(--bd); background:transparent; color:var(--dim); cursor:pointer; font:inherit; }',
    '.chip.active { color:var(--fg); border-color:var(--fg); background:var(--track); }',
    '.chip .cdot { width:6px; height:6px; border-radius:50%; background:#34c759; }',
    '.chip.off .cdot { background:#ff453a; }',
    '.chip.off { opacity:.55; }',
    '.body { padding:2px 10px 9px; display:grid; gap:6px; }',
    '.panel.stale .body, .panel.stale .chips { opacity:.55; }',
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
    '.lat { display:grid; gap:2px; }',
    '.lat .line, .extra .line { display:flex; justify-content:space-between; }',
    '.extra { color:var(--dim); display:grid; gap:2px; }',
    'canvas { display:block; }',
    '.collapsed .body, .collapsed .chips { display:none; }',
    '.collapsed { width:auto; }'
  ].join('\n');

  var HTML = [
    '<div class="panel' + (collapsed ? ' collapsed' : '') + '" id="mm-panel">',
    '  <header id="mm-head">',
    '    <span class="dot" id="mm-dot"></span>',
    '    <span class="title">设备监控</span>',
    '    <span class="chev">' + (collapsed ? '▸' : '▾') + '</span>',
    '  </header>',
    '  <div class="chips" id="mm-chips"></div>',
    '  <div class="body">',
    '    <div class="row"><span class="k">CPU</span><div class="metric"><span class="val" id="mm-cpu"></span><div class="bar"><i id="mm-cpu-bar" style="background:#4c8dff"></i></div><canvas class="spark" id="mm-cpu-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="row"><span class="k">GPU</span><div class="metric"><span class="val" id="mm-gpu"></span><div class="bar"><i id="mm-gpu-bar" style="background:#a970ff"></i></div><canvas class="spark" id="mm-gpu-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="row clickable" id="mm-mem-row" title="点击查看内存明细"><span class="k">内存</span><div class="metric"><span class="val" id="mm-mem"></span><div class="bar"><i id="mm-mem-bar" style="background:#2fbf71"></i></div><canvas class="spark" id="mm-mem-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="detail" id="mm-mem-detail" style="display:none"></div>',
    '    <div class="row"><span class="k">硬盘</span><div class="metric"><span class="val" id="mm-disk"></span><div class="bar"><i id="mm-disk-bar" style="background:#e8a33d"></i></div></div></div>',
    '    <div class="row"><span class="k">带宽</span><div class="metric"><span class="val" id="mm-net"></span><div class="bar" style="visibility:hidden"></div><canvas class="spark" id="mm-net-sp" width="112" height="32"></canvas></div></div>',
    '    <div class="lat" id="mm-lat"></div>',
    '    <div class="extra">',
    '      <div class="line"><span id="mm-load"></span><span id="mm-updated"></span></div>',
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
  function fmtUptime(up) {
    if (up === null || up === undefined) return '--';
    return up >= 86400 ? (up / 86400).toFixed(1) + 'd' : (up / 3600).toFixed(1) + 'h';
  }
  function barPct(el, v) { el.style.width = Math.max(0, Math.min(100, v || 0)) + '%'; }
  function escapeHtml(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function getHist(id) {
    if (!hist[id]) { hist[id] = { cpu: [], gpu: [], mem: [], net: [] }; }
    return hist[id];
  }

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

  function setDot(cls) { els.dot.className = 'dot' + (cls ? ' ' + cls : ''); }

  function updateChips() {
    var html = '';
    for (var i = 0; i < devices.length; i++) {
      var d = devices[i];
      var on = d.online !== false;
      html += '<button class="chip' + (d.id === selectedId ? ' active' : '') + (on ? '' : ' off') +
        '" data-id="' + escapeHtml(d.id) + '" title="' + escapeHtml(PLATFORM_LABEL[d.platform] || d.platform || '') + '">' +
        '<span class="cdot"></span>' + escapeHtml(d.name || d.id) + '</button>';
    }
    els.chips.innerHTML = html;
  }

  function render(data) {
    devices = (data && data.devices) || [];
    if (!devices.length) { renderHubDown(); return; }
    if (!devices.some(function (d) { return d.id === selectedId; })) {
      var pick = null;
      for (var i = 0; i < devices.length; i++) {
        if (devices[i].online !== false) { pick = devices[i]; break; }
      }
      selectedId = (pick || devices[0]).id;
      localStorage.setItem('deviceMonitorSelected', selectedId);
    }
    updateChips();

    var dev = null;
    for (var j = 0; j < devices.length; j++) if (devices[j].id === selectedId) dev = devices[j];
    var m = (dev && dev.metrics) || {};
    var online = dev && dev.online !== false;
    els.panel.classList.toggle('stale', !online);
    setDot(online ? 'on' : 'off');

    var hh = getHist(selectedId);

    push(hh.cpu, m.cpu_pct);
    els.cpu.textContent = fmtPct(m.cpu_pct);
    barPct(els.cpuBar, m.cpu_pct);
    spark(els.cpuSp, hh.cpu, '#4c8dff');

    var gpu = m.gpu ? m.gpu.util_pct : null;
    push(hh.gpu, gpu);
    els.gpu.textContent = fmtPct(gpu);
    barPct(els.gpuBar, gpu);
    spark(els.gpuSp, hh.gpu, '#a970ff');

    var mem = m.mem || {};
    var memPct = mem.total ? Math.round(mem.used / mem.total * 1000) / 10 : null;
    push(hh.mem, memPct);
    els.mem.textContent = mem.total ? fmtBytes(mem.used) + '/' + fmtBytes(mem.total) : '--';
    barPct(els.memBar, memPct);
    spark(els.memSp, hh.mem, '#2fbf71');

    var memFields = [['app', 'App'], ['wired', 'Wired'], ['compressed', '压缩内存'], ['cached', '缓存'], ['available', '可用'], ['swap_used', 'Swap']];
    var detailHtml = '';
    for (var k = 0; k < memFields.length; k++) {
      var v = mem[memFields[k][0]];
      if (v === null || v === undefined) continue;
      detailHtml += '<div class="line"><span>' + memFields[k][1] + '</span><span>' + fmtBytes(v) + '</span></div>';
    }
    els.memDetail.innerHTML = detailHtml || '<div class="line"><span>明细</span><span>--</span></div>';

    var disk = m.disk || {};
    els.disk.textContent = disk.total ? fmtBytes(disk.used) + '/' + fmtBytes(disk.total) : '--';
    barPct(els.diskBar, disk.used_pct);

    var net = m.net || {};
    var rx = net.rx_bps || 0, tx = net.tx_bps || 0;
    push(hh.net, (rx + tx) / 1024);
    els.net.textContent = '↓' + fmtRate(rx) + ' ↑' + fmtRate(tx);
    spark(els.netSp, hh.net, '#3ac9d6');

    var lat = m.latency || {};
    var latHtml = '';
    Object.keys(lat).forEach(function (key) {
      var lv = lat[key];
      latHtml += '<div class="line"><span>' + escapeHtml(key) + '</span><span>' +
        (lv === null || lv === undefined ? '--' : lv + 'ms') + '</span></div>';
    });
    els.lat.innerHTML = latHtml || '<div class="line"><span>延迟</span><span>--</span></div>';

    var extras = [];
    if (m.load && m.load.length) {
      extras.push(m.load.map(function (v) { return v.toFixed(2); }).join(' '));
    } else if (m.queue !== null && m.queue !== undefined) {
      extras.push('队列 ' + m.queue);
    }
    if (m.battery !== null && m.battery !== undefined) {
      extras.push('电量 ' + m.battery + '%' + (/Charg|Full/i.test(m.charging || '') ? ' ⚡' : ''));
    }
    if (m.uptime_s !== null && m.uptime_s !== undefined) extras.push(fmtUptime(m.uptime_s));
    els.load.textContent = extras.join(' · ') || '--';

    var age = dev.last_seen ? Math.max(0, Math.round((data.ts || 0) - dev.last_seen)) : null;
    els.updated.textContent = (age === null ? '--' : age + 's前') + ' · ' + (linkRtt === null ? '--' : linkRtt + 'ms');
  }

  function renderHubDown() {
    hubOk = false;
    setDot('off');
    if (els.updated) els.updated.textContent = 'Hub 离线';
  }

  function routeVisible() {
    if (CFG.showWhen === 'always') return true;
    return (location.hash || '').indexOf('#/settings') === 0;
  }

  function updateVisibility() {
    if (!host) return;
    host.style.display = routeVisible() ? 'block' : 'none';
  }

  function toggle() {
    collapsed = !collapsed;
    localStorage.setItem('macMonitorCollapsed', collapsed ? '1' : '0');
    els.panel.classList.toggle('collapsed', collapsed);
    els.chev.textContent = collapsed ? '▸' : '▾';
  }

  function toggleMem() {
    memOpen = !memOpen;
    localStorage.setItem('macMonitorMemOpen', memOpen ? '1' : '0');
    els.memDetail.style.display = memOpen ? 'grid' : 'none';
  }

  function poll() {
    if (inflight) return;
    inflight = true;
    var t0 = performance.now();
    fetch(CFG.hub + '?_=' + Date.now(), { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        linkRtt = Math.max(1, Math.round(performance.now() - t0));
        hubOk = true;
        lastData = d;
        lastDataTs = d.ts || 0;
        render(d);
      })
      .catch(function () {
        linkRtt = null;
        renderHubDown();
      })
      .finally(function () { inflight = false; });
  }

  function build() {
    host = document.createElement('div');
    host.id = 'device-monitor-host';
    document.body.appendChild(host);
    root = host.attachShadow({ mode: 'open' });
    root.innerHTML = '<style>' + CSS + '</style>' + HTML;
    els = {
      panel: root.getElementById('mm-panel'),
      head: root.getElementById('mm-head'),
      dot: root.getElementById('mm-dot'),
      chev: root.querySelector('.chev'),
      chips: root.getElementById('mm-chips'),
      cpu: root.getElementById('mm-cpu'), cpuBar: root.getElementById('mm-cpu-bar'), cpuSp: root.getElementById('mm-cpu-sp'),
      gpu: root.getElementById('mm-gpu'), gpuBar: root.getElementById('mm-gpu-bar'), gpuSp: root.getElementById('mm-gpu-sp'),
      mem: root.getElementById('mm-mem'), memBar: root.getElementById('mm-mem-bar'), memSp: root.getElementById('mm-mem-sp'),
      memRow: root.getElementById('mm-mem-row'), memDetail: root.getElementById('mm-mem-detail'),
      disk: root.getElementById('mm-disk'), diskBar: root.getElementById('mm-disk-bar'),
      net: root.getElementById('mm-net'), netSp: root.getElementById('mm-net-sp'),
      lat: root.getElementById('mm-lat'),
      load: root.getElementById('mm-load'),
      updated: root.getElementById('mm-updated')
    };
    els.head.addEventListener('click', toggle);
    els.memRow.addEventListener('click', toggleMem);
    els.memDetail.style.display = memOpen ? 'grid' : 'none';
    els.chips.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('.chip') : null;
      if (!btn) return;
      selectedId = btn.getAttribute('data-id');
      localStorage.setItem('deviceMonitorSelected', selectedId);
      if (lastData) render(lastData);
    });
    updateVisibility();
  }

  function start() {
    build();
    window.addEventListener('hashchange', updateVisibility);
    poll();
    setInterval(poll, CFG.refreshMs);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
