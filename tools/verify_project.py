#!/usr/bin/env python3
"""Fail CI before Gradle if required native files or supplied artwork are missing."""
import hashlib
import re
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def png_size(path):
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', f'Not a PNG: {path}'
    return struct.unpack('>II', data[16:24])

required = [
    'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'app/build.gradle', 'app/src/main/AndroidManifest.xml',
    'app/src/main/java/id/my/chiroot/MainActivity.java',
    '.github/workflows/android-build.yml', 'README.md',
    'supabase/migrations/202611080001_chiroot_native.sql',
]
for name in required:
    assert (ROOT / name).is_file(), f'Missing required project file: {name}'
source_icon = ROOT / 'favicon.png'
assert digest(source_icon) == digest(RES / 'drawable-nodpi/favicon.png'), 'Bundled favicon differs from supplied source'
assert png_size(source_icon) == (64, 64)
assert png_size(RES / 'drawable-nodpi/chiroot_error_sign.png') == (1888, 833)
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
app = manifest.find('application')
assert app.get(ANDROID + 'icon') == '@mipmap/ic_launcher'
assert app.get(ANDROID + 'roundIcon') == '@mipmap/ic_launcher_round'
for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
    icon = ET.parse(RES / 'mipmap-anydpi-v26' / name).getroot()
    assert icon.tag == 'adaptive-icon'
    assert icon.find('foreground').get(ANDROID + 'drawable') == '@mipmap/ic_launcher_foreground'
for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
    for name in ('ic_launcher.png', 'ic_launcher_round.png', 'ic_launcher_foreground.png'):
        assert (RES / f'mipmap-{density}' / name).is_file(), f'Missing {density}/{name}'
java_files = list((ROOT / 'app/src/main').rglob('*.java'))
assert len(java_files) == 1, 'Application implementation must stay in MainActivity.java'
source = java_files[0].read_text(encoding='utf-8')
assert not re.search(r'import\s+android\.webkit|new\s+WebView\b|extends\s+WebView\b', source), 'WebView is forbidden'
assert not list((ROOT / 'app/src/main').rglob('*.kt')), 'Kotlin application sources are forbidden'
assert 'chiroot_error_sign' in source and 'Try Again' in source
assert 'app/build/outputs/apk/debug/app-debug.apk' in (ROOT / '.github/workflows/android-build.yml').read_text()
assert 'workflow_dispatch' in (ROOT / '.github/workflows/android-build.yml').read_text()
print('Project checks passed: native Java, complete build files, exact favicon and error illustration.')
print('favicon.png SHA-256:', digest(source_icon))
print('error illustration SHA-256:', digest(RES / 'drawable-nodpi/chiroot_error_sign.png'))
