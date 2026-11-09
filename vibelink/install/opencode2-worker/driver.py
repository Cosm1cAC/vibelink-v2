#!/usr/bin/python3
"""Root-owned WSL driver. The untrusted CLI never runs in this host namespace."""
import contextlib
import base64
import binascii
import fcntl
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import pwd
import re
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import uuid

ROOT = Path('/opt/vibelink-worker')
STATE = Path('/var/lib/vibelink-worker')
RUN = Path('/run/vibelink-worker')
MODEL = 'auto/deepseek-v4.1-flash'
MAX_INPUT = 8 * 1024 * 1024
MAX_REQUEST = 20 * 1024 * 1024
MAX_ATTACHMENTS_BYTES = 12 * 1024 * 1024

class InvalidRequest(Exception):
    pass

def validate(raw):
    if not isinstance(raw, dict) or type(raw.get('version')) is not int or raw['version'] != 1:
        raise InvalidRequest('invalid_version')
    scope = raw.get('scope')
    if not isinstance(scope, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', scope):
        raise InvalidRequest('invalid_scope')
    operation = raw.get('operation', 'run')
    if operation not in ('run', 'probe', 'cancel', 'status'):
        raise InvalidRequest('invalid_operation')
    job = raw.get('jobId', str(uuid.uuid4()))
    if not isinstance(job, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', job):
        raise InvalidRequest('invalid_job')
    if operation in ('cancel', 'status'):
        return { 'operation': operation, 'scope': scope, 'jobId': job }
    if raw.get('model') != MODEL:
        raise InvalidRequest('unsupported_model')
    gateway = raw.get('gateway')
    if gateway not in ('http://127.0.0.1:18995/api/llm/v1', 'http://127.0.0.1:18998/api/llm/v1'):
        raise InvalidRequest('invalid_gateway')
    token = raw.get('token')
    if not isinstance(token, str) or not 1 <= len(token) <= 4096 or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise InvalidRequest('invalid_grant')
    sid = raw.get('session')
    if sid is not None and (not isinstance(sid, str) or not re.fullmatch(r'ses_[A-Za-z0-9_-]{1,200}', sid)):
        raise InvalidRequest('invalid_session')
    prompt = raw.get('prompt', '')
    if not isinstance(prompt, str) or len(prompt.encode()) > 1024 * 1024:
        raise InvalidRequest('invalid_prompt')
    if type(raw.get('readOnly')) is not bool:
        raise InvalidRequest('invalid_read_only')
    files = raw.get('files', [])
    if not isinstance(files, list) or len(files) > 8:
        raise InvalidRequest('invalid_attachments')
    decoded, names, size = [], set(), 0
    for file in files:
        if not isinstance(file, dict) or set(file) != {'filename', 'mime', 'base64'}:
            raise InvalidRequest('invalid_attachment')
        name, mime, data = file['filename'], file['mime'], file['base64']
        if (not isinstance(name, str) or not name or len(name.encode()) > 180
                or name.startswith('.') or name[-1] in '. '
                or any(ord(c) < 32 or c in '/\\:"<>|?*' for c in name)
                or name.casefold() in names):
            raise InvalidRequest('invalid_attachment_name')
        if not isinstance(mime, str) or not re.fullmatch(r'[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+', mime):
            raise InvalidRequest('invalid_attachment_mime')
        if not isinstance(data, str) or len(data) > (MAX_ATTACHMENTS_BYTES + 2) // 3 * 4:
            raise InvalidRequest('attachment_too_large')
        try:
            content = base64.b64decode(data, validate=True)
        except (ValueError, binascii.Error):
            raise InvalidRequest('invalid_attachment_base64') from None
        size += len(content)
        if size > MAX_ATTACHMENTS_BYTES:
            raise InvalidRequest('attachments_too_large')
        names.add(name.casefold())
        decoded.append({'filename': name, 'mime': mime, 'content': content})
    return { 'version': 1, 'operation': operation, 'scope': scope, 'jobId': job,
             'gateway': gateway, 'token': token, 'session': sid, 'prompt': prompt,
             'model': MODEL, 'readOnly': raw['readOnly'], 'files': decoded }

def nat_gateway():
    for line in Path('/proc/net/route').read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) > 3 and fields[1] == '00000000' and int(fields[3], 16) & 2:
            return socket.inet_ntoa(struct.pack('<I', int(fields[2], 16)))
    raise InvalidRequest('windows_gateway_unavailable')

def private_uid(scope):
    STATE.mkdir(mode=0o711, parents=True, exist_ok=True)
    STATE.chmod(0o711)
    with open(STATE / 'uids.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        file = STATE / 'uids.json'
        values = json.loads(file.read_text()) if file.exists() else {}
        key = hashlib.sha256(scope.encode()).hexdigest()
        if key not in values:
            used = set(values.values()) | {row.pw_uid for row in pwd.getpwall()}
            candidate = max([199999, *values.values()]) + 1
            while candidate in used:
                candidate += 1
            values[key] = candidate
            temp = file.with_suffix('.tmp')
            temp.write_text(json.dumps(values))
            temp.chmod(0o600)
            os.replace(temp, file)
        return key, values[key]

def scope_paths(key, uid):
    parent = STATE / 'scopes'
    parent.mkdir(mode=0o711, exist_ok=True)
    parent.chmod(0o711)
    base = parent / key
    base.mkdir(mode=0o700, exist_ok=True)
    os.chown(base, uid, uid)
    for name in ('home', 'workspace'):
        path = base / name
        if path.is_symlink():
            raise InvalidRequest('invalid_scope_storage')
        path.mkdir(mode=0o700, exist_ok=True)
        os.chown(path, uid, uid)
    return base / 'home', base / 'workspace'

class UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(*args, **kwargs)
    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise
    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

class Gateway(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    def setup(self):
        super().setup()
        self.connection.settimeout(30)
    def log_message(self, *args):
        pass
    def do_GET(self):
        self.forward()
    def do_POST(self):
        self.forward()
    def forward(self):
        allowed = {('GET', '/v1/models'), ('POST', '/v1/chat/completions'), ('POST', '/v1/responses')}
        if (self.command, self.path) not in allowed:
            self.send_error(403, 'model_route_only')
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > MAX_INPUT or self.headers.get('Transfer-Encoding'):
                self.send_error(413, 'invalid_body')
                return
            body = self.rfile.read(length) if length else None
            if self.command == 'POST':
                parsed = json.loads(body)
                if not isinstance(parsed, dict) or parsed.get('model') != MODEL:
                    self.send_error(403, 'model_route_only')
                    return
            connection = http.client.HTTPConnection(self.server.upstream, self.server.port, timeout=120)
            # Never forward caller headers, credentials, destinations or redirect targets.
            connection.request(self.command, '/api/llm' + self.path, body=body, headers={
                'Authorization': 'Bearer ' + self.server.grant,
                'Content-Type': 'application/json', 'Connection': 'close',
            })
            response = connection.getresponse()
            if 300 <= response.status < 400:
                self.send_error(502, 'upstream_redirect_refused')
                return
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Connection', 'close')
            self.end_headers()
            while True:
                block = response.read1(8192)
                if not block:
                    break
                self.wfile.write(block)
                self.wfile.flush()
        except (OSError, ValueError, http.client.HTTPException):
            with contextlib.suppress(OSError):
                self.send_error(502, 'model_gateway_unavailable')
        finally:
            if 'connection' in locals():
                connection.close()

def command(home, workspace, socket_path, config_file, identity_dir, readonly, inputs=None):
    args = ['/usr/bin/bwrap', '--die-with-parent', '--new-session', '--unshare-all',
            '--ro-bind', '/usr', '/usr', '--symlink', 'usr/bin', '/bin',
            '--symlink', 'usr/sbin', '/sbin', '--symlink', 'usr/lib', '/lib',
            '--symlink', 'usr/lib64', '/lib64', '--proc', '/proc', '--dev', '/dev',
            '--tmpfs', '/tmp', '--dir', '/run', '--dir', '/home', '--dir', '/worker',
            '--ro-bind', str(identity_dir), '/etc',
            '--ro-bind', str(ROOT / 'bin/opencode'), '/worker/opencode',
            '--ro-bind', str(ROOT / 'namespace.py'), '/worker/namespace.py',
            '--ro-bind', str(config_file), '/etc/opencode/opencode.json',
            '--bind', str(home), '/home/worker',
            '--ro-bind' if readonly else '--bind', str(workspace), '/workspace',
            '--ro-bind', str(socket_path), '/run/model.sock',
            '--uid', '1000', '--gid', '1000', '--cap-drop', 'ALL', '--clearenv',
            '--setenv', 'PATH', '/usr/bin:/bin', '--setenv', 'HOME', '/home/worker',
            '--setenv', 'USER', 'worker', '--setenv', 'LOGNAME', 'worker',
            '--setenv', 'XDG_CONFIG_HOME', '/home/worker/.config',
            '--setenv', 'XDG_DATA_HOME', '/home/worker/.local/share',
            '--setenv', 'XDG_STATE_HOME', '/home/worker/.local/state',
            '--setenv', 'XDG_CACHE_HOME', '/home/worker/.cache',
            '--setenv', 'VIBELINK_MODEL_GATEWAY_TOKEN', 'worker-model-only',
            '--setenv', 'OPENCODE_DISABLE_AUTOUPDATE', '1',
            '--setenv', 'OPENCODE_CONFIG_PROJECT_DISABLE', 'true',
            '--setenv', 'OPENCODE_DISABLE_PROJECT_CONFIG', 'true',
            '--setenv', 'OPENCODE_DISABLE_CLAUDE_CODE', 'true',
            '--chdir', '/workspace', '/usr/bin/python3', '/worker/namespace.py']
    if inputs is not None:
        args[1:1] = ['--ro-bind', str(inputs), '/inputs']
    return args

def process_start(pid):
    return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]

def cancel(request):
    key = hashlib.sha256(request['scope'].encode()).hexdigest()
    file = RUN / 'jobs' / (request['jobId'] + '.json')
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        job = json.loads(file.read_text())
        if job['scope'] != key:
            raise InvalidRequest('job_identity_mismatch')
    except FileNotFoundError:
        pass
    write_job(file.with_suffix('.cancel'), {'scope': key})
    # Re-read after the marker: it closes cancellation's race with spawn.
    try:
        job = json.loads(file.read_text())
        if job['scope'] != key:
            raise InvalidRequest('job_identity_mismatch')
        if job.get('state') == 'running' and process_start(job['pid']) == job['start']:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(job['pid'], signal.SIGTERM)
    except FileNotFoundError:
        pass
    print(json.dumps({'type': 'vibelink_worker_cancel', 'jobId': request['jobId']}), flush=True)

def status(request):
    file = RUN / 'jobs' / (request['jobId'] + '.json')
    key = hashlib.sha256(request['scope'].encode()).hexdigest()
    result = {'known': False, 'running': False, 'quiescent': False}
    try:
        job = json.loads(file.read_text())
        if job['scope'] != key:
            raise InvalidRequest('job_identity_mismatch')
        result['known'] = True
        if job.get('state') == 'finished':
            result['quiescent'] = True
        else:
            with contextlib.suppress(FileNotFoundError):
                result['running'] = process_start(job['pid']) == job['start']
            # A missing live PID is not a durable completion receipt.
    except (FileNotFoundError, ValueError, KeyError):
        pass
    print(json.dumps(result), flush=True)

def write_job(file, record):
    # Run receipts and cancellation markers for one job are concurrent writers.
    # A shared <job>.tmp can lose or exchange their different target contents.
    descriptor, name = tempfile.mkstemp(prefix=file.name + '.', suffix='.tmp', dir=file.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump(record, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, file)
    finally:
        with contextlib.suppress(FileNotFoundError):
            temporary.unlink()

def execute(request):
    key, uid = private_uid(request['scope'])
    home, workspace = scope_paths(key, uid)
    RUN.mkdir(mode=0o711, parents=True, exist_ok=True)
    RUN.chmod(0o711)
    jobs = RUN / 'jobs'
    jobs.mkdir(mode=0o700, exist_ok=True)
    job_file = jobs / (request['jobId'] + '.json')
    marker = job_file.with_suffix('.cancel')
    try:
        with job_file.open('x') as file:
            json.dump({'scope': key, 'state': 'starting'}, file)
            file.flush()
            os.fsync(file.fileno())
    except FileExistsError:
        raise InvalidRequest('job_already_registered')
    def cancelled():
        if not marker.exists():
            return False
        if json.loads(marker.read_text()).get('scope') != key:
            raise InvalidRequest('job_identity_mismatch')
        return True
    if cancelled():
        write_job(job_file, {'scope': key, 'state': 'finished', 'exitCode': 130})
        return 130
    with tempfile.TemporaryDirectory(prefix='job_', dir=RUN) as temporary:
        directory = Path(temporary)
        os.chown(directory, uid, uid)
        inputs = directory / 'inputs'
        inputs.mkdir(mode=0o755)
        inputs.chmod(0o755)
        for file in request['files']:
            target = inputs / file['filename']
            with target.open('xb') as stream:
                stream.write(file['content'])
            target.chmod(0o444)
        identity = directory / 'etc'
        identity.mkdir(mode=0o755)
        identity.chmod(0o755)
        (identity / 'opencode').mkdir(mode=0o755)
        (identity / 'opencode').chmod(0o755)
        (identity / 'opencode/opencode.json').touch()
        (identity / 'opencode/opencode.json').chmod(0o644)
        (identity / 'passwd').write_text('worker:x:1000:1000:Worker:/home/worker:/bin/bash\n')
        (identity / 'group').write_text('worker:x:1000:\n')
        (identity / 'hosts').write_text('127.0.0.1 localhost\n::1 localhost\n')
        (identity / 'nsswitch.conf').write_text('passwd: files\ngroup: files\nhosts: files\n')
        config = directory / 'provider.json'
        permissions = [{'action': '*', 'resource': '*', 'effect': 'allow'}]
        if request['readOnly']:
            permissions.extend({'action': action, 'resource': '*', 'effect': 'deny'}
                               for action in ('edit', 'shell', 'subagent', 'execute'))
        config.write_text(json.dumps({
            'model': 'vibelink/' + MODEL, 'small_model': 'vibelink/' + MODEL,
            'permissions': permissions,
            'policies': [{'action': 'provider.use', 'resource': '*', 'effect': 'deny'},
                         {'action': 'provider.use', 'resource': 'vibelink', 'effect': 'allow'}],
            'providers': {'vibelink': {'name': 'VibeLink', 'env': ['VIBELINK_MODEL_GATEWAY_TOKEN'],
                'package': '@opencode/ai/providers/openai-compatible',
                'settings': {'baseURL': 'http://127.0.0.1:11434/v1'},
                'models': {MODEL: {'name': 'DeepSeek'}}}},
        }))
        config.chmod(0o644)
        for file in identity.iterdir():
            if file.is_file():
                file.chmod(0o644)
        unix = directory / 'model.sock'
        server = UnixServer(str(unix), Gateway)
        server.upstream = nat_gateway()
        server.port = urllib.parse.urlsplit(request['gateway']).port
        server.grant = request['token']
        unix.chmod(0o600)
        os.chown(unix, uid, uid)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        def drop():
            os.setgroups([])
            os.setgid(uid)
            os.setuid(uid)
        args = command(home, workspace, unix, config, identity, request['readOnly'], inputs)
        child_input = {k: request[k] for k in ('operation', 'session', 'prompt', 'model')}
        if request['files']:
            child_input['prompt'] += '\n\nAttached inputs (read-only):\n' + '\n'.join(
                json.dumps('/inputs/' + file['filename'], ensure_ascii=False) for file in request['files'])
        try:
            with subprocess.Popen(args, stdin=subprocess.PIPE, stdout=sys.stdout.buffer,
                                  stderr=subprocess.PIPE, preexec_fn=drop, start_new_session=True,
                                  env={'PATH': '/usr/bin:/bin'}) as process:
                record = {'pid': process.pid, 'start': process_start(process.pid), 'scope': key, 'state': 'running'}
                write_job(job_file, record)
                if cancelled():
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.communicate(json.dumps(child_input).encode(), timeout=1800)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate(timeout=5)
                record.update(state='finished', exitCode=process.returncode)
                write_job(job_file, record)
                if process.returncode:
                    # CLI errors may contain prompt/provider material. Never log raw stderr.
                    print('worker_cli_failed', file=sys.stderr)
                return process.returncode
        finally:
            server.shutdown()
            server.server_close()

def main():
    os.umask(0o077)
    if os.geteuid() != 0:
        raise InvalidRequest('root_driver_required')
    data = sys.stdin.buffer.read(MAX_REQUEST + 1)
    if len(data) > MAX_REQUEST:
        raise InvalidRequest('request_too_large')
    request = validate(json.loads(data))
    if request['operation'] == 'cancel':
        cancel(request)
        return 0
    if request['operation'] == 'status':
        status(request)
        return 0
    return execute(request)

if __name__ == '__main__':
    try:
        sys.exit(main())
    except InvalidRequest as error:
        print('worker_request_failed:' + str(error), file=sys.stderr)
        sys.exit(64)
    except (ValueError, KeyError, OSError, subprocess.TimeoutExpired):
        print('worker_request_failed', file=sys.stderr)
        sys.exit(64)
