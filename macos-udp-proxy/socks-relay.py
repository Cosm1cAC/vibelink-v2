import os
import socket
import struct
import threading
import select
import time

# 监听配置（可被环境变量覆盖；launchd 直接跑时用默认值）：
# BIND_IP    IPv4 监听地址，默认 '0.0.0.0'（全部 IPv4 接口）
# BIND_IPV6  IPv6 监听地址，'' 为关闭（默认）；'::' 为全部 IPv6 接口；也可填具体地址
#            （如 Mac 的稳定公网 v6，或 '::1' 仅本机）。注意：该端口是匿名 SOCKS，
#            暴露到公网前必须先加认证/限源，否则等于白送日本出口。
# 域名类的 UDP 目标默认只解析 IPv4；开启 BIND_IPV6 后才会在 v4 失败时尝试 v6。
BIND_IP = os.environ.get('SOCKS_BIND_IP', '0.0.0.0')
BIND_IPV6 = os.environ.get('SOCKS_BIND_IPV6', '')
BIND_PORT = int(os.environ.get('SOCKS_BIND_PORT', '7891'))
BUF = 65536
CONNECT_TIMEOUT = 12
PIPE_IDLE_TIMEOUT = 300      # 单方向静默上限（秒），超时后让另一方向感知
CONN_MAX_LIFETIME = 3600     # 单条隧道最长存活（秒），兜底防泄漏
MAX_UDP_SESSIONS = 64
UDP_IDLE_TIMEOUT = 300
# [patch-tcp-limit] TCP 并发上限：防止连接洪水把线程/FD 撑爆导致整机假死
MAX_TCP_CONNS = int(os.environ.get('SOCKS_MAX_TCP', '256'))

_tcp_state = {'count': 0, 'lock': threading.Lock()}


def tcp_acquire():
    with _tcp_state['lock']:
        if _tcp_state['count'] >= MAX_TCP_CONNS:
            return False
        _tcp_state['count'] += 1
        return True


def tcp_release():
    with _tcp_state['lock']:
        if _tcp_state['count'] > 0:
            _tcp_state['count'] -= 1

_udp_state = {'count': 0, 'lock': threading.Lock()}


def log(msg):
    print(msg, flush=True)


def udp_acquire():
    with _udp_state['lock']:
        if _udp_state['count'] >= MAX_UDP_SESSIONS:
            return False
        _udp_state['count'] += 1
        return True


def udp_release():
    with _udp_state['lock']:
        if _udp_state['count'] > 0:
            _udp_state['count'] -= 1


def recv_exact(conn, n):
    data = b''
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def read_target(conn):
    at = recv_exact(conn, 1)
    if at is None:
        return None
    atype = at[0]
    if atype == 1:
        raw = recv_exact(conn, 4)
        if raw is None:
            return None
        host = socket.inet_ntoa(raw)
    elif atype == 4:
        raw = recv_exact(conn, 16)
        if raw is None:
            return None
        host = socket.inet_ntop(socket.AF_INET6, raw)
    elif atype == 3:
        ln = recv_exact(conn, 1)
        if ln is None:
            return None
        raw = recv_exact(conn, ln[0])
        if raw is None:
            return None
        host = raw.decode('utf-8', 'ignore')
    else:
        return None
    pr = recv_exact(conn, 2)
    if pr is None:
        return None
    return host, struct.unpack('!H', pr)[0]


def build_reply(code, addr):
    if isinstance(addr, tuple):
        ip, port = addr[0], addr[1]
    else:
        ip, port = '0.0.0.0', 0
    try:
        packed = socket.inet_pton(socket.AF_INET, ip)
        atyp = 1
    except OSError:
        try:
            packed = socket.inet_pton(socket.AF_INET6, ip)
            atyp = 4
        except OSError:
            packed = b'\x00\x00\x00\x00'
            atyp = 1
    return b'\x05' + bytes([code]) + b'\x00' + bytes([atyp]) + packed + struct.pack('!H', port)


def pipe(src, dst, stop=None):
    # [fix-closewait-20261025] recv 加超时 + 可中断，避免一端 EOF 后另一端永久阻塞
    try:
        src.settimeout(PIPE_IDLE_TIMEOUT)
    except OSError:
        pass
    try:
        while True:
            if stop is not None and stop.is_set():
                break
            try:
                data = src.recv(BUF)
            except socket.timeout:
                if stop is not None and stop.is_set():
                    break
                continue
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass
        if stop is not None:
            stop.set()


