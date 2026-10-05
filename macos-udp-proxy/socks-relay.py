import socket
import struct
import threading
import select

BIND_IP = '100.105.59.31'
BIND_PORT = 7891
BUF = 65536
CONNECT_TIMEOUT = 12


def log(msg):
    print(msg, flush=True)


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


def pipe(src, dst):
    try:
        while True:
            data = src.recv(BUF)
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


def handle_connect(client, host, port):
    try:
        target = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT)
    except OSError as e:
        log('connect failed: %s:%s %s' % (host, port, e))
        client.sendall(build_reply(0x05, None))
        return
    client.sendall(build_reply(0x00, target.getsockname()))
    t1 = threading.Thread(target=pipe, args=(client, target), daemon=True)
    t2 = threading.Thread(target=pipe, args=(target, client), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    try:
        target.close()
    except OSError:
        pass


def handle_udp(client, peer_ip):
    try:
        client_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client_sock.bind((BIND_IP, 0))
        target_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        target_sock.bind(('0.0.0.0', 0))
    except OSError as e:
        log('udp bind failed: %s' % e)
        client.sendall(build_reply(0x01, None))
        return
    client.sendall(build_reply(0x00, client_sock.getsockname()))
    stop = threading.Event()

    def udp_loop():
        client_addr = None
        while not stop.is_set():
            try:
                ready, _, _ = select.select([client_sock, target_sock], [], [], 0.5)
            except OSError:
                break
            for sk in ready:
                try:
                    data, src = sk.recvfrom(BUF)
                except OSError:
                    continue
                if sk is client_sock:
                    if src[0] != peer_ip:
                        continue
                    client_addr = src
                    if len(data) < 5 or data[2] != 0:
                        continue
                    atyp = data[3]
                    idx = 4
                    if atyp == 1:
                        if len(data) < 10:
                            continue
                        host = socket.inet_ntoa(data[4:8])
                        idx = 8
                    elif atyp == 4:
                        if len(data) < 22:
                            continue
                        host = socket.inet_ntop(socket.AF_INET6, data[4:20])
                        idx = 20
                    elif atyp == 3:
                        if len(data) < 5:
                            continue
                        ln = data[4]
                        if len(data) < 5 + ln + 2:
                            continue
                        host = data[5:5 + ln].decode('utf-8', 'ignore')
                        idx = 5 + ln
                    else:
                        continue
                    port = struct.unpack('!H', data[idx:idx + 2])[0]
                    payload = data[idx + 2:]
                    if not payload:
                        continue
                    try:
                        if atyp == 3:
                            infos = socket.getaddrinfo(host, port, type=socket.SOCK_DGRAM)
                            dest = infos[0][4]
                        else:
                            dest = (host, port)
                        target_sock.sendto(payload, dest)
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
    try:
        while client.recv(BUF):
            pass
    except OSError:
        pass
    stop.set()
    try:
        client_sock.close()
    except OSError:
        pass
    try:
        target_sock.close()
    except OSError:
        pass


def handle_client(conn, addr):
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
            handle_udp(conn, addr[0])
        else:
            conn.sendall(build_reply(0x07, None))
    except OSError:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((BIND_IP, BIND_PORT))
    srv.listen(128)
    log('socks relay listening on %s:%d' % (BIND_IP, BIND_PORT))
    while True:
        try:
            conn, addr = srv.accept()
        except OSError:
            continue
        threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


if __name__ == '__main__':
    main()
