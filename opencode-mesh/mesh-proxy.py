#!/usr/bin/env python3
"""opencode-mesh 徽标代理。

在 opencode server（默认 127.0.0.1:4097）前做一层极简反向代理（默认 0.0.0.0:4096）：
- 对 HTML 响应注入徽标脚本，页面左下角显示到当前主机的路径是「直连」还是「中继」（按 RTT 判断）
- 其余流量（SSE / 上传 / 下载 / 预检）原样透传
- /__mesh/badge.js 由本代理本地应答

仅标准库实现，macOS 系统 python3 与 Windows python 均可运行。
"""
import argparse
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import threading
import time

INJECT_TAG = b'<script src="/__mesh/badge.js" defer></script>'

BADGE_JS = """(function () {
  if (window.__ocMeshBadge) return;
  window.__ocMeshBadge = true;
  var el = document.createElement('div');
  el.style.cssText = [
    'position:fixed', 'left:10px', 'bottom:10px', 'z-index:2147483647',
    'padding:3px 10px', 'border-radius:999px',
    'font:12px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif',
    'color:#fff', 'background:#6b7280', 'opacity:.9', 'pointer-events:none',
    'transition:background .3s', 'box-shadow:0 1px 4px rgba(0,0,0,.25)'
  ].join(';');
  el.textContent = '测速中…';
  function mount() {
    if (document.body) { document.body.appendChild(el); } else { setTimeout(mount, 300); }
  }
  mount();

  var pathInfo = null;
  var rtt = null;

  function label() {
    var ms = rtt === null ? '' : ' ' + Math.round(rtt) + 'ms';
    if (!pathInfo) return '测速中…' + ms;
    var p = pathInfo.path;
    if (p === 'local') return '本机' + ms;
    if (p === 'direct') return (pathInfo.reason === 'lan' ? '直连·局域网' : '直连·Tailscale') + ms;
    if (p === 'relay') return '中继·DERP' + ms;
    if (p === 'wan') return '公网直连' + ms;
    return '路径未知' + ms;
  }

  function paint() {
    el.textContent = label();
    el.title = pathInfo
      ? ('path=' + pathInfo.path + ' reason=' + pathInfo.reason + ' peer=' + pathInfo.peer +
         (pathInfo.tailscale && pathInfo.tailscale.relay ? ' relay=' + pathInfo.tailscale.relay : ''))
      : '';
    var good = pathInfo && (pathInfo.path === 'direct' || pathInfo.path === 'local');
    var mid = pathInfo && pathInfo.path === 'relay';
    el.style.background = good ? '#16a34a' : (mid ? '#d97706' : '#6b7280');
  }

  function probePath() {
    fetch('/__mesh/path', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (j) { pathInfo = j; paint(); })
      .catch(function () { pathInfo = null; paint(); });
  }

  var ema = null;
  function probeRtt() {
    var t0 = performance.now();
    fetch('/global/health', { cache: 'no-store' })
      .then(function (r) { return r.arrayBuffer(); })
      .catch(function () {})
      .then(function () {
        var ms = performance.now() - t0;
        ema = (ema === null) ? ms : (ema * 0.5 + ms * 0.5);
        rtt = ema;
        paint();
      });
  }

  probePath();
  probeRtt();
  setInterval(probePath, 10000);
  setInterval(probeRtt, 5000);
})();
""".encode('utf-8')


# --- path classification -------------------------------------------------
# Same contract as the hub: report the path the bytes actually take, with a
# reason, instead of guessing from an RTT threshold.

TS_CANDIDATES = [
    os.path.join(os.environ.get('ProgramFiles', r'C:\Program Files'), 'Tailscale', 'tailscale.exe'),
    'tailscale.exe',
    'tailscale',
    '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
]

_ts_lock = threading.Lock()
_ts_cache = {'at': 0.0, 'peers': {}}


