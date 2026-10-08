"""Build Watch Desk Relay with JDK 17+ and Android SDK 35; no Gradle download."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def java_tool(name):
    suffix = '.exe' if os.name == 'nt' else ''
    if os.environ.get('JAVA_HOME'):
        path = Path(os.environ['JAVA_HOME']) / 'bin' / (name + suffix)
        if path.is_file():
            return str(path)
    path = shutil.which(name)
    if not path:
        raise RuntimeError('JDK 17+ required: ' + name)
    return path


def sdk_default():
    configured = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if configured:
        return Path(configured).expanduser()
    if platform.system() == 'Darwin':
        return Path.home() / 'Library/Android/sdk'
    if platform.system() == 'Windows':
        return Path(os.environ.get('LOCALAPPDATA', str(Path.home()/'AppData/Local'))) / 'Android/Sdk'
    return Path.home() / 'Android/Sdk'


def run(command, env=None):
    subprocess.run([str(arg) for arg in command], check=True, cwd=ROOT, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=sdk_default())
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--test-only', action='store_true')
    parser.add_argument('--face', type=Path, help='Optionally bundle the selected native .face for this delivery')
    args = parser.parse_args()
    out = ROOT/'build'
    out.mkdir(exist_ok=True)
    java, javac = java_tool('java'), java_tool('javac')
    core = sorted(p for p in (ROOT/'src').rglob('*.java') if p.name != 'MainActivity.java')
    with tempfile.TemporaryDirectory(prefix='test-', dir=out) as staging:
        run([javac, '-encoding', 'UTF-8', '--release', '8', '-d', staging, *core, ROOT/'tests/ProtocolTest.java'])
        run([java, '-cp', staging, 'local.watchdesk.relay.ProtocolTest'])
    if args.test_only:
        return
    sdk = args.sdk.expanduser().resolve()
    tools, android = sdk/'build-tools'/args.build_tools, sdk/'platforms/android-35/android.jar'
    suffix = '.exe' if os.name == 'nt' else ''
    for path in [tools/('aapt2'+suffix), tools/('zipalign'+suffix), tools/'lib/d8.jar', tools/'lib/apksigner.jar', android]:
        if not path.is_file():
            raise RuntimeError('Missing SDK file: '+str(path))
    # A local development key stays under ignored build/ and is never source-delivered.
    key = out/'relay-development.keystore'
    signing = os.environ.copy()
    signing['WATCH_RELAY_STORE_PASS'] = 'android'
    if not key.exists():
        run([java_tool('keytool'), '-genkeypair', '-keystore', key, '-alias', 'relay',
             '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048',
             '-validity', '3650', '-dname', 'CN=Watch Desk Relay Development'])
    with tempfile.TemporaryDirectory(prefix='apk-', dir=out) as temp:
        stage = Path(temp)
        classes, dex = stage/'classes', stage/'dex'
        classes.mkdir(); dex.mkdir()
        run([javac, '-encoding', 'UTF-8', '--release', '8', '-classpath', android, '-d', classes, *sorted((ROOT/'src').rglob('*.java'))])
        run([java, '-cp', tools/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android,
             '--output', dex, *sorted(classes.rglob('*.class'))])
        resources, unsigned, aligned = stage/'res.zip', stage/'unsigned.apk', stage/'aligned.apk'
        assets = stage/'assets'
        assets.mkdir()
        if args.face:
            face = args.face.expanduser().resolve()
            if not face.is_file() or not 2048 <= face.stat().st_size <= 64*1024*1024:
                raise RuntimeError('Invalid bundled face size')
            header = face.read_bytes()[:64]
            if header[:4] != bytes.fromhex('5aa53412') or int.from_bytes(header[16:20], 'little') != 0x800:
                raise RuntimeError('Bundled file is not a native 0x800 face')
            shutil.copy2(face, assets/'bundled.face')
        run([tools/('aapt2'+suffix), 'compile', '--dir', ROOT/'res', '-o', resources])
        run([tools/('aapt2'+suffix), 'link', '-I', android, '--manifest', ROOT/'AndroidManifest.xml', '-A', assets, resources, '-o', unsigned])
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as z:
            for file in sorted(dex.glob('*.dex')):
                z.write(file, file.name)
        run([tools/('zipalign'+suffix), '-f', '4', unsigned, aligned])
        candidate = stage/'watch-desk-relay.apk'
        run([java, '-jar', tools/'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'relay',
             '--ks-pass', 'env:WATCH_RELAY_STORE_PASS', '--out', candidate, aligned], signing)
        run([java, '-jar', tools/'lib/apksigner.jar', 'verify', '--verbose', candidate])
        run([tools/('zipalign'+suffix), '-c', '4', candidate])
        manifest = ET.parse(ROOT/'AndroidManifest.xml').getroot()
        version = manifest.attrib['{http://schemas.android.com/apk/res/android}versionName']
        target = out/'小米手表中转.apk'
        shutil.copy2(candidate, target)
    result = {'apk': target.name, 'versionName': version, 'versionCode': int(manifest.attrib['{http://schemas.android.com/apk/res/android}versionCode']), 'bytes': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
              'protocolTests': 'passed', 'apkSignature': 'verified', 'androidRuntime': 'not tested', 'watchInstall': 'not tested'}
    if args.face:
        result['bundledFaceSha256'] = hashlib.sha256(args.face.read_bytes()).hexdigest()
    (out/'verification.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
