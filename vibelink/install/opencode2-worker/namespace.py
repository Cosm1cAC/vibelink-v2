#!/usr/bin/python3
"""Runs only inside bwrap. No real model grant or host credential enters here."""
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time

class UnixConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect('/run/model.sock')

class Loopback(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    def log_message(self, *args):
        pass
    def do_GET(self):
        self.forward()
    def do_POST(self):
        self.forward()
    def forward(self):
        self.connection.settimeout(30)
        connection = UnixConnection('model', timeout=125)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > 8 * 1024 * 1024 or self.headers.get('Transfer-Encoding'):
                self.send_error(413, 'invalid_body')
                return
            connection.request(self.command, self.path, body=self.rfile.read(length) if length else None,
                               headers={'Content-Type': 'application/json'})
            response = connection.getresponse()
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Connection', 'close')
            self.end_headers()
            while block := response.read1(8192):
                self.wfile.write(block)
                self.wfile.flush()
        except (OSError, ValueError, http.client.HTTPException):
            pass
        finally:
            connection.close()

def probe():
    paths = ['/mnt/c/Users/22771/.codex/auth.json', '/home/shaotian', '/var/lib/vibelink-worker',
             '/root', '/proc/1/root/mnt/c', '/run/WSL', '/opt/vibelink-worker']
    writes = False
    try:
        file = Path('/workspace/worker-write-probe.txt')
        file.write_text('worker sandbox probe\n')
        writes = True
    except OSError:
        pass
    inputs_write = False
    try:
        Path('/inputs/forbidden-write.txt').write_text('must fail')
        inputs_write = True
    except OSError:
        pass
    direct = False
    try:
        socket.create_connection(('1.1.1.1', 443), timeout=0.5).close()
        direct = True
    except OSError:
        pass
    gateway = None
    connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=5)
    try:
        connection.request('GET', '/v1/models')
        response = connection.getresponse()
        gateway = response.status
        response.read()
    except OSError:
        pass
    finally:
        connection.close()
    version = subprocess.run(['/worker/opencode', '--version'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10).stdout.decode(errors='replace').strip()[:80]
    print(json.dumps({'type': 'worker_probe', 'cliVersion': version, 'uid': os.getuid(), 'uidMap': Path('/proc/self/uid_map').read_text().strip(),
                      'cwd': os.getcwd(), 'workspaceWrite': writes, 'workspaceFiles': len(list(Path('/workspace').iterdir())),
                      'inputContents': {file.name: file.read_text() for file in Path('/inputs').iterdir()}, 'inputsWrite': inputs_write,
                      'hostPathsVisible': {path: Path(path).exists() for path in paths},
                      'directInternet': direct, 'modelGatewayStatus': gateway,
                      'tokenIsPlaceholder': os.environ.get('VIBELINK_MODEL_GATEWAY_TOKEN') == 'worker-model-only',
                      'capabilities': next(line.split(':')[1].strip() for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('CapEff:'))}), flush=True)
    return 0

def main():
    request = json.load(sys.stdin)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 11434), Loopback)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        if request['operation'] == 'probe':
            if request['prompt'] == '__cancel_test__':
                time.sleep(30)
            return probe()
        args = ['/worker/opencode', 'run', '--standalone', '--format', 'json', '--model', 'vibelink/' + request['model']]
        if request['session']:
            args.extend(['--session', request['session']])
        args.extend(['--', request['prompt']])
        return subprocess.call(args, stdin=subprocess.DEVNULL)
    finally:
        server.shutdown()
        server.server_close()

if __name__ == '__main__':
    sys.exit(main())
