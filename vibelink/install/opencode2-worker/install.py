#!/usr/bin/python3
"""Run as WSL root. Installs only the pinned official Linux CLI and worker files."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path('/opt/vibelink-worker')
VERSION = '2.0.32'
URL = 'https://registry.npmjs.org/@opencode/cli-linux-x64/-/cli-linux-x64-2.0.32.tgz'
INTEGRITY = 'Q50ofBQ3gdwjD6ZQhrVR8fVlm8D2xPMshy4dynY6l6b+qLdKgBSJ9WpoaDyrQX1YWmf2s7IN4RbXmoXXdJitDA=='
BINARY_SHA256 = '489b51816a77809e841310e80c88e834c28c19ad1639e12ba8a19f08b47ab607'

def main():
    if os.geteuid() != 0:
        raise SystemExit('Installer requires WSL root')
    ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    binary = ROOT / 'bin/opencode'
    if not binary.exists():
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(URL, timeout=60) as response:
            payload = response.read(256 * 1024 * 1024)
        digest = base64.b64encode(hashlib.sha512(payload).digest()).decode()
        if digest != INTEGRITY:
            raise SystemExit('Official package integrity mismatch')
        with tarfile.open(fileobj=io.BytesIO(payload), mode='r:gz') as archive:
            member = archive.getmember('package/bin/opencode')
            if not member.isfile():
                raise SystemExit('Invalid binary member')
            data = archive.extractfile(member).read()
        binary.parent.mkdir(mode=0o755, exist_ok=True)
        binary.write_bytes(data)
        binary.chmod(0o755)
    if hashlib.sha256(binary.read_bytes()).hexdigest() != BINARY_SHA256:
        raise SystemExit('Installed binary does not match the pinned official package')
    for name in ['driver.py', 'namespace.py']:
        source = Path(__file__).parent / name
        if source.exists():
            shutil.copyfile(source, ROOT / name)
            (ROOT / name).chmod(0o755)
    (ROOT / 'installed.json').write_text(json.dumps({
        'version': VERSION, 'source': URL, 'sha512': INTEGRITY,
        'binarySha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
    }, indent=2) + '\n')
    print('Pinned official OpenCode2 Linux binary installed; worker files copied.')

if __name__ == '__main__':
    main()