def handle_connect(client, host, port):
    try:
        target = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT)
    except OSError as e:
        log('connect failed: %s:%s %s' % (host, port, e))
        client.sendall(build_reply(0x05, None))
        return
    try:
        target.settimeout(None)
    except OSError:
        pass
    client.sendall(build_reply(0x00, target.getsockname()))
    # [fix-closewait-20261025] 用 stop 事件联动两个方向，并强制收尾，防 CLOSE_WAIT 泄漏
    stop = threading.Event()
    t1 = threading.Thread(target=pipe, args=(client, target, stop), daemon=True)
    t2 = threading.Thread(target=pipe, args=(target, client, stop), daemon=True)
    t1.start()
    t2.start()
    deadline = time.time() + CONN_MAX_LIFETIME
    while (t1.is_alive() or t2.is_alive()) and time.time() < deadline:
        t1.join(timeout=0.5)
        t2.join(timeout=0.5)
    stop.set()
    for sk in (client, target):
        try:
            sk.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sk.close()
        except OSError:
            pass


def handle_udp(client, peer_ip, local_ip):
    if not udp_acquire():
        log('udp session limit reached (%d), rejecting %s' % (MAX_UDP_SESSIONS, peer_ip))
        client.sendall(build_reply(0x01, None))
        return
    client_sock = None
    targets = {}
    try:
        client_sock = socket.socket(client.family, socket.SOCK_DGRAM)
        client_sock.bind((local_ip, 0))
    except OSError as e:
        log('udp bind failed: %s' % e)
        client.sendall(build_reply(0x01, None))
        udp_release()
        return
    client.sendall(build_reply(0x00, client_sock.getsockname()))
    log('udp associate from %s relay=%s:%d' % (peer_ip, local_ip, client_sock.getsockname()[1]))
    stop = threading.Event()

    def get_target(family):
        sk = targets.get(family)
        if sk is None:
            sk = socket.socket(family, socket.SOCK_DGRAM)
            sk.bind(('0.0.0.0', 0) if family == socket.AF_INET else ('::', 0))
            targets[family] = sk
        return sk

    def udp_loop():
        client_addr = None
        last_active = time.time()
        while not stop.is_set():
            fds = [client_sock] + list(targets.values())
            try:
                ready, _, _ = select.select(fds, [], [], 1.0)
            except OSError:
                break
            if not ready:
                if time.time() - last_active > UDP_IDLE_TIMEOUT:
                    log('udp session idle %ds, closing %s' % (UDP_IDLE_TIMEOUT, peer_ip))
                    break
                continue
            for sk in ready:
                try:
                    data, src = sk.recvfrom(BUF)
                except OSError:
                    continue
                last_active = time.time()
                if sk is client_sock:
                    if src[0] != peer_ip:
                        continue
                    client_addr = src
                    if len(data) < 5 or data[2] != 0:
                        continue
                    atyp = data[3]
                    idx = 4
                    dest = None
                    family = None
                    if atyp == 1:
                        if len(data) < 10:
                            continue
                        dest = (socket.inet_ntoa(data[4:8]), None)
                        idx = 8
                        family = socket.AF_INET
                    elif atyp == 4:
                        if len(data) < 22:
                            continue
                        dest = (socket.inet_ntop(socket.AF_INET6, data[4:20]), None)
                        idx = 20
                        family = socket.AF_INET6
                    elif atyp == 3:
                        if len(data) < 5:
                            continue
                        ln = data[4]
                        if len(data) < 5 + ln + 2:
                            continue
                        host = data[5:5 + ln].decode('utf-8', 'ignore')
                        idx = 5 + ln
                        port = struct.unpack('!H', data[idx:idx + 2])[0]
                        payload = data[idx + 2:]
                        if not payload:
                            continue
                        fams = [socket.AF_INET]
                        if BIND_IPV6:
                            fams.append(socket.AF_INET6)
                        for f in list(targets.keys()):
                            if f in fams:
                                fams.remove(f)
                                fams.insert(0, f)
                        for f in fams:
                            try:
                                infos = socket.getaddrinfo(host, port, f, socket.SOCK_DGRAM)
                            except OSError:
                                continue
                            if not infos:
                                continue
                            try:
                                get_target(f).sendto(payload, (infos[0][4][0], port))
                                break
                            except OSError:
                                continue
                        continue
                    else:
                        continue
                    port = struct.unpack('!H', data[idx:idx + 2])[0]
                    payload = data[idx + 2:]
                    if not payload:
                        continue
                    try:
                        get_target(family).sendto(payload, (dest[0], port))
                    except OSError:
                        continue
                else:
                    if client_addr is None:
                        continue
                    try:
                        tag = socket.inet_pton(socket.AF_INET, src[0])
                        hdr = b'\x00\x00\x00\x01' + tag + struct.pack('!H', src[1])
                    except OSError:
                        try:
                            tag6 = socket.inet_pton(socket.AF_INET6, src[0])
                            hdr = b'\x00\x00\x00\x04' + tag6 + struct.pack('!H', src[1])
                        except OSError:
                            continue
                    try:
                        client_sock.sendto(hdr + data, client_addr)
                    except OSError:
                        continue

    worker = threading.Thread(target=udp_loop, daemon=True)
    worker.start()
    client.settimeout(None)
    try:
        while True:
            chunk = client.recv(BUF)
            if not chunk:
                break
    except OSError:
        pass
    stop.set()
    for sk in [client_sock] + list(targets.values()):
        try:
            sk.close()
        except OSError:
            pass
    udp_release()
    log('udp session closed for %s' % peer_ip)


