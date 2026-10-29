"""Repackage launcher-only resources in an existing build using Android SDK.

Java/Dex files and resources.arsc remain byte-for-byte unchanged. A normal
Gradle build is also supported and does not require this optional utility.
"""
from pathlib import Path
import argparse
import hashlib
import re
import struct
import subprocess
import tempfile
import zipfile


def patch_version(data, code, name):
    data = bytearray(data); chunks = []; strings = []; locations = []
    offset = struct.unpack_from('<H', data, 2)[0]
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        assert size >= header and offset+size <= len(data)
        chunks.append((kind, offset, size))
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from('<IIIII', data, offset+8)
            def length(pos, utf8):
                if utf8:
                    n = data[pos]
                    return (((n & 127) << 8) | data[pos+1], pos+2) if n & 128 else (n, pos+1)
                n = struct.unpack_from('<H', data, pos)[0]
                return (((n & 32767) << 16) | struct.unpack_from('<H', data, pos+2)[0], pos+4) if n & 32768 else (n, pos+2)
            for i in range(count):
                p = offset+start+struct.unpack_from('<I', data, offset+header+4*i)[0]
                n, p = length(p, bool(flags & 256))
                if flags & 256:
                    n, p = length(p, True); codec = 'utf-8'; end = p+n
                else:
                    codec = 'utf-16-le'; end = p+2*n
                strings.append(bytes(data[p:end]).decode(codec)); locations.append((p, end, codec))
        offset += size
    changed = set()
    for kind, offset, size in chunks:
        if kind != 0x102 or strings[struct.unpack_from('<I', data, offset+20)[0]] != 'manifest':
            continue
        start, attr_size, count = struct.unpack_from('<HHH', data, offset+24)
        for i in range(count):
            pos = offset+16+start+i*attr_size
            attr = strings[struct.unpack_from('<I', data, pos+4)[0]]
            if attr == 'versionCode':
                assert data[pos+15] in (0x10, 0x11)
                struct.pack_into('<I', data, pos+16, code); changed.add(attr)
            elif attr == 'versionName':
                assert data[pos+15] == 3
                index = struct.unpack_from('<I', data, pos+16)[0]
                begin, end, codec = locations[index]; encoded = name.encode(codec)
                if len(encoded) != end-begin: raise ValueError('Version string must retain its encoded length')
                data[begin:end] = encoded; changed.add(attr)
    assert changed == {'versionCode', 'versionName'}
    return bytes(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    config = (root/'app/build.gradle').read_text()
    code = int(re.search(r'versionCode\s+(\d+)', config)[1])
    name = re.search(r"versionName\s+'([^']+)'", config)[1]
    aapt = str(args.build_tools/'aapt2')
    with tempfile.TemporaryDirectory(prefix='utv-assets-') as temporary:
        temp = Path(temporary); compiled = temp/'compiled'; compiled.mkdir()
        manifest = temp/'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.mark.utvremote"><uses-sdk android:minSdkVersion="26"/><application/></manifest>')
        subprocess.run([aapt, 'compile', str(root/'app/src/main/res/drawable/app_icon.xml'), '-o', str(compiled)], check=True)
        linked = temp/'resources.apk'
        subprocess.run([aapt, 'link', '-o', str(linked), '-I', str(args.android_jar), '--manifest', str(manifest),
                        '--min-sdk-version', '26', *map(str, compiled.glob('*.flat'))], check=True)
        with zipfile.ZipFile(linked) as z:
            icon = z.read('res/drawable/app_icon.xml')
        unsigned = temp/'unsigned.apk'
        expected_changes = {'AndroidManifest.xml', 'res/drawable/app_icon.xml', 'res/drawable-xhdpi-v4/tv_banner.png'}
        with zipfile.ZipFile(args.base_apk) as old, zipfile.ZipFile(unsigned, 'w') as new:
            for item in old.infolist():
                if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', item.filename, flags=re.I): continue
                content = old.read(item.filename)
                if item.filename == 'AndroidManifest.xml': content = patch_version(content, code, name)
                elif item.filename == 'res/drawable/app_icon.xml': content = icon
                elif item.filename == 'res/drawable-xhdpi-v4/tv_banner.png': content = (root/'app/src/main/res/drawable-xhdpi/tv_banner.png').read_bytes()
                new.writestr(item, content)
        aligned = temp/'aligned.apk'
        subprocess.run([str(args.build_tools/'zipalign'), '-f', '-p', '4', str(unsigned), str(aligned)], check=True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(args.build_tools/'apksigner'), 'sign', '--ks', str(root/'signing/prototype-debug.keystore'),
                        '--ks-key-alias', 'AndroidDebugKey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                        '--out', str(args.output), str(aligned)], check=True)
        subprocess.run([str(args.build_tools/'apksigner'), 'verify', str(args.output)], check=True)
        subprocess.run([str(args.build_tools/'zipalign'), '-c', '-p', '4', str(args.output)], check=True)
        with zipfile.ZipFile(args.base_apk) as before, zipfile.ZipFile(args.output) as after:
            assert after.testzip() is None
            for entry in before.namelist():
                if entry in expected_changes or entry.startswith('META-INF/'): continue
                assert before.read(entry) == after.read(entry), 'Unexpected change: '+entry
            print('Verified: all Dex, libraries and resource IDs unchanged; 3 launcher/manifest resources updated.')
        print('APK SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__': main()
