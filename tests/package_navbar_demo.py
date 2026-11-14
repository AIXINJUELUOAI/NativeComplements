"""Build the independent observation APK, never install or change device settings."""
import os, re, subprocess, hashlib, zipfile, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    jdk = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
    env = os.environ.copy(); env['JAVA_HOME'] = str(jdk)
    subprocess.run([str(ROOT/'gradlew.bat'), '--no-daemon', '--console=plain', ':navbar-demo:testDebugUnitTest', ':navbar-demo:assembleRelease'], cwd=ROOT, env=env, check=True)
    apk = ROOT/'navbar-demo/build/outputs/apk/release/navbar-demo-release.apk'
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        assert z.read('META-INF/xposed/scope.list').decode().strip() == 'system'
        assert z.read('META-INF/xposed/java_init.list').decode().strip() == 'dev.nativecomplements.navdemo.DemoModule'
        assert not any(n.endswith(('.keystore','.jks')) for n in z.namelist())
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk'))
    tools = sdk/'build-tools/36.1.0'
    cert = subprocess.check_output([str(jdk/'bin/java.exe'),'-jar',str(tools/'lib/apksigner.jar'),'verify','--print-certs',str(apk)], text=True)
    assert re.search(r'certificate SHA-256 digest: (\w+)',cert)[1] == '28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104'
    badging = subprocess.check_output([str(tools/'aapt2.exe'),'dump','badging',str(apk)],text=True,encoding='utf-8')
    assert "name='dev.nativecomplements.navdemo'" in badging
    version = re.search(r"versionName='([^']+)'",badging)[1]
    target=ROOT/f'deliverables/NavbarObservationDemo-{version}.apk'; target.parent.mkdir(exist_ok=True)
    shutil.copyfile(apk,target)
    print('APK='+str(target)); print('SHA256='+hashlib.sha256(target.read_bytes()).hexdigest())
    print('PASS independent identity, original certificate, system-only observation scope')
if __name__ == '__main__': main()
