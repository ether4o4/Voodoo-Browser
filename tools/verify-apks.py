#!/usr/bin/env python3
"""Record exact source, packaged asset, unit-test and signing evidence in CI."""
import hashlib
import json
import os
import re
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
aapt = build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
apksigner = build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
gradle = (root / 'app/build.gradle').read_text(encoding='utf-8')
version_codes = re.findall(r'^\s*versionCode\s+(\d+)\s*$', gradle, re.MULTILINE)
version_names = re.findall(r'^\s*versionName\s+[\'\"]([^\'\"]+)[\'\"]\s*$', gradle, re.MULTILINE)
assert len(version_codes) == len(version_names) == 1, 'Expected one source versionCode and versionName'
version_code, version_name = int(version_codes[0]), version_names[0]
debug_suffix = re.search(r'\bversionNameSuffix\s+[\'\"]([^\'\"]*)[\'\"]', gradle)
debug_suffix = debug_suffix.group(1) if debug_suffix else ''
run = lambda *args: subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)
head = run('git', 'rev-parse', 'HEAD').strip()
assets = list((root / 'app/src/main/assets').rglob('*'))
report = {'commit': head, 'apks': [], 'native_tests': {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}}
for flavor, package in [('debug', 'com.ether4o4.voodoo.dev'), ('release', 'com.ether4o4.voodoo')]:
    apks = list((root / 'app/build/outputs/apk' / flavor).glob('*.apk'))
    assert len(apks) == 1, f'Expected one {flavor} APK'
    apk = apks[0]
    with zipfile.ZipFile(apk) as z:
        forbidden_assets = {'assets/tools/suno.js', 'assets/tools/loop.js', 'assets/tools/loop-ui.js'}
        assert not forbidden_assets.intersection(z.namelist()), 'Removed Suno assets are still packaged'
        for source in assets:
            if source.is_file():
                name = 'assets/' + source.relative_to(root / 'app/src/main/assets').as_posix()
                assert z.read(name) == source.read_bytes(), f'Packaged asset mismatch: {name}'
        assert 'lib/arm64-v8a/libvoodoo-completion.so' in z.namelist()
    badging = run(str(aapt), 'dump', 'badging', str(apk))
    actual = dict(re.findall(r"(\w+)='([^']*)'", badging.splitlines()[0]))
    expected_name = version_name + (debug_suffix if flavor == 'debug' else '')
    assert actual.get('name') == package, f'Unexpected {flavor} package'
    assert actual.get('versionCode') == str(version_code), f'{flavor} APK versionCode differs from source'
    assert actual.get('versionName') == expected_name, f'{flavor} APK versionName differs from source'
    (out / (flavor + '-package.txt')).write_text(badging)
    signature = 'unsigned; original owner key required for an update'
    if flavor == 'debug':
        signature = run(str(apksigner), 'verify', '--verbose', '--print-certs', str(apk))
        (out / 'debug-signature.txt').write_text(signature)
    report['apks'].append({'file': apk.name, 'package': package, 'bytes': apk.stat().st_size,
        'versionCode': int(actual['versionCode']), 'versionName': actual['versionName'],
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'assets_match_source': True,
        'suno_assets_absent': True,
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