def read_tailscale_peers():
    now = time.time()
    with _ts_lock:
        if now - _ts_cache['at'] < 5:
            return _ts_cache['peers']
    peers = {}
    for exe in TS_CANDIDATES:
        if not (os.path.isabs(exe) and os.path.exists(exe)) and shutil.which(exe) is None:
            continue
        try:
            out = subprocess.run([exe, 'status', '--json'], capture_output=True, timeout=2).stdout
            status = json.loads(out or b'{}')
        except Exception:
            continue
        for peer in (status.get('Peer') or {}).values():
            info = {
                'direct': bool(peer.get('CurAddr')),
                'relay': peer.get('Relay') or None,
                'host': peer.get('HostName') or '',
            }
            for ip in peer.get('TailscaleIPs') or []:
                peers[ip] = info
        self_peer = status.get('Self')
        if self_peer:
            for ip in self_peer.get('TailscaleIPs') or []:
                peers[ip] = {'direct': True, 'relay': None, 'host': self_peer.get('HostName') or '', 'self': True}
        break
    with _ts_lock:
        _ts_cache['at'] = now
        _ts_cache['peers'] = peers
    return peers


def classify_path(peer_ip):
    try:
        addr = ipaddress.ip_address(peer_ip)
    except ValueError:
        return {'peer': peer_ip, 'ipClass': 'wan', 'path': 'wan', 'reason': 'unparsed'}
    if addr.is_loopback:
        return {'peer': peer_ip, 'ipClass': 'loopback', 'path': 'local', 'reason': 'loopback'}
    if addr in ipaddress.ip_network('100.64.0.0/10'):
        ts = read_tailscale_peers().get(peer_ip)
        if not ts:
            return {'peer': peer_ip, 'ipClass': 'tailscale', 'path': 'unknown', 'reason': 'tailscale-status-unavailable'}
        if ts['direct']:
            return {'peer': peer_ip, 'ipClass': 'tailscale', 'path': 'direct', 'reason': 'tailscale-direct', 'tailscale': ts}
        if ts['relay']:
            return {'peer': peer_ip, 'ipClass': 'tailscale', 'path': 'relay', 'reason': 'tailscale-derp', 'tailscale': ts}
        return {'peer': peer_ip, 'ipClass': 'tailscale', 'path': 'unknown', 'reason': 'tailscale-no-endpoint', 'tailscale': ts}
    if addr.is_private:
        return {'peer': peer_ip, 'ipClass': 'lan', 'path': 'direct', 'reason': 'lan'}
    return {'peer': peer_ip, 'ipClass': 'wan', 'path': 'wan', 'reason': 'public-route'}


class BufSock:
    def __init__(self, sock, initial=b''):
        self.sock = sock
        self.buf = bytearray(initial)

    def read_until(self, delim):
        while delim not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                return None
            self.buf += chunk
        idx = self.buf.index(delim) + len(delim)
        out = bytes(self.buf[:idx])
        del self.buf[:idx]
        return out

    def read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                break
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out


def parse_head(head):
    lines = head.split(b'\r\n')
    first = lines[0].decode('latin-1')
    headers = []
    for line in lines[1:]:
        if b':' in line:
            k, _, v = line.partition(b':')
            headers.append((k.decode('latin-1').strip(), v.decode('latin-1').strip()))
    return first, headers


def get_header(headers, name):
    for k, v in headers:
        if k.lower() == name:
            return v
    return None


def read_chunked(buf):
    body = bytearray()
    while True:
        line = buf.read_until(b'\r\n')
        if line is None:
            break
        size = int(line.strip().split(b';')[0] or b'0', 16)
        if size == 0:
            buf.read_until(b'\r\n')
            break
        body += buf.read_exact(size)
        buf.read_exact(2)
    return bytes(body)


def inject(html):
    idx = html.lower().rfind(b'</body>')
    if idx == -1:
        return html + INJECT_TAG
    return html[:idx] + INJECT_TAG + html[idx:]


