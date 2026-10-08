"""Build the separate signed Android startup instrumentation APK."""
import argparse
from pathlib import Path
import os
import tempfile
import zipfile
import sys
sys.dont_write_bytecode = True
from build import ROOT, java_tool, run, sdk_default


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk', type=Path, default=sdk_default())
    p.add_argument('--status', action='store_true', help='Build a read-only monitor targeting its own process, not the relay')
    args = p.parse_args()
    tools = args.sdk/'build-tools/35.0.0'
    android = args.sdk/'platforms/android-35/android.jar'
    out = ROOT/'build'
    suffix = '.exe' if os.name == 'nt' else ''
    java = java_tool('java')
    with tempfile.TemporaryDirectory(prefix='smoke-', dir=out) as folder:
        stage = Path(folder)
        classes, dex = stage/'classes', stage/'dex'
        classes.mkdir(); dex.mkdir()
        source = ROOT/'tests'/('AndroidStatus.java' if args.status else 'AndroidSmoke.java')
        manifest = ROOT/'tests'/('StatusManifest.xml' if args.status else 'AndroidManifest.xml')
        run([java_tool('javac'), '-encoding', 'UTF-8', '--release', '8', '-classpath', android, '-d', classes, source])
        run([java, '-cp', tools/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android, '--output', dex, *classes.rglob('*.class')])
        unsigned, aligned = stage/'unsigned.apk', stage/'aligned.apk'
        run([tools/('aapt2'+suffix), 'link', '-I', android, '--manifest', manifest, '-o', unsigned])
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as z:
            z.write(dex/'classes.dex', 'classes.dex')
        run([tools/('zipalign'+suffix), '-f', '4', unsigned, aligned])
        env = os.environ.copy(); env['WATCH_RELAY_STORE_PASS'] = 'android'
        target = out/('relay-status.apk' if args.status else 'relay-smoke.apk')
        run([java, '-jar', tools/'lib/apksigner.jar', 'sign', '--ks', out/'relay-development.keystore', '--ks-key-alias', 'relay', '--ks-pass', 'env:WATCH_RELAY_STORE_PASS', '--out', target, aligned], env)
        run([java, '-jar', tools/'lib/apksigner.jar', 'verify', target])
    print('Built separate instrumentation APK: '+str(target))


if __name__ == '__main__':
    main()
