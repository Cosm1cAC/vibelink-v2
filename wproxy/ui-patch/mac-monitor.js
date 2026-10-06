/* 多设备监控页 for zashboard
 *
 * 无侵入注入：
 *  - 在侧边栏（设置上方）插入「监控」导航项；移动端底部 tab 栏也会插入
 *  - 点击后在内容区显示全屏监控页（设备卡片网格）
 *  - 数据源：Windows 上的 metrics-hub（聚合本机 / 第二台 Windows / Mac / 安卓）
 * 由 wproxy/ui-patch/apply-ui-patch.ps1 注入 index.html。
 */
(function () {
  'use strict';

  var CFG = {
    hub: 'http://127.0.0.1:18993/devices',
    refreshMs: 2000,
    maxPoints: 60
  };
  var NAV_LABEL = '监控';
  var NAV_KEY = 'wproxy-monitor';

  var MONITOR_ICON =
    '<svg class="nav-item-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 20.5h8M12 17.5v3"/><path d="M6.5 12.5l2.7-3.2 2.4 3.8 2-1.9 3.9.6"/></svg>';
  var MONITOR_ICON_TAB =
    MONITOR_ICON.replace('nav-item-icon', 'tab-bar-icon');

  var PLATFORM_LABEL = { macos: 'Mac', windows: 'Windows', android: 'Android' };

  var devices = [], lastTs = 0, hubOk = null, linkRtt = null, inflight = false;
  var hist = {};
  var cards = {};
  var pageOpen = localStorage.getItem('wproxyMonitorPage') === '1';

  var navMenu = null, navItem = null, navIndicator = null;
  var mobileBtn = null, tabBar = null, tabIndicator = null;
  var overlay = null, grid = null, pageStatus = null;

  /* ---------------------------------------------------------------- utils */

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
    if (up === null || up === undefined || up === 0) return '--';
    return up >= 86400 ? (up / 86400).toFixed(1) + 'd' : (up / 3600).toFixed(1) + 'h';
  }
  function escapeHtml(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function barPct(el, v) { el.style.width = Math.max(0, Math.min(100, v || 0)) + '%'; }
  function setAttr(el, name, val) { if (el && el.getAttribute(name) !== val) el.setAttribute(name, val); }
  function getHist(id) {
    if (!hist[id]) hist[id] = { cpu: [], gpu: [], mem: [], net: [] };
    return hist[id];
  }
  function push(arr, v) {
    arr.push(Math.max(0, v || 0));
    if (arr.length > CFG.maxPoints) arr.shift();
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
      var x = j * (w - 1) / (CFG.maxPoints - 1);
      var y = h - 3 - (Math.max(0, data[j]) / max) * (h - 6);
      if (j === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.stroke();
    ctx.lineTo((data.length - 1) * (w - 1) / (CFG.maxPoints - 1), h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fillStyle = color + '22'; ctx.fill();
  }

  /* ---------------------------------------------------------------- styles */

  function injectStyles() {
    if (document.getElementById('wproxy-monitor-style')) return;
    var st = document.createElement('style');
    st.id = 'wproxy-monitor-style';
    st.textContent = [
      '.wproxy-nav-active { background-color: color-mix(in srgb, var(--color-base-content) 9%, transparent); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-base-content) 3%, transparent); }',
      '.wproxy-monitor-page { position:absolute; inset:0; z-index:10; overflow-y:auto; background:var(--color-base-100); color:var(--color-base-content); padding:1rem; }',
      '@media (min-width:768px){ .wproxy-monitor-page{ padding:1.5rem 2rem; } }',
      '.wproxy-monitor-page.wproxy-hidden { display:none; }',
      '.wproxy-mon-inner { max-width:1400px; margin:0 auto; }',
      '.wproxy-mon-head { display:flex; align-items:baseline; gap:.75rem; flex-wrap:wrap; margin-bottom:1rem; }',
      '.wproxy-mon-head h1 { font-size:1.25rem; font-weight:600; letter-spacing:.01em; }',
      '.wproxy-mon-status { font-size:.8rem; color:color-mix(in srgb, var(--color-base-content) 55%, transparent); display:flex; align-items:center; gap:.5rem; }',
      '.wproxy-mon-status .dot { width:8px; height:8px; border-radius:50%; background:#34c759; }',
      '.wproxy-mon-status.off .dot { background:#ff453a; }',
      '.wproxy-mon-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(320px, 1fr)); gap:1rem; }',
      '.wproxy-card { background:var(--color-base-200); border-radius:1rem; padding:.9rem 1rem .7rem; min-width:0; }',
      '.wproxy-card-head { display:flex; align-items:center; gap:.5rem; margin-bottom:.55rem; min-width:0; }',
      '.wproxy-card-head .cdot { width:8px; height:8px; border-radius:50%; background:#34c759; flex:none; }',
      '.wproxy-card.off .cdot { background:#ff453a; }',
      '.wproxy-card.off .wproxy-rows { opacity:.55; }',
      '.wproxy-card-head .cname { font-weight:600; font-size:.9rem; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }',
      '.wproxy-card-head .cplat { font-size:.68rem; padding:.05rem .45rem; border-radius:999px; background:color-mix(in srgb, var(--color-base-content) 9%, transparent); color:color-mix(in srgb, var(--color-base-content) 70%, transparent); flex:none; }',
      '.wproxy-card-head .cage { margin-left:auto; font-size:.72rem; color:color-mix(in srgb, var(--color-base-content) 45%, transparent); flex:none; }',
      '.wproxy-rows { display:grid; gap:.42rem; }',
      '.wproxy-row { display:grid; grid-template-columns:2.6rem 1fr; gap:.6rem; align-items:center; }',
      '.wproxy-row .k { font-size:.78rem; color:color-mix(in srgb, var(--color-base-content) 55%, transparent); }',
      '.wproxy-metric { display:flex; align-items:center; gap:.55rem; min-width:0; }',
      '.wproxy-val { min-width:4.6rem; text-align:right; font-size:.8rem; font-variant-numeric:tabular-nums; white-space:nowrap; }',
      '.wproxy-bar { flex:1; height:5px; border-radius:3px; background:color-mix(in srgb, var(--color-base-content) 8%, transparent); overflow:hidden; min-width:2rem; }',
      '.wproxy-bar i { display:block; height:100%; width:0; border-radius:3px; transition:width .35s ease; }',
      '.wproxy-spark { width:4.5rem; height:1.1rem; flex:none; opacity:.9; }',
      '.wproxy-clickable { cursor:pointer; border-radius:.5rem; }',
      '.wproxy-clickable:hover { background:color-mix(in srgb, var(--color-base-content) 6%, transparent); }',
      '.wproxy-detail { display:grid; gap:.15rem; padding:.1rem 0 .15rem; }',
      '.wproxy-line { display:flex; justify-content:space-between; gap:.5rem; font-size:.75rem; color:color-mix(in srgb, var(--color-base-content) 55%, transparent); }',
      '.wproxy-line span:last-child { font-variant-numeric:tabular-nums; }',
      '.wproxy-extra { margin-top:.15rem; }',
      '.wproxy-foot { margin-top:1rem; font-size:.72rem; color:color-mix(in srgb, var(--color-base-content) 40%, transparent); text-align:right; }',
      '@media (prefers-reduced-motion: reduce){ .wproxy-bar i{ transition:none; } }'
    ].join('\n');
    document.head.appendChild(st);
  }

  /* ---------------------------------------------------------------- nav */

  function injectNav() {
    if (navItem && document.body.contains(navItem)) return true;
    var menus = document.querySelectorAll('ul.nav-menu');
    var menu = null;
    for (var i = 0; i < menus.length; i++) {
      if (menus[i].querySelector('[data-nav-key="settings"]')) { menu = menus[i]; break; }
    }
    if (!menu) return false;
    var settingsLink = menu.querySelector('[data-nav-key="settings"]');
    var settingsLi = settingsLink.closest('li');
    if (!settingsLi) return false;
    var li = settingsLi.cloneNode(true);
    var a = li.querySelector('a, button');
    if (!a) return false;
    a.removeAttribute('href');
    a.removeAttribute('aria-current');
    a.setAttribute('data-nav-key', NAV_KEY);
    var icon = a.querySelector('svg');
    if (icon) icon.outerHTML = MONITOR_ICON;
    var span = a.querySelector('span');
    if (span) span.textContent = NAV_LABEL;
    a.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      openPage();
    });
    menu.insertBefore(li, settingsLi);
    navMenu = menu;
    var nav = menu.closest('nav');
    navIndicator = nav ? nav.querySelector('.nav-indicator') : null;
    navItem = a;
    return true;
  }

  function injectMobileTab() {
    if (mobileBtn && document.body.contains(mobileBtn)) return true;
    var bar = document.querySelector('.tab-bar');
    if (!bar) return false;
    var ref = bar.querySelector('.tab-bar-item');
    if (!ref) return false;
    var btn = ref.cloneNode(true);
    btn.removeAttribute('aria-current');
    var icon = btn.querySelector('svg');
    if (icon) icon.outerHTML = MONITOR_ICON_TAB;
    var label = btn.querySelector('span');
    if (label) label.textContent = NAV_LABEL;
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      openPage();
    });
    bar.appendChild(btn);
    tabBar = bar;
    tabIndicator = bar.querySelector('.tab-bar-indicator');
    var count = bar.querySelectorAll('.tab-bar-item').length;
    bar.style.setProperty('--tab-count', String(count));
    mobileBtn = btn;
    return true;
  }

  /* ---------------------------------------------------------------- page */

  function ensureOverlay() {
    if (overlay && document.body.contains(overlay)) return true;
    var home = document.querySelector('.home-page');
    if (!home) return false;
    var content = null;
    for (var i = 0; i < home.children.length; i++) {
      var c = home.children[i];
      if (!c.querySelector('[data-nav-key="settings"]')) content = c;
    }
    if (!content) return false;
    overlay = document.createElement('div');
    overlay.id = 'wproxy-monitor-page';
    overlay.className = 'wproxy-monitor-page wproxy-hidden';
    overlay.innerHTML =
      '<div class="wproxy-mon-inner">' +
      '  <div class="wproxy-mon-head">' +
      '    <h1>设备监控</h1>' +
      '    <div class="wproxy-mon-status" id="wproxy-mon-status"><span class="dot"></span><span id="wproxy-mon-status-text">连接中…</span></div>' +
      '  </div>' +
      '  <div class="wproxy-mon-grid" id="wproxy-mon-grid"></div>' +
      '  <div class="wproxy-foot" id="wproxy-mon-foot"></div>' +
      '</div>';
    content.appendChild(overlay);
    grid = overlay.querySelector('#wproxy-mon-grid');
    pageStatus = overlay.querySelector('#wproxy-mon-status');
    return true;
  }

  var CARD_HTML = [
    '<div class="wproxy-card-head"><span class="cdot"></span><span class="cname"></span><span class="cplat"></span><span class="cage"></span></div>',
    '<div class="wproxy-rows">',
    '  <div class="wproxy-row"><span class="k">CPU</span><div class="wproxy-metric"><span class="wproxy-val" data-f="cpu"></span><div class="wproxy-bar"><i data-f="cpu-bar" style="background:#4c8dff"></i></div><canvas class="wproxy-spark" data-f="cpu-sp" width="144" height="36"></canvas></div></div>',
    '  <div class="wproxy-row"><span class="k">GPU</span><div class="wproxy-metric"><span class="wproxy-val" data-f="gpu"></span><div class="wproxy-bar"><i data-f="gpu-bar" style="background:#a970ff"></i></div><canvas class="wproxy-spark" data-f="gpu-sp" width="144" height="36"></canvas></div></div>',
    '  <div class="wproxy-row wproxy-clickable" data-f="mem-row" title="点击查看内存明细"><span class="k">内存</span><div class="wproxy-metric"><span class="wproxy-val" data-f="mem"></span><div class="wproxy-bar"><i data-f="mem-bar" style="background:#2fbf71"></i></div><canvas class="wproxy-spark" data-f="mem-sp" width="144" height="36"></canvas></div></div>',
    '  <div class="wproxy-detail" data-f="mem-detail" style="display:none"></div>',
    '  <div class="wproxy-row"><span class="k">硬盘</span><div class="wproxy-metric"><span class="wproxy-val" data-f="disk"></span><div class="wproxy-bar"><i data-f="disk-bar" style="background:#e8a33d"></i></div></div></div>',
    '  <div class="wproxy-row"><span class="k">带宽</span><div class="wproxy-metric"><span class="wproxy-val" data-f="net"></span><div class="wproxy-bar" style="visibility:hidden"></div><canvas class="wproxy-spark" data-f="net-sp" width="144" height="36"></canvas></div></div>',
    '  <div data-f="lat"></div>',
    '  <div class="wproxy-line wproxy-extra"><span data-f="extra"></span><span></span></div>',
    '</div>'
  ].join('');

  function ensureCard(id) {
    if (cards[id]) return cards[id];
    var root = document.createElement('div');
    root.className = 'wproxy-card';
    root.innerHTML = CARD_HTML;
    grid.appendChild(root);
    var refs = { root: root };
    var nodes = root.querySelectorAll('[data-f]');
    for (var i = 0; i < nodes.length; i++) refs[nodes[i].getAttribute('data-f')] = nodes[i];
    refs.memOpen = false;
    refs['mem-row'].addEventListener('click', function () {
      refs.memOpen = !refs.memOpen;
      refs['mem-detail'].style.display = refs.memOpen ? 'grid' : 'none';
    });
    cards[id] = refs;
    return refs;
  }

  function updateCard(refs, dev, ts) {
    var m = dev.metrics || {};
    var online = dev.online !== false;
    refs.root.classList.toggle('off', !online);
    refs.root.querySelector('.cname').textContent = dev.name || dev.id;
    refs.root.querySelector('.cplat').textContent = PLATFORM_LABEL[dev.platform] || dev.platform || '';
    var age = dev.last_seen ? Math.max(0, Math.round(ts - dev.last_seen)) : null;
    refs.root.querySelector('.cage').textContent = online ? (age === null ? '' : age + 's 前') : '离线';

    var hh = getHist(dev.id);

    push(hh.cpu, m.cpu_pct);
    refs.cpu.textContent = fmtPct(m.cpu_pct);
    barPct(refs['cpu-bar'], m.cpu_pct);
    spark(refs['cpu-sp'], hh.cpu, '#4c8dff');

    var gpu = m.gpu ? m.gpu.util_pct : null;
    push(hh.gpu, gpu);
    refs.gpu.textContent = fmtPct(gpu);
    barPct(refs['gpu-bar'], gpu);
    spark(refs['gpu-sp'], hh.gpu, '#a970ff');

    var mem = m.mem || {};
    var memPct = mem.total ? Math.round(mem.used / mem.total * 1000) / 10 : null;
    push(hh.mem, memPct);
    refs.mem.textContent = mem.total ? fmtBytes(mem.used) + ' / ' + fmtBytes(mem.total) : '--';
    barPct(refs['mem-bar'], memPct);
    spark(refs['mem-sp'], hh.mem, '#2fbf71');
    var fields = [['app', 'App'], ['wired', 'Wired'], ['compressed', '压缩内存'], ['cached', '缓存'], ['available', '可用'], ['swap_used', 'Swap']];
    var detail = '';
    for (var i = 0; i < fields.length; i++) {
      var v = mem[fields[i][0]];
      if (v === null || v === undefined) continue;
      detail += '<div class="wproxy-line"><span>' + fields[i][1] + '</span><span>' + fmtBytes(v) + '</span></div>';
    }
    refs['mem-detail'].innerHTML = detail || '<div class="wproxy-line"><span>明细</span><span>--</span></div>';

    var disk = m.disk || {};
    refs.disk.textContent = disk.total ? fmtBytes(disk.used) + ' / ' + fmtBytes(disk.total) + (disk.used_pct !== null && disk.used_pct !== undefined ? ' · ' + disk.used_pct + '%' : '') : '--';
    barPct(refs['disk-bar'], disk.used_pct);

    var net = m.net || {};
    var rx = net.rx_bps || 0, tx = net.tx_bps || 0;
    push(hh.net, (rx + tx) / 1024);
    refs.net.textContent = '↓' + fmtRate(rx) + '  ↑' + fmtRate(tx);
    spark(refs['net-sp'], hh.net, '#3ac9d6');

    var lat = m.latency || {};
    var latHtml = '';
    Object.keys(lat).forEach(function (key) {
      var lv = lat[key];
      latHtml += '<div class="wproxy-line"><span>' + escapeHtml(key) + '</span><span>' + (lv === null || lv === undefined ? '--' : lv + ' ms') + '</span></div>';
    });
    refs.lat.innerHTML = latHtml;

    var extras = [];
    if (m.load && m.load.length) extras.push('负载 ' + m.load.map(function (v) { return v.toFixed(2); }).join(' '));
    else if (m.queue !== null && m.queue !== undefined) extras.push('队列 ' + m.queue);
    if (m.battery !== null && m.battery !== undefined) extras.push('电量 ' + m.battery + '%' + (/Charg|Full/i.test(m.charging || '') ? ' ⚡' : ''));
    if (m.uptime_s) extras.push('运行 ' + fmtUptime(m.uptime_s));
    refs.extra.textContent = extras.join(' · ');
  }

  function renderPage(data) {
    if (!pageOpen || !overlay) return;
    if (!grid) return;
    var list = (data && data.devices) || [];
    var seen = {};
    for (var i = 0; i < list.length; i++) {
      var dev = list[i];
      seen[dev.id] = true;
      updateCard(ensureCard(dev.id), dev, data.ts || 0);
    }
    Object.keys(cards).forEach(function (id) {
      if (!seen[id]) { cards[id].root.remove(); delete cards[id]; }
    });
    var onlineCount = list.filter(function (d) { return d.online !== false; }).length;
    pageStatus.classList.toggle('off', !hubOk);
    var stText = hubOk
      ? onlineCount + ' / ' + list.length + ' 台在线'
      : 'Hub 离线';
    overlay.querySelector('#wproxy-mon-status-text').textContent = stText;
    overlay.querySelector('#wproxy-mon-foot').textContent =
      '数据源 metrics-hub · 刷新 ' + CFG.refreshMs / 1000 + 's · ' +
      (linkRtt === null ? '' : linkRtt + 'ms') +
      (data && data.ts ? ' · ' + new Date(data.ts * 1000).toLocaleTimeString() : '');
  }

  function openPage() {
    pageOpen = true;
    localStorage.setItem('wproxyMonitorPage', '1');
    if (ensureOverlay() && lastData) renderPage(lastData);
    else if (ensureOverlay() && devices.length) renderPage({ ts: lastTs, devices: devices });
    applyState();
  }

  function closePage() {
    pageOpen = false;
    localStorage.setItem('wproxyMonitorPage', '0');
    applyState();
  }

  function applyState() {
    if (overlay) overlay.classList.toggle('wproxy-hidden', !pageOpen);
    if (navItem) navItem.classList.toggle('wproxy-nav-active', pageOpen);
    if (navIndicator) {
      var disp = pageOpen ? 'none' : '';
      if (navIndicator.style.display !== disp) navIndicator.style.display = disp;
    }
    setAttr(mobileBtn, 'aria-current', pageOpen ? 'page' : '');
    if (tabIndicator) {
      var td = pageOpen ? 'none' : '';
      if (tabIndicator.style.display !== td) tabIndicator.style.display = td;
    }
  }

  /* ---------------------------------------------------------------- sync */

  var syncQueued = false;
  function scheduleSync() {
    if (syncQueued) return;
    syncQueued = true;
    setTimeout(function () { syncQueued = false; sync(); }, 200);
  }
  function sync() {
    injectStyles();
    injectNav();
    injectMobileTab();
    if (pageOpen) {
      if (ensureOverlay()) {
        if (lastData) renderPage(lastData);
        else if (devices.length) renderPage({ ts: lastTs, devices: devices });
      }
    }
    applyState();
  }

  /* ---------------------------------------------------------------- poll */

  var lastData = null;
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
        lastTs = d.ts || 0;
        devices = d.devices || [];
        if (pageOpen) renderPage(d);
      })
      .catch(function () {
        hubOk = false;
        linkRtt = null;
        if (pageOpen && overlay) {
          pageStatus.classList.add('off');
          overlay.querySelector('#wproxy-mon-status-text').textContent = 'Hub 离线';
        }
      })
      .finally(function () { inflight = false; });
  }

  /* ---------------------------------------------------------------- boot */

  window.addEventListener('hashchange', function () {
    if (pageOpen) closePage();
  });

  function boot() {
    sync();
    new MutationObserver(scheduleSync).observe(document.documentElement, { childList: true, subtree: true });
    setInterval(sync, 3000);
    poll();
    setInterval(poll, CFG.refreshMs);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
