"""Build and deliver only the current APK; never install, reboot or make backups."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile
from verify_r8_host_names import check as check_r8

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = "28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104"
SCOPES = {"system", "com.android.systemui", "com.google.android.apps.nexuslauncher"}


def run(*args):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", check=True)
    return result.stdout + result.stderr


def main():
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert re.search(r"isMinifyEnabled\s*=\s*true", gradle), "Release must enable R8"
    version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    code = int(re.search(r'versionCode = (\d+)', gradle)[1])
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    tools = sdk / "build-tools/36.1.0"
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    subprocess.run([str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain",
                    ":app:assembleRelease"], cwd=ROOT, env=env, check=True)
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    assert mapping.exists() and "# compiler: R8" in mapping.read_text(encoding="utf-8"), "R8 mapping missing"
    check_r8(apk)
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK CRC failure"
        assert set(archive.read("META-INF/xposed/scope.list").decode().splitlines()) == SCOPES
        assert "staticScope=true" in archive.read("META-INF/xposed/module.prop").decode()
        assert set(archive.read("META-INF/xposed/java_init.list").decode().splitlines()) == {
            "dev.local.sunwindowdemo.SunWindowModule",
            "dev.local.sunwindowdemo.LauncherGuard",
            "dev.local.sunwindowdemo.BackBridge",
            "dev.local.sunwindowdemo.features.callblocking.CallBlockingModule",
            "dev.local.sunwindowdemo.features.iconlayers.IconLayerModule",
            "dev.local.sunwindowdemo.features.iconlayers.SystemIconModule",
            "dev.local.sunwindowdemo.features.navbar.NavbarModule",
        }, "Module entries differ from the integrated window and Telecom backends"
        assert not any(name.endswith((".keystore", ".jks", ".p12", ".pfx"))
                       for name in archive.namelist()), "Signing material in APK"
    signature = run(jdk / "bin/java.exe", "-jar", tools / "lib/apksigner.jar",
                    "verify", "--print-certs", apk)
    assert re.search(r"certificate SHA-256 digest: (\w+)", signature)[1] == CERTIFICATE
    badging = run(tools / "aapt2.exe", "dump", "badging", apk).splitlines()[0]
    for expected in ("name='dev.nativecomplements.module'", f"versionCode='{code}'",
                     f"versionName='{version}'"):
        assert expected in badging, badging
    manifest = run(tools / "aapt2.exe", "dump", "xmltree", apk,
                   "--file", "AndroidManifest.xml")
    assert "BIND_ACCESSIBILITY_SERVICE" not in manifest
    target = ROOT / f"deliverables/NativeComplements-{version}.apk"
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    print(badging)
    print(f"APK={target}")
    print(f"SHA256={digest}")
    print("Original signing certificate and three fixed system scopes verified")


if __name__ == "__main__":
    main()
