"""Build and verify same-source full/window APKs; never install, reboot or make backups."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile
from verify_r8_host_names import check as check_r8
import verify_signal_target as dex

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = "28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104"
SCOPE = ROOT / "app/src/main/resources/META-INF/xposed/scope.list"
SYSTEM_SCOPES = {"system", "com.google.android.apps.nexuslauncher", "com.android.systemui"}
BASE_ENTRIES = {"dev.local.sunwindowdemo." + name for name in ("SunWindowModule", "LauncherGuard", "BackBridge")}
ENTRIES = {
    "full": BASE_ENTRIES | {"dev.local.sunwindowdemo.features." + name for name in (
        "callblocking.CallBlockingModule", "iconlayers.IconLayerModule", "iconlayers.SystemIconModule", "navbar.NavbarModule")},
    "window": BASE_ENTRIES | {"dev.local.sunwindowdemo.features.iconlayers.LauncherThemeModule"},
}
WINDOW_FORBIDDEN = {"Ldev/local/sunwindowdemo/" + name + ";" for name in (
    "SuggestionHost", "SuggestionInput", "BarrageHost", "RotationSuggestion", "LauncherRotationSuggestion",
    "features/callblocking/CallBlockingModule", "features/navbar/NavbarModule", "features/iconlayers/IconLayerModule",
    "features/iconlayers/SystemIconModule", "features/iconlayers/DrawerMonet", "features/motion/MotionAccess",
    "features/edgetouch/EdgeInputFilter", "features/shade/LandscapeShade", "features/shade/StatusBarOffset", "features/shade/SignalDisplay",
    "features/alive/AliveWallpaperService", "features/alive/AliveAssets", "features/alive/AlivePhase")}



def check_scopes(text):
    scopes = text.splitlines()
    assert len(scopes) == len(SYSTEM_SCOPES) and set(scopes) == SYSTEM_SCOPES, "Expected exactly three system scopes"


def run(*args):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", check=True)
    return result.stdout + result.stderr


def main():
    check_scopes(SCOPE.read_text(encoding="utf-8"))
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert re.search(r"isMinifyEnabled\s*=\s*true", gradle), "Release must enable R8"
    version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    code = int(re.search(r'versionCode = (\d+)', gradle)[1])
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    tools = sdk / "build-tools/36.1.0"
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
    env = os.environ.copy(); env["JAVA_HOME"] = str(jdk)
    subprocess.run([str(ROOT / "gradlew.bat"), "--no-daemon", "--console=plain",
                    ":app:assembleFullRelease", ":app:assembleWindowRelease"], cwd=ROOT, env=env, check=True)
    verified = []
    for product in ("full", "window"):
        standalone = product == "window"
        package = "dev.nativecomplements.window" if standalone else "dev.nativecomplements.module"
        apk = ROOT / f"app/build/outputs/apk/{product}/release/app-{product}-release.apk"
        mapping = ROOT / f"app/build/outputs/mapping/{product}Release/mapping.txt"
        assert mapping.exists() and "# compiler: R8" in mapping.read_text(encoding="utf-8"), "R8 mapping missing"
        check_r8(apk, window=standalone)
        with zipfile.ZipFile(apk) as archive:
            assert archive.testzip() is None, "APK CRC failure"
            assert "assets/navbar/templates.json" not in archive.namelist(), "Removed navigation templates asset in APK"
            check_scopes(archive.read("META-INF/xposed/scope.list").decode("utf-8"))
            assert "staticScope=true" in archive.read("META-INF/xposed/module.prop").decode().splitlines()
            entries = archive.read("META-INF/xposed/java_init.list").decode().splitlines()
            assert len(entries) == len(ENTRIES[product]) and set(entries) == ENTRIES[product], (product, entries)
            assert not any(name.endswith((".keystore", ".jks", ".p12", ".pfx")) for name in archive.namelist())
            assert not any(name.endswith("libalive_clean.so") or name.startswith(("assets/masks/", "assets/shader/")) for name in archive.namelist()), "Official Alive resources must remain user-imported, not bundled"
            if not standalone:
                assert "assets/licenses/openalive/LICENSE" in archive.namelist()
                assert "assets/licenses/openalive/NOTICE.txt" in archive.namelist()
                alive_types = {"Lorg/aliveclean/NativeScene;", "Lorg/aliveclean/AliveRenderer;",
                               "Ldev/local/sunwindowdemo/features/alive/AliveWallpaperService;"}
                found = set()
                for name in archive.namelist():
                    if name.endswith(".dex"):
                        data = dex.Dex(archive.read(name))
                        owners = {data.types[data.u32(data.u32(100) + 32 * i)] for i in range(data.u32(96))}
                        found |= owners & alive_types
                assert found == alive_types, "Alive JNI/renderer/service missing after R8"
            if standalone:
                for name in archive.namelist():
                    if not name.endswith(".dex"): continue
                    data = dex.Dex(archive.read(name))
                    owners = {data.types[data.u32(data.u32(100) + 32 * i)] for i in range(data.u32(96))}
                    forbidden = {owner for owner in owners if owner.split("$", 1)[0].rstrip(";") + ";" in WINDOW_FORBIDDEN}
                    assert not forbidden, f"Non-window hooks in standalone DEX: {sorted(forbidden)}"
        signature = run(jdk / "bin/java.exe", "-jar", tools / "lib/apksigner.jar", "verify", "--print-certs", apk)
        assert re.search(r"certificate SHA-256 digest: (\w+)", signature)[1] == CERTIFICATE
        badging = run(tools / "aapt2.exe", "dump", "badging", apk).splitlines()[0]
        for expected in (f"name='{package}'", f"versionCode='{code}'", f"versionName='{version}'"):
            assert expected in badging, badging
        manifest = run(tools / "aapt2.exe", "dump", "xmltree", apk, "--file", "AndroidManifest.xml")
        assert "BIND_ACCESSIBILITY_SERVICE" not in manifest
        assert package + ".CONTROL" in manifest and package + ".settings" in manifest
        assert package + ".iconlayers" in manifest and package + ".diagnostics" in manifest
        if standalone:
            assert "dev.nativecomplements.module." not in manifest, "Standalone manifest aliases full-product identity"
            for feature in ("CallBlockingProvider", "MotionProvider", "EdgeTouchProvider", "ShadeProvider", "BarrageProvider", "SuggestionProvider", "NavbarProvider", "SuggestionSmsReceiver", "IconLayerProvider"):
                assert feature not in manifest, (product, feature)
            assert "WindowIconProvider" in manifest
            assert "AliveWallpaperService" not in manifest, "Alive must not enter the standalone window product"
        else:
            assert "AliveWallpaperService" in manifest and "android.permission.BIND_WALLPAPER" in manifest
            assert "android.permission.AMBIENT_WALLPAPER" not in manifest
            assert "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE" not in manifest
        resources = run(tools / "aapt2.exe", "dump", "resources", apk)
        shortcut_path = re.search(r"resource [^\n]+ xml/shortcuts\s+\(\) \(file\) (\S+)", resources)
        assert shortcut_path, "Missing compiled static shortcut resource"
        shortcut = run(tools / "aapt2.exe", "dump", "xmltree", apk, "--file", shortcut_path[1])
        assert package + ".FLOAT_CURRENT" in shortcut and package in shortcut
        target = ROOT / f"deliverables/NativeComplements{'-Window' if standalone else ''}-{version}.apk"
        verified.append((apk, target, badging))
    # Publish neither deliverable until both products pass every check.
    for apk, target, badging in verified:
        target.parent.mkdir(exist_ok=True); shutil.copyfile(apk, target)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
        print(badging); print(f"APK={target}"); print(f"SHA256={digest}")
    print("Both original certificates, isolated identities, static scopes, entries and window-only Hook boundary verified; device effects unverified")


if __name__ == "__main__":
    main()
