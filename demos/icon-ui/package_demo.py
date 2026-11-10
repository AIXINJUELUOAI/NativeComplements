"""Verify the built standalone Demo and copy only its APK into deliverables."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
SDK = Path(os.environ.get('ANDROID_HOME') or str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'))
TOOLS = SDK / 'build-tools/36.1.0'
JDK = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))


def run(*args):
    return subprocess.check_output(list(map(str, args)), encoding='utf-8', errors='replace')


def main():
    apk = BASE / 'build/outputs/apk/release/ComplementsLab-release.apk'
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert not any(name.startswith('META-INF/xposed/') for name in archive.namelist())
        # AAPT2 shortens resource paths in release APKs; the only resource is our flat PNG.
        resources = [name for name in archive.namelist() if name.startswith('res/')]
        assert len(resources) == 1 and resources[0].endswith('.png'), resources
        assert archive.read(resources[0]).startswith(b'\x89PNG\r\n\x1a\n')
        assert archive.read('assets/index.html') == (BASE / 'src/main/assets/index.html').read_bytes()
    cert = run(JDK / 'bin/java.exe', '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--print-certs', apk)
    assert re.search(r'certificate SHA-256 digest: (\w+)', cert)[1] == '28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104'
    badging = run(TOOLS / 'aapt2.exe', 'dump', 'badging', apk).splitlines()[0]
    assert "name='dev.local.nativecomplements.lab'" in badging and "versionName='0.1.0'" in badging
    manifest = run(TOOLS / 'aapt2.exe', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
    assert 'android.permission.INTERNET' not in manifest
    target = ROOT / 'deliverables/ComplementsLab-0.1.0.apk'
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    print(f'{badging}\nAPK={target}\nSHA256={digest}\nVerified: original signing certificate, flat PNG, no Xposed entry or network permission')


if __name__ == '__main__':
    main()
