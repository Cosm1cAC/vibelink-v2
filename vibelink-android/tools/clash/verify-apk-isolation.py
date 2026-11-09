"""Verify edition assets without printing configuration or credentials."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--dev', required=True, type=Path)
parser.add_argument('--beta', required=True, type=Path)
parser.add_argument('--seed', required=True, type=Path)
args = parser.parse_args()
asset = 'assets/private-clash/dev-seed.yaml'
reports = {}
for edition, path in [('dev', args.dev), ('beta', args.beta)]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        private = [name for name in names if name.startswith('assets/private-clash/')]
        assert 'lib/arm64-v8a/libclash.so' in names, 'Clash native core missing'
        if edition == 'dev':
            assert archive.read(asset) == args.seed.read_bytes(), 'Developer seed mismatch'
            for name in ['Country.mmdb', 'geosite.dat']:
                assert f'assets/private-clash/data/{name}' in names, 'Offline geo data missing'
        else:
            assert not private, 'Private developer assets found in beta APK'
        reports[edition] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                            'privateAssetCount': len(private), 'bytes': path.stat().st_size}
print(json.dumps(reports))
