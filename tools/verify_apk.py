#!/usr/bin/env python3
"""Inspect the actual assembled APK, not just the source manifest."""
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1]).resolve()
assert apk.is_file() and apk.stat().st_size > 100_000, 'APK missing or implausibly small'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path.home() / 'Android/Sdk'))
tools = sorted((sdk / 'build-tools').glob('*/aapt'), reverse=True)
assert tools, 'Android aapt not found; install build-tools;35.0.0'
badging = subprocess.check_output([str(tools[0]), 'dump', 'badging', str(apk)], text=True)
package = re.search(r"package: name='([^']+)'", badging)
assert package and package.group(1) == 'id.my.chiroot.debug', badging
assert re.search(r"sdkVersion:'29'", badging), 'Minimum Android API must be 29'
assert re.search(r"targetSdkVersion:'35'", badging), 'Target SDK must be 35'
assert re.search(r"application: label='Chiroot'", badging), 'Wrong application label'
icons = re.findall(r"application-icon(?:-[\d]+)?:'([^']+)'", badging)
assert len(icons) >= 5, f'Expected density-specific launcher icons, got {icons}'
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    assert 'classes.dex' in names and 'resources.arsc' in names
    for path in icons:
        assert path in names, f'Launcher icon referenced by APK is absent: {path}'
    assert any(name.endswith('chiroot_error_sign.png') for name in names), 'Error illustration not packaged'
    assert any(name.endswith('favicon.png') for name in names), 'Original favicon resource not packaged'
    assert any(name.endswith('chiroot_body.ttf') for name in names), 'Offline Chiroot font not packaged'
    assert not any(name.endswith('.kt') for name in names)
print(f'APK verified: {apk.name}, {apk.stat().st_size:,} bytes, API 29+, {len(icons)} launcher icon entries.')
print('\n'.join(icons))
