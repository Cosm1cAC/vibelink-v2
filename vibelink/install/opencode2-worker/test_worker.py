import contextlib
import concurrent.futures
import base64
import http.client
import http.server
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
import uuid
from unittest import mock

import driver

def request(**changes):
    value = {'version': 1, 'scope': 'beta_worker_test_' + uuid.uuid4().hex,
             'jobId': str(uuid.uuid4()), 'model': driver.MODEL,
             'gateway': 'http://127.0.0.1:18998/api/llm/v1',
             'token': 'fixture-grant-not-a-real-token', 'session': None,
             'prompt': '', 'readOnly': False, 'files': []}
    return {**value, **changes}

class BoundaryTests(unittest.TestCase):
    def test_receipt_and_cancel_marker_atomic_writes_do_not_share_a_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / 'same-job.json'
            marker = receipt.with_suffix('.cancel')
            barrier = threading.Barrier(2)
            original_replace = os.replace
            def together(source, target):
                barrier.wait(timeout=3)
                original_replace(source, target)
            with mock.patch.object(driver.os, 'replace', side_effect=together):
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    writes = [pool.submit(driver.write_job, receipt, {'scope': 'same', 'state': 'finished'}),
                              pool.submit(driver.write_job, marker, {'scope': 'same'})]
                    for write in writes:
                        write.result(timeout=5)
            self.assertEqual(json.loads(receipt.read_text()), {'scope': 'same', 'state': 'finished'})
            self.assertEqual(json.loads(marker.read_text()), {'scope': 'same'})
            self.assertEqual(sorted(file.name for file in Path(directory).iterdir()), ['same-job.cancel', 'same-job.json'])

    def test_model_and_gateway_are_not_caller_selected_routes(self):
        for gateway in ['https://evil.invalid/api/llm/v1', 'http://127.0.0.1:18998/api/admin',
                        'http://127.0.0.1:3425/v1', 'http://127.0.0.1:19876/api/llm/v1',
                        'http://user:pass@127.0.0.1:18998/api/llm/v1']:
            with self.assertRaises(driver.InvalidRequest):
                driver.validate(request(gateway=gateway))
        with self.assertRaises(driver.InvalidRequest):
            driver.validate(request(model='gpt-other'))

    def test_scope_session_and_attachments_cannot_name_host_paths(self):
        for data in [request(scope='../../root'), request(session='../../auth.json'),
                     request(files=[{'path': '/mnt/c/Users/private.txt'}]),
                     request(token='header\r\ninjection'), request(readOnly='false')]:
            with self.assertRaises(driver.InvalidRequest):
                driver.validate(data)

    def test_inline_inputs_are_bounded_and_never_host_paths(self):
        file = {'filename': '说明.txt', 'mime': 'text/plain', 'base64': base64.b64encode(b'fixture input').decode()}
        self.assertEqual(driver.validate(request(files=[file]))['files'][0]['content'], b'fixture input')
        for files in [[{**file, 'filename': name}] for name in ('../auth.json', '/root/key', 'a\\b', '.secret', 'a\n.txt')]:
            with self.assertRaises(driver.InvalidRequest):
                driver.validate(request(files=files))
        for files in [[file] * 9, [file, file], [{**file, 'base64': 'invalid!'}], [{**file, 'path': '/root/key'}]]:
            with self.assertRaises(driver.InvalidRequest):
                driver.validate(request(files=files))
        with mock.patch.object(driver, 'MAX_ATTACHMENTS_BYTES', 4):
            with self.assertRaises(driver.InvalidRequest):
                driver.validate(request(files=[file]))

    def test_status_does_not_infer_quiescence_from_a_missing_record(self):
        with io.StringIO() as output, contextlib.redirect_stdout(output):
            driver.status(driver.validate({'version': 1, 'operation': 'status',
                                          'scope': 'unknown_fixture', 'jobId': uuid.uuid4().hex}))
            self.assertEqual(json.loads(output.getvalue()), {'known': False, 'running': False, 'quiescent': False})

    def test_launch_has_required_namespaces_and_no_real_grant(self):
        args = driver.command('/home-scope', '/approved-workspace', '/private-socket',
                              '/provider.json', '/minimal-etc', True)
        self.assertIn('--unshare-all', args)
        self.assertIn('--clearenv', args)
        self.assertIn('--cap-drop', args)
        self.assertIn('--die-with-parent', args)
        self.assertNotIn('fixture-grant-not-a-real-token', args)
        position = args.index('/approved-workspace')
        self.assertEqual(args[position - 1], '--ro-bind')
        self.assertNotIn('/mnt/c', args)
        self.assertNotIn('/home/shaotian', args)

    def test_model_proxy_overwrites_auth_and_denies_admin_and_other_models(self):
        observed = []
        class Upstream(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                observed.append((self.path, self.headers.get('Authorization')))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{}')
        upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory() as directory:
            file = str(Path(directory) / 'model.sock')
            server = driver.UnixServer(file, driver.Gateway)
            server.upstream, server.port, server.grant = '127.0.0.1', upstream.server_port, 'fixture-model-only-grant'
            threading.Thread(target=server.serve_forever, daemon=True).start()
            class Unix(http.client.HTTPConnection):
                def connect(self):
                    import socket
                    self.sock = socket.socket(socket.AF_UNIX)
                    self.sock.settimeout(3)
                    self.sock.connect(file)
            try:
                for method, path, body, expected in [
                    ('GET', '/v1/models', None, 200),
                    ('GET', '/api/admin', None, 403),
                    ('POST', '/v1/chat/completions', '{"model":"gpt-other"}', 403),
                ]:
                    connection = Unix('unused')
                    connection.request(method, path, body, {'Authorization': 'Bearer attacker-controlled'})
                    response = connection.getresponse()
                    self.assertEqual(response.status, expected)
                    response.read()
                    connection.close()
                self.assertEqual(observed, [('/api/llm/v1/models', 'Bearer fixture-model-only-grant')])
            finally:
                server.shutdown()
                server.server_close()
        upstream.shutdown()
        upstream.server_close()

@unittest.skipUnless(os.geteuid() == 0 and Path('/opt/vibelink-worker/bin/opencode').exists(), 'requires installed WSL worker')
class KernelTests(unittest.TestCase):
    def call(self, value):
        result = subprocess.run(['/opt/vibelink-worker/driver.py'], input=json.dumps(value),
                                text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_actual_readonly_mount_and_network_host_isolation(self):
        value = request(operation='probe', files=[{'filename': 'fixture.txt', 'mime': 'text/plain', 'base64': base64.b64encode(b'fixture input').decode()}])
        writable = self.call(value)
        readonly = self.call({**value, 'jobId': str(uuid.uuid4()), 'readOnly': True})
        self.assertTrue(writable['workspaceWrite'])
        self.assertFalse(readonly['workspaceWrite'])
        self.assertGreaterEqual(readonly['workspaceFiles'], 1)
        self.assertRegex(readonly['cliVersion'], r'^(?:opencode v)?2\.0\.32$')
        self.assertFalse(any(readonly['hostPathsVisible'].values()))
        self.assertFalse(readonly['directInternet'])
        self.assertEqual(readonly['modelGatewayStatus'], 401)
        self.assertTrue(readonly['tokenIsPlaceholder'])
        self.assertEqual(readonly['capabilities'], '0000000000000000')
        self.assertEqual(readonly['inputContents'], {'fixture.txt': 'fixture input'})
        self.assertFalse(readonly['inputsWrite'])
        import hashlib
        key = hashlib.sha256(value['scope'].encode()).hexdigest()
        uid = json.loads(Path('/var/lib/vibelink-worker/uids.json').read_text())[key]
        file = Path('/var/lib/vibelink-worker/scopes') / key / 'workspace/worker-write-probe.txt'
        self.assertGreaterEqual(uid, 200000)
        self.assertEqual(file.stat().st_uid, uid)

    def test_actual_cli_readonly_tool_catalog_and_metered_provider_with_mock_model(self):
        observed_tools = []
        class Model(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
                observed_tools.extend(tool.get('function', {}).get('name', '') for tool in body.get('tools', []))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                for chunk in [
                    {'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'Fixture OK'}, 'finish_reason': None}]},
                    {'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}},
                ]:
                    self.wfile.write(('data: ' + json.dumps({'id': 'fixture', 'object': 'chat.completion.chunk', 'model': driver.MODEL, **chunk}) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
        try:
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 18998), Model)
        except OSError:
            self.skipTest('Fixed WSL-only mock port 18998 is occupied; production routes are not changed')
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryFile(mode='w+b') as output:
                text = io.TextIOWrapper(output, encoding='utf8')
                with mock.patch.object(driver, 'nat_gateway', return_value='127.0.0.1'), contextlib.redirect_stdout(text):
                    code = driver.execute(driver.validate(request(readOnly=True, prompt='Reply only Fixture OK.')))
                text.flush()
                output.seek(0)
                records = output.read().decode()
                text.detach()
            self.assertEqual(code, 0)
            self.assertIn('Fixture OK', records)
            self.assertTrue(observed_tools, 'Readonly mode must preserve useful read tools')
            self.assertTrue(set(observed_tools).isdisjoint({'edit', 'write', 'patch', 'shell', 'subagent', 'execute'}), observed_tools)
        finally:
            server.shutdown()
            server.server_close()

    def test_cancel_waits_for_durable_quiescence_and_startup_cancel_is_not_lost(self):
        value = request(operation='probe', prompt='__cancel_test__')
        process = subprocess.Popen(['/opt/vibelink-worker/driver.py'], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        process.stdin.write(json.dumps(value))
        process.stdin.close()
        process.stdin = None
        control = {'version': 1, 'scope': value['scope'], 'jobId': value['jobId']}
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                state = self.call({**control, 'operation': 'status'})
                if state['running']:
                    break
                time.sleep(0.05)
            self.assertTrue(state['running'])
            self.call({**control, 'operation': 'cancel'})
            process.communicate(timeout=5)
            self.assertTrue(self.call({**control, 'operation': 'status'})['quiescent'])
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()
        early = request(operation='probe', prompt='__cancel_test__')
        self.call({'version': 1, 'operation': 'cancel', 'scope': early['scope'], 'jobId': early['jobId']})
        result = subprocess.run(['/opt/vibelink-worker/driver.py'], input=json.dumps(early),
                                text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 130)
        self.assertTrue(self.call({'version': 1, 'operation': 'status', 'scope': early['scope'], 'jobId': early['jobId']})['quiescent'])

if __name__ == '__main__':
    unittest.main()
