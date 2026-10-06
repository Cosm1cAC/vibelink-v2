#!/usr/bin/env python3
"""Mac system metrics agent for the wproxy dashboard.

Stdlib only (runs on the system python3, ALF-friendly).
Binds to the Tailscale IP and serves JSON with permissive CORS so the
zashboard page (http://127.0.0.1:9097/ui/) can poll it directly.

Metrics: cpu, gpu, memory, disk, network bandwidth, latency, load, uptime.
"""
import json
import os
import re
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIND = os.environ.get("METRICS_BIND", "100.105.59.31")
PORT = int(os.environ.get("METRICS_PORT", "18992"))
VERSION = "1.1.0"
NCPU = os.cpu_count() or 1
LAT_TARGETS = [("1.1.1.1", 443), ("8.8.8.8", 443), ("223.5.5.5", 443)]
NET_IFACES = ("en0", "en1", "en2", "en3", "utun0", "utun1", "utun2", "utun3", "utun4", "utun5")
TS_CLI = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
TS_PEERS = {
    "100.74.71.80": "haoshao",
    "100.123.253.85": "desktop-9m0bu3h",
    "100.126.194.61": "phone-mihomo",
}

_lock = threading.Lock()
_state = {
    "host": None,
    "model": None,
    "boot_time": None,
    "cpu_pct": None,
    "load": None,
    "gpu": {},
    "mem": {},
    "disk": {},
    "net": {},
    "latency": {},
    "tailnet": {},
}


def _run(cmd, timeout=6):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout
    except Exception:
        return ""


def _parse_ps_time(t):
    # "0:00.01" | "12:34.56" | "1:02:03" | "2-01:02:03"
    t = t.strip()
    days = 0
    if "-" in t:
        d, t = t.split("-", 1)
        days = int(d)
    parts = [float(p) for p in t.split(":")]
    if len(parts) == 3:
        h, m, s = parts
    elif len(parts) == 2:
        h, m, s = 0.0, parts[0], parts[1]
    else:
        h, m, s = 0.0, 0.0, parts[0]
    return days * 86400 + h * 3600 + m * 60 + s


def _cpu_read_total():
    total = 0.0
    for line in _run(["ps", "-Ao", "time="]).splitlines():
        line = line.strip()
        if line:
            total += _parse_ps_time(line)
    return total


def _cpu_loop():
    prev = _cpu_read_total()
    prev_t = time.monotonic()
    while True:
        time.sleep(1.0)
        cur = _cpu_read_total()
        cur_t = time.monotonic()
        dt = cur_t - prev_t
        if dt > 0:
            pct = (cur - prev) / (dt * NCPU) * 100.0
            pct = max(0.0, min(100.0, pct))
            with _lock:
                old = _state["cpu_pct"]
                _state["cpu_pct"] = round(pct, 1) if old is None else round(old * 0.4 + pct * 0.6, 1)
        prev, prev_t = cur, cur_t


def _gpu_loop():
    while True:
        out = _run(["ioreg", "-r", "-d", "1", "-c", "IOAccelerator", "-l"])

        def g(pat):
            m = re.search(pat, out)
            return int(m.group(1)) if m else None

        gpu = {
            "util_pct": g(r'"Device Utilization %"\s*=\s*(\d+)'),
            "tiler_pct": g(r'"Tiler Utilization %"\s*=\s*(\d+)'),
            "renderer_pct": g(r'"Renderer Utilization %"\s*=\s*(\d+)'),
            "mem_used": g(r'"In use system memory"\s*=\s*(\d+)'),
        }
        with _lock:
            _state["gpu"] = gpu
        time.sleep(2.0)


def _mem_loop():
    total = int((_run(["sysctl", "-n", "hw.memsize"]).strip() or "0"))
    while True:
        out = _run(["vm_stat"])
        mo = re.search(r"page size of (\d+) bytes", out)
        ps = int(mo.group(1)) if mo else 4096
        vals = {}
        for line in out.splitlines():
            m = re.match(r'\s*"?([A-Za-z][^":]*?)"?:\s+(\d+)\.', line)
            if m:
                vals[m.group(1).strip()] = int(m.group(2))
        anon = vals.get("Anonymous pages", 0)
        fileb = vals.get("File-backed pages", 0)
        purg = vals.get("Pages purgeable", 0)
        wired = vals.get("Pages wired down", 0)
        comp = vals.get("Pages occupied by compressor", 0)
        free = vals.get("Pages free", 0)
        app = max(0, anon - purg)
        swap_used = None
        smo = re.search(r"used = ([\d.]+)([MG])", _run(["sysctl", "-n", "vm.swapusage"]))
        if smo:
            v = float(smo.group(1))
            swap_used = int(v * (1024 ** 2 if smo.group(2) == "M" else 1024 ** 3))
        with _lock:
            _state["mem"] = {
                "total": total or None,
                "used": (app + wired + comp) * ps,  # Activity Monitor 口径
                "app": app * ps,
                "wired": wired * ps,
                "compressed": comp * ps,
                "cached": (fileb + purg) * ps,
                "free": free * ps,
                "swap_used": swap_used,
            }
        time.sleep(2.0)