def send_simple(client, status, ctype, body):
    head = (
        'HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %d\r\n'
        'Cache-Control: no-store\r\nConnection: close\r\n\r\n'
        % (status, ctype, len(body))
    )
    client.sendall(head.encode('latin-1') + body)


def handle(client, upstream):
    client.settimeout(None)
    up = None
    try:
        buf = BufSock(client)
        req_head = buf.read_until(b'\r\n\r\n')
        if not req_head:
            return
        first, headers = parse_head(req_head[:-4])
        parts = (first.split(' ') + ['', ''])[:3]
        method, path = parts[0], parts[1]

        if method == 'GET' and path == '/__mesh/badge.js':
            send_simple(client, '200 OK', 'application/javascript; charset=utf-8', BADGE_JS)
            return

        if method == 'GET' and path == '/__mesh/path':
            peer = client.getpeername()[0]
            body = json.dumps(classify_path(peer), ensure_ascii=False).encode('utf-8')
            send_simple(client, '200 OK', 'application/json; charset=utf-8', body)
            return

        up = socket.create_connection(upstream, timeout=10)
        up.settimeout(None)
        up.sendall(req_head)

        leftover = bytes(buf.buf)
        buf.buf = bytearray()

        def pump_request():
            try:
                if leftover:
                    up.sendall(leftover)
                while True:
                    data = client.recv(65536)
                    if not data:
                        break
                    up.sendall(data)
            except OSError:
                pass
            finally:
                try:
                    up.shutdown(socket.SHUT_WR)
                except OSError:
                    pass

        threading.Thread(target=pump_request, daemon=True).start()

        up_buf = BufSock(up)
        resp_head = up_buf.read_until(b'\r\n\r\n')
        if not resp_head:
            return
        status_line, resp_headers = parse_head(resp_head[:-4])
        ctype = (get_header(resp_headers, 'content-type') or '').lower()
        is_html = 'text/html' in ctype and method != 'HEAD'

        if is_html:
            te = (get_header(resp_headers, 'transfer-encoding') or '').lower()
            cl = get_header(resp_headers, 'content-length')
            if 'chunked' in te:
                body = read_chunked(up_buf)
            elif cl is not None:
                body = up_buf.read_exact(int(cl))
            else:
                body = bytes(up_buf.buf)
                up_buf.buf = bytearray()
                while True:
                    chunk = up.recv(65536)
                    if not chunk:
                        break
                    body += chunk
            body = inject(body)
            out_headers = [(k, v) for k, v in resp_headers
                           if k.lower() not in ('content-length', 'transfer-encoding', 'connection')]
            out_headers.append(('Content-Length', str(len(body))))
            out_headers.append(('Connection', 'close'))
            head = status_line.encode('latin-1') + b'\r\n'
            for k, v in out_headers:
                head += k.encode('latin-1') + b': ' + v.encode('latin-1') + b'\r\n'
            client.sendall(head + b'\r\n' + body)
        else:
            client.sendall(resp_head)
            if up_buf.buf:
                client.sendall(bytes(up_buf.buf))
                up_buf.buf = bytearray()
            while True:
                try:
                    data = up.recv(65536)
                except OSError:
                    break
                if not data:
                    break
                try:
                    client.sendall(data)
                except OSError:
                    break
    except OSError:
        pass
    finally:
        for sock in (up, client):
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


def main():
    parser = argparse.ArgumentParser(description='opencode-mesh badge proxy')
    parser.add_argument('--listen', default='0.0.0.0:4096')
    parser.add_argument('--upstream', default='127.0.0.1:4097')
    args = parser.parse_args()

    listen_host, listen_port = args.listen.rsplit(':', 1)
    upstream_host, upstream_port = args.upstream.rsplit(':', 1)
    upstream = (upstream_host, int(upstream_port))

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((listen_host, int(listen_port)))
    server.listen(128)
    print('mesh-proxy listening on %s -> %s' % (args.listen, args.upstream), flush=True)

    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client, upstream), daemon=True).start()


if __name__ == '__main__':
    main()