def handle_client(conn, addr):
    # [patch-tcp-limit] 确保任何退出路径都归还配额
    try:
        _handle_client_inner(conn, addr)
    finally:
        tcp_release()


def _handle_client_inner(conn, addr):
    try:
        greeting = recv_exact(conn, 2)
        if greeting is None or greeting[0] != 5:
            return
        methods = recv_exact(conn, greeting[1])
        if methods is None:
            return
        if 0 in methods:
            conn.sendall(b'\x05\x00')
        else:
            conn.sendall(b'\xff')
            return
        head = recv_exact(conn, 3)
        if head is None or head[0] != 5:
            return
        cmd = head[1]
        target = read_target(conn)
        if target is None:
            return
        host, port = target
        if cmd == 1:
            handle_connect(conn, host, port)
        elif cmd == 3:
            try:
                local_ip = conn.getsockname()[0]
            except OSError:
                local_ip = BIND_IP
            handle_udp(conn, addr[0], local_ip)
        else:
            conn.sendall(build_reply(0x07, None))
    except OSError:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def make_listener(family, addr):
    srv = socket.socket(family, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if family == socket.AF_INET6:
        srv.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
    srv.bind((addr, BIND_PORT))
    srv.listen(128)
    return srv


def main():
    listeners = []
    try:
        listeners.append(make_listener(socket.AF_INET, BIND_IP))
        log('socks relay listening on %s:%d (max_udp=%d)' % (BIND_IP, BIND_PORT, MAX_UDP_SESSIONS))
    except OSError as e:
        log('v4 listen failed on %s:%d %s' % (BIND_IP, BIND_PORT, e))
    if BIND_IPV6:
        try:
            listeners.append(make_listener(socket.AF_INET6, BIND_IPV6))
            log('socks relay listening on [%s]:%d (max_udp=%d)' % (BIND_IPV6, BIND_PORT, MAX_UDP_SESSIONS))
        except OSError as e:
            log('v6 listen failed on [%s]:%d %s' % (BIND_IPV6, BIND_PORT, e))
    if not listeners:
        raise SystemExit('no listener could start')
    while True:
        try:
            ready, _, _ = select.select(listeners, [], [], 0.5)
        except OSError:
            continue
        for srv in ready:
            try:
                conn, addr = srv.accept()
            except OSError:
                continue
            # [patch-tcp-limit] 超限直接拒绝，避免线程无限增长
            if not tcp_acquire():
                try:
                    conn.close()
                except OSError:
                    pass
                log('tcp conn limit reached (%d), rejecting %s' % (MAX_TCP_CONNS, addr[0]))
                continue
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


if __name__ == '__main__':
    main()