def _disk_loop():
    while True:
        lines = _run(["df", "-k", "/System/Volumes/Data"]).strip().splitlines()
        disk = {}
        if len(lines) >= 2:
            parts = lines[1].split()
            if len(parts) >= 3:
                try:
                    total = int(parts[1]) * 1024
                    used = int(parts[2]) * 1024
                    disk = {
                        "mount": "/System/Volumes/Data",
                        "total": total,
                        "used": used,
                        "free": max(0, total - used),
                        "used_pct": round(used / total * 100, 1) if total else None,
                    }
                except ValueError:
                    disk = {}
        with _lock:
            _state["disk"] = disk
        time.sleep(30.0)


def _net_read():
    res = {}
    for line in _run(["netstat", "-ib"]).splitlines():
        toks = line.split()
        if len(toks) >= 10 and toks[0] in NET_IFACES and toks[2].startswith("<Link#"):
            try:
                res[toks[0]] = (int(toks[6]), int(toks[9]))
            except ValueError:
                pass
    return res


def _net_loop():
    prev = _net_read()
    prev_t = time.monotonic()
    while True:
        time.sleep(1.0)
        cur = _net_read()
        cur_t = time.monotonic()
        dt = max(0.001, cur_t - prev_t)
        ifaces = {}
        rx_total = 0
        tx_total = 0
        for name, (rx, tx) in cur.items():
            if name in prev:
                prx, ptx = prev[name]
                rx_bps = max(0, int((rx - prx) / dt))
                tx_bps = max(0, int((tx - ptx) / dt))
                ifaces[name] = {"rx_bps": rx_bps, "tx_bps": tx_bps}
                if name.startswith("en"):
                    rx_total += rx_bps
                    tx_total += tx_bps
        if cur:
            with _lock:
                _state["net"] = {"rx_bps": rx_total, "tx_bps": tx_total, "interfaces": ifaces}
            prev, prev_t = cur, cur_t


def _lat_loop():
    while True:
        lat = {}
        for host, port in LAT_TARGETS:
            t0 = time.perf_counter()
            try:
                with socket.create_connection((host, port), timeout=3):
                    lat["%s:%d" % (host, port)] = round((time.perf_counter() - t0) * 1000, 1)
            except Exception:
                lat["%s:%d" % (host, port)] = None
        with _lock:
            _state["latency"] = lat
        time.sleep(5.0)


def _load_loop():
    while True:
        m = re.search(r"\{\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*\}", _run(["sysctl", "-n", "vm.loadavg"]))
        if m:
            with _lock:
                _state["load"] = [float(m.group(1)), float(m.group(2)), float(m.group(3))]
        time.sleep(5.0)


def _tailnet_loop():
    while True:
        net = {}
        for ip, name in TS_PEERS.items():
            out = _run([TS_CLI, "ping", "--timeout=3s", "-c", "1", ip], timeout=8)
            entry = {"name": name, "path": None, "via": None, "ms": None}
            m = re.search(r"via DERP\(([a-z0-9]+)\) in ([\d.]+)ms", out)
            if m:
                entry["path"] = "relay"
                entry["via"] = m.group(1)
                entry["ms"] = float(m.group(2))
            else:
                m = re.search(r"via ([\d.]+:\d+) in ([\d.]+)ms", out)
                if m:
                    entry["path"] = "direct"
                    entry["ms"] = float(m.group(2))
            net[ip] = entry
        with _lock:
            _state["tailnet"] = net
        time.sleep(20.0)


def _init_static():
    m = re.search(r"sec = (\d+)", _run(["sysctl", "-n", "kern.boottime"]))
    with _lock:
        _state["boot_time"] = int(m.group(1)) if m else None
        _state["model"] = _run(["sysctl", "-n", "hw.model"]).strip() or None
        _state["host"] = socket.gethostname()


class Handler(BaseHTTPRequestHandler):
    server_version = "MacMetrics/" + VERSION

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self._send(204, b"")

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/metrics", "/"):
            with _lock:
                snap = json.loads(json.dumps(_state))
            snap["ts"] = time.time()
            snap["uptime_s"] = int(time.time() - snap["boot_time"]) if snap.get("boot_time") else None
            snap["ncpu"] = NCPU
            snap["agent_version"] = VERSION
            self._send(200, json.dumps(snap, ensure_ascii=False))
        elif path == "/healthz":
            self._send(200, "ok", "text/plain; charset=utf-8")
        else:
            self._send(404, '{"error":"not found"}')

    def log_message(self, fmt, *args):
        pass


def main():
    _init_static()
    for fn in (_cpu_loop, _gpu_loop, _mem_loop, _disk_loop, _net_loop, _lat_loop, _load_loop, _tailnet_loop):
        threading.Thread(target=fn, daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    main()
