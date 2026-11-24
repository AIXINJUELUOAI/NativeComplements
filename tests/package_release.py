"""Build and verify the integrated main APK; never install, reboot or make backups."""
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
SYSTEM_SCOPES = {"system", "com.google.android.apps.nexuslauncher", "com.android.launcher3",
                 "com.android.systemui", "com.android.settings", "com.android.phone"}
ENTRIES = {"dev.local.sunwindowdemo." + name for name in (
    "SunWindowModule", "LauncherGuard", "BackBridge", "features.callblocking.CallBlockingModule", "features.smsblocking.SmsBlockingModule",
    "features.iconlayers.IconLayerModule", "features.iconlayers.SystemIconModule", "features.iconlayers.SettingsIconModule", "features.navbar.NavbarModule")}


def check_scopes(text):
    scopes = text.splitlines()
    assert len(scopes) == len(SYSTEM_SCOPES) and set(scopes) == SYSTEM_SCOPES, "Expected exactly six system scopes"


def run(*args):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", check=True)
    return result.stdout + result.stderr


def main():
    check_scopes(SCOPE.read_text(encoding="utf-8"))
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert re.search(r"isMinifyEnabled\s*=\s*true", gradle), "Release must enable R8"
    assert 'create("window")' not in gradle, "Retired standalone product must not be built"
    version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    code = int(re.search(r'versionCode = (\d+)', gradle)[1])
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or str(Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Android/Sdk"))
    tools = sdk / "build-tools/36.1.0"
    jdk_env = os.environ.get("JAVA_HOME")
    jdk = Path(jdk_env) if jdk_env else None
    env = os.environ.copy()
    if jdk:
        env["JAVA_HOME"] = str(jdk)
    windows = os.name == "nt"
    gradlew = ROOT / ("gradlew.bat" if windows else "gradlew")
    java = (jdk / "bin" / ("java.exe" if windows else "java")) if jdk else Path(shutil.which("java") or "java")
    aapt2 = tools / ("aapt2.exe" if windows else "aapt2")
    subprocess.run([str(gradlew), "--no-daemon", "--console=plain",
                    ":app:assembleFullRelease"], cwd=ROOT, env=env, check=True)
    package = "dev.nativecomplements.module"
    apk = ROOT / "app/build/outputs/apk/full/release/app-full-release.apk"
    mapping = ROOT / "app/build/outputs/mapping/fullRelease/mapping.txt"
    assert mapping.exists(), "R8 mapping missing"
    mapping_text = mapping.read_text(encoding="utf-8")
    assert "# compiler: R8" in mapping_text, "R8 mapping missing"
    check_r8(apk)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert archive.testzip() is None, "APK CRC failure"
        assert "assets/navbar/templates.json" not in names, "Removed navigation templates asset in APK"
        check_scopes(archive.read("META-INF/xposed/scope.list").decode("utf-8"))
        assert "staticScope=true" in archive.read("META-INF/xposed/module.prop").decode().splitlines()
        entries = archive.read("META-INF/xposed/java_init.list").decode().splitlines()
        assert len(entries) == len(ENTRIES) and set(entries) == ENTRIES, entries
        assert not any(name.endswith((".keystore", ".jks", ".p12", ".pfx")) for name in names)
        assert not any(name.endswith("libalive_clean.so") or name.startswith(("assets/masks/", "assets/shader/", "assets/editor/")) for name in names), "Restricted Alive runtime/resources must remain user-imported"
        for notice in ("LICENSE", "NOTICE.txt"):
            path = "assets/licenses/openalive/" + notice
            assert archive.read(path) == (ROOT / "app/src/full" / path).read_bytes(), "Missing or changed OpenAlive notice: " + notice
        assert archive.read("assets/licenses/openalive/LICENSE") == (ROOT / "app/src/full/java/org/aliveclean/LICENSE").read_bytes(), "Keep complete OpenAlive MIT license with adapted code"
        required_types = {"Ldev/local/sunwindowdemo/" + name + ";" for name in (
            "CornerGesture", "FanView", "AndroidWindowAdmission", "WindowAdmission",
            "SwipeMini", "DesktopDoubleTap", "DesktopSleepHost")}
        required_types |= {"Ldev/local/sunwindowdemo/" + name + ";" for name in ("GameModeHost", "GameCornerOverlay", "SuggestionEnvironment", "SuggestionInput")}
        required_types |= {"Ldev/local/sunwindowdemo/features/gamemode/" + name + ";" for name in ("GameModeStore", "GameCornerGeometry", "GameHandleGeometry", "GameHandlePositionSettingsKt")}
        required_types |= {"Ldev/local/sunwindowdemo/features/edgetouch/" + name + ";" for name in ("EdgeInputFilter", "EdgeInputPolicy", "GameCornerAccess")}
        required_types |= {"Ldev/local/sunwindowdemo/features/smsblocking/" + name + ";" for name in (
            "SmsBlockingModule", "SmsBlockingProvider", "SmsBlockingStore", "SmsBlockingRules")}
        required_types |= {"Ldev/local/sunwindowdemo/features/diagnostics/" + name + ";" for name in (
            "DiagnosticsCapture", "DiagnosticsRecordingService")}
        required_types |= {"Ldev/local/sunwindowdemo/features/alive/" + name + ";" for name in (
            "AliveWallpaperService", "AliveAmbientWallpaperService", "AliveBootReceiver", "AliveSettings",
            "AliveAssets", "AliveAmbientAccess", "AliveWallpaperAccess", "AliveSystemUi", "AliveSleepHandoff", "AliveHandoffTransport")}
        required_types |= {"Lorg/aliveclean/" + name + ";" for name in ("AliveRenderer", "NativeScene")}
        required_types |= {"Ldev/local/sunwindowdemo/features/iconlayers/" + name + ";" for name in (
            "SettingsIconModule", "SettingsIconAccess", "NotificationCardIcons")}
        found = set()
        for name in names:
            if not name.endswith(".dex"):
                continue
            data = dex.Dex(archive.read(name))
            owners = {data.types[data.u32(data.u32(100) + 32 * i)] for i in range(data.u32(96))}
            assert "Ldev/local/sunwindowdemo/WindowAccessibilityService;" not in owners, "Retired accessibility entry in DEX"
            assert not any(t.startswith("Ldev/local/sunwindowdemo/features/ltpo/") or
                           t.startswith("Ldev/local/sunwindowdemo/RefreshRateBadge") for t in owners), "Retired LTPO code in DEX"
            found |= owners & required_types
        assert found == required_types, "Integrated corner/admission runtime missing after R8: " + str(required_types - found)
        assert any(b'dev.local.sunwindowdemo.core.WindowBackendEligibility' in archive.read(name)
                   for name in names if name.endswith('.dex')), "Preserve own eligibility marker for old installations"
    signature = run(java, "-jar", tools / "lib/apksigner.jar", "verify", "--print-certs", apk)
    assert re.search(r"certificate SHA-256 digest: (\w+)", signature)[1] == CERTIFICATE
    badging = run(aapt2, "dump", "badging", apk).splitlines()[0]
    for expected in (f"name='{package}'", f"versionCode='{code}'", f"versionName='{version}'"):
        assert expected in badging, badging
    manifest = run(aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml")
    assert "WindowAccessibilityService" not in manifest and "android.accessibilityservice.AccessibilityService" not in manifest
    assert package + ".ltpo" not in manifest, "Retired LTPO configuration provider in manifest"
    recording_service = next((block for block in re.split(r'(?m)^\s*E:', manifest)
                              if block.lstrip().startswith('service') and 'DiagnosticsRecordingService' in block), '')
    assert recording_service and re.search(r'exported[^\n]*=false', recording_service), "Recording service must stay private"
    assert "android.permission.FOREGROUND_SERVICE" in manifest and "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest
    assert "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest, "Explain manual log recording foreground use"
    for authority in ("CONTROL", "settings", "iconlayers", "diagnostics", "smsblocking"):
        assert package + "." + authority in manifest
    eligibility = next((block for block in re.split(r'(?m)^\s*E:', manifest)
                        if block.lstrip().startswith('receiver') and 'WindowBackendEligibility' in block), '')
    assert re.search(r'enabled[^\n]*=true', eligibility) and re.search(r'exported[^\n]*=false', eligibility), "Missing own eligibility migration receiver"
    assert 'android.intent.action.BOOT_COMPLETED' in manifest and 'android.intent.action.MY_PACKAGE_REPLACED' in manifest
    marker = next((block for block in re.split(r'(?m)^\s*E:', manifest)
                   if block.lstrip().startswith('receiver') and 'WindowBackendSelection' in block), '')
    assert re.search(r'enabled[^\n]*=false', marker) and re.search(r'exported[^\n]*=false', marker), "Preserve private legacy backend marker"
    for component in ("AliveWallpaperService", "AliveAmbientWallpaperService"):
        service = next((block for block in re.split(r'(?m)^\s*E:', manifest)
                        if block.lstrip().startswith('service') and 'features.alive.' + component in block), '')
        assert re.search(r'directBootAware[^\n]*=true', service), component + " must support prepared DE data"
        assert re.search(r'exported[^\n]*=true', service) and 'android.permission.BIND_WALLPAPER' in service, "Only platform wallpaper binding may reach " + component
    boot = next((block for block in re.split(r'(?m)^\s*E:', manifest)
                 if block.lstrip().startswith('receiver') and 'features.alive.AliveBootReceiver' in block), '')
    assert re.search(r'exported[^\n]*=false', boot) and not re.search(r'directBootAware[^\n]*=true', boot), "Alive migration receiver must remain private and post-unlock"
    assert "android.permission.AMBIENT_WALLPAPER" not in manifest
    assert "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE" not in manifest
    resources = run(aapt2, "dump", "resources", apk)
    for metadata, ambient in (("alive_wallpaper", False), ("alive_ambient_wallpaper", True)):
        entry = re.search(r'(?m)^\s*resource (0x[0-9a-f]+) xml/' + re.escape(metadata)
                          + r'\s*\n\s*\(\) \(file\) (\S+) type=XML', resources)
        assert entry, "Missing Alive wallpaper metadata"
        # Resource optimization shortens APK paths; resolve from the actual resource table.
        tree = run(aapt2, "dump", "xmltree", apk, "--file", entry.group(2))
        assert re.search(r'supportsAmbientMode[^\n]*=' + str(ambient).lower(), tree), "Alive ambient metadata must stay truthful"
    assert "xml/window_accessibility_service" not in resources
    assert "WindowShortcutActivity" not in manifest and "android.app.shortcuts" not in manifest, "Retired shortcut must remain absent"
    # Copy only after all identity, signature, runtime and product checks pass.
    target = ROOT / f"deliverables/NativeComplements-{version}.apk"
    target.parent.mkdir(exist_ok=True); shutil.copyfile(apk, target)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    print(badging); print(f"APK={target}"); print(f"SHA256={digest}")
    print("Original certificate, main identity, static scopes, entries, integrated window/Alive runtime, Direct Boot, licenses and retirement boundaries verified; device effects unverified")


if __name__ == "__main__":
    main()
