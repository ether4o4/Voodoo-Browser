#!/usr/bin/env python3
"""Record exact source, packaged asset, unit-test and signing evidence in CI."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
os.chdir(root)
out = root / 'verification'
out.mkdir(exist_ok=True)
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
build_tools = sdk / 'build-tools' / '35.0.0'
run = lambda *args: subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)
head = run('git', 'rev-parse', 'HEAD').strip()
assets = list((root / 'app/src/main/assets').rglob('*'))
report = {'commit': head, 'apks': [], 'native_tests': {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}}
for flavor, package in [('debug', 'com.ether4o4.voodoo.dev'), ('release', 'com.ether4o4.voodoo')]:
    apks = list((root / 'app/build/outputs/apk' / flavor).glob('*.apk'))
    assert len(apks) == 1, f'Expected one {flavor} APK'
    apk = apks[0]
    with zipfile.ZipFile(apk) as z:
        for source in assets:
            if source.is_file():
                name = 'assets/' + source.relative_to(root / 'app/src/main/assets').as_posix()
                assert z.read(name) == source.read_bytes(), f'Packaged asset mismatch: {name}'
        assert 'lib/arm64-v8a/libvoodoo-completion.so' in z.namelist()
    badging = run(str(build_tools / 'aapt'), 'dump', 'badging', str(apk))
    assert "name='" + package + "'" in badging.splitlines()[0]
    assert "versionCode='8'" in badging.splitlines()[0]
    (out / (flavor + '-package.txt')).write_text(badging)
    signature = 'unsigned; original owner key required for an update'
    if flavor == 'debug':
        signature = run(str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
        (out / 'debug-signature.txt').write_text(signature)
    report['apks'].append({'file': apk.name, 'package': package, 'bytes': apk.stat().st_size,
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'assets_match_source': True,
        'signing': 'verified development key' if flavor == 'debug' else signature})
xmls = list((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert xmls, 'Missing native unit test results'
for xml in xmls:
    suite = ET.parse(xml).getroot()
    for key in report['native_tests']:
        report['native_tests'][key] += int(suite.attrib.get(key, 0))
assert report['native_tests']['failures'] == report['native_tests']['errors'] == 0
(out / 'build-evidence.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
