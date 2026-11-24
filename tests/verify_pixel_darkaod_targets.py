"""Exact DarkAod archived ABI and invocation checks, not ART/device acceptance.

Python stdlib only; missing archives exit 2 (SKIP), missing targets exit 1.
Default is dada; --host pixelui checks QPR2, --host all checks both.
"""
from pathlib import Path
import hashlib
import re
import sys
import verify_test4_target as archive_reader

ROOT = Path(__file__).resolve().parents[1]
S = "Lcom/android/systemui/"
A = "Landroid/"
CHECKS = []


def checks(owner, methods=(), fields=(), inherited=False):
    for name, sig in methods:
        CHECKS.append((owner, "methods", name, sig, inherited))
    for name, sig in fields:
        CHECKS.append((owner, "fields", name, sig, inherited))


MACHINE = S+"doze/DozeMachine;"
STATE = S+"doze/DozeMachine$State;"
HOST = S+"statusbar/phone/DozeServiceHost;"
PROX = S+"util/sensors/ProximitySensorImpl;"
checks(S+"doze/DozeService;", [("onCreate", "()V"), ("onDestroy", "()V")], [("mDozeMachine", MACHINE)], True)
checks(MACHINE, [("transitionTo", "("+STATE+"I)V"), ("requestState", "("+STATE+")V")],
       [("mState", STATE), ("mParts", "["+S+"doze/DozeMachine$Part;"), ("mQueuedRequests", "Ljava/util/ArrayList;"), ("mUserTracker", S+"settings/UserTracker;"),
        ("mAmbientDisplayConfig", "Landroid/hardware/display/AmbientDisplayConfiguration;"), ("mDozeHost", HOST)], True)
checks(HOST, fields=[("mAlwaysOnSuppressed", "Z"), ("mBatteryController", S+"statusbar/policy/BatteryControllerImpl;")], inherited=True)
checks(S+"statusbar/policy/BatteryControllerImpl;", fields=[("mAodPowerSave", "Z")], inherited=True)
checks(S+"settings/UserTrackerImpl;", [("getUserId", "()I")], inherited=True)
checks(A+"hardware/display/AmbientDisplayConfiguration;", [("alwaysOnEnabled", "(I)Z")], inherited=True)
checks(S+"doze/DozeTriggers;", fields=[("mDozeSensors", S+"doze/DozeSensors;")], inherited=True)
checks(S+"doze/DozeSensors;", fields=[("mProximitySensor", PROX)], inherited=True)
checks(PROX, [("isNear", "()Ljava/lang/Boolean;"), ("alertListeners", "()V"),
              ("onSensorEvent", "("+S+"util/sensors/ThresholdSensorEvent;)V")],
       [("mRegistered", "Z"), ("mPaused", "Z")], True)
checks(STATE, fields=[(n, STATE) for n in ("DOZE_AOD", "DOZE_AOD_PAUSING", "DOZE_AOD_PAUSED")])

# Type-only reflection targets remain part of the DarkAod contract.
DARK_SOURCE = ROOT/"app/src/main/java/dev/local/sunwindowdemo/features/darkaod"
SOURCES = (DARK_SOURCE/"DarkAodAccess.java", DARK_SOURCE/"DarkAodHost.java")
REQUIRED_CLASSES = {
    "L"+name.replace(".", "/")+";"
    for source in SOURCES
    for name in re.findall(r'(?:type\(loader,|Class\.forName\()"([\w.$]+)"', source.read_text(encoding="utf-8"))
}

HOSTS = {
    "PixelOS(dada)": ("verification/navbar-system/framework.jar", "verification/navbar-system/services.jar", "verification/split-shade-device/SystemUI.apk", "verification/navbar-system/NexusLauncher.apk"),
    "PixelUI": ("verification/pixel-qpr2-20261113/framework.jar", "verification/pixel-qpr2-20261113/services.jar", "verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk", "verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk"),
}


def members(classes, owner, kind, inherited):
    seen = set()
    while owner in classes and owner not in seen:
        seen.add(owner)
        yield from classes[owner][kind]
        if not inherited:
            break
        owner = classes[owner]["superclass"]


HASHES = {
    'verification/navbar-system/framework.jar': '89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f',
    'verification/navbar-system/services.jar': '3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9',
    'verification/split-shade-device/SystemUI.apk': 'a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972',
    'verification/navbar-system/NexusLauncher.apk': '3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56',
    'verification/pixel-qpr2-20261113/framework.jar': '466ba9aa9934aa5bc113f9ee738e3fd66cf92b0bb3d02a144d0669b59381015f',
    'verification/pixel-qpr2-20261113/services.jar': '382ed5d6b0e9ca464878ac6c87b655ab7f6009fd8463949aa43c23b738dbb7db',
    'verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk': '458b481b241552d67d33963f3cb449a9e5e53d4cfcd28e8bb1d919b814fd982f',
    'verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk': '109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c',
}


def required_classes(qpr2):
    return set(REQUIRED_CLASSES)


def host_checks(qpr2):
    result = []
    for owner, kind, name, sig, inherited in CHECKS:
        if owner == S+"doze/DozeSensors;" and name == "mProximitySensor" and not qpr2:
            sig = S+"util/sensors/ProximitySensor;"
        result.append((owner, kind, name, sig, inherited))
    return result


def read_with_code(path, targets):
    # Reuse the existing DEX041 header/container iteration, retaining its parser
    # objects as well as classes for exact instruction-boundary call evidence.
    parser = archive_reader.dex.Dex
    dexes = []
    def capture(data):
        value = parser(data)
        dexes.append(value)
        return value
    archive_reader.TARGETS = targets
    archive_reader.dex.Dex = capture
    try:
        return archive_reader.read_archive(path), dexes
    finally:
        archive_reader.dex.Dex = parser


def instructions(dexes, owner, name, signature):
    matches = [d for d in dexes if (owner, name, signature) in d.code]
    assert len(matches) == 1, ("missing/duplicate code", owner, name, signature)
    return matches[0].instructions(owner, name, signature)


def code_checks(dexes, qpr2):
    required = [
        (MACHINE, "requestState", "("+STATE+"I)V", "method", MACHINE, "transitionTo", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "field", MACHINE, "mParts", "["+S+"doze/DozeMachine$Part;"),
        (MACHINE, "requestState", "("+STATE+")V", "method", MACHINE, "requestState", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", MACHINE, "transitionTo", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", S+"settings/UserTrackerImpl;", "getUserId", "()I"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", A+"hardware/display/AmbientDisplayConfiguration;", "alwaysOnEnabled", "(I)Z"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", S+"doze/DozeMachine$Part;", "transitionTo", "("+STATE+STATE+")V"),
        (S+"doze/DozeService;", "onCreate", "()V", "field", S+"doze/DozeService;", "mDozeMachine", MACHINE),
        (S+"doze/DozeService;", "onDestroy", "()V", "field", S+"doze/DozeService;", "mDozeMachine", MACHINE),
    ]
    failures = []
    for owner, name, sig, kind, target, member, descriptor in required:
        try:
            stream = instructions(dexes, owner, name, sig)
            key = "type" if kind == "field" else "descriptor"
            assert any(i.get(kind, {}).get("owner") == target and i[kind]["name"] == member and i[kind][key] == descriptor for i in stream)
        except (AssertionError, KeyError) as error:
            failures.append((owner, name, target, member, str(error)))
    return failures, len(required)


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=("dada", "pixelui", "all"), default="dada")
    selected = parser.parse_args().host
    hosts = {label: paths for label, paths in HOSTS.items()
             if selected == "all" or (label == "PixelUI") == (selected == "pixelui")}
    missing = [p for files in hosts.values() for p in files if not (ROOT/p).is_file()]
    if missing:
        print("SKIP missing archives:", *missing, sep="\n")
        return 2
    failed = False
    for label, paths in hosts.items():
        for path in paths:
            if hashlib.sha256((ROOT/path).read_bytes()).hexdigest() != HASHES[path]:
                print("FAIL SHA256", label, path)
                return 1
        expected = host_checks(label == "PixelUI")
        required = required_classes(label == "PixelUI")
        targets = {c[0] for c in expected} | required
        classes, dexes = {}, []
        for path in paths[:3]:
            current, parsed = read_with_code(ROOT/path, targets)
            dexes.extend(parsed)
            for owner, body in current.items():
                if path.endswith(".apk") and owner.startswith(A):
                    continue  # Android definitions must come from boot framework, not APK stubs.
                classes.setdefault(owner, body)
        failures = []
        missing_classes = required-classes.keys()
        for owner in sorted(missing_classes):
            print("FAIL CLASS", label, owner)
        failed |= bool(missing_classes)
        for owner, kind, name, sig, inherited in expected:
            key = "descriptor" if kind == "methods" else "type"
            rows = list(members(classes, owner, kind, inherited))
            if not any(m["name"] == name and m[key] == sig for m in rows):
                failures.append((owner, name, sig))
                print("FAIL", label, owner, name, sig, "actual:", [m[key] for m in rows if m["name"] == name])
        call_failures, call_count = code_checks(dexes, label == "PixelUI")
        for failure in call_failures:
            print("FAIL CALL", label, *failure)
        failed |= bool(failures or call_failures)
        print("ABI", label, len(expected)-len(failures), "/", len(expected), "exact targets")
        print("CALLS", label, call_count-len(call_failures), "/", call_count, "exact DEX invocation/field paths")
        print("CLASSES", label, len(required)-len(missing_classes), "/", len(required), "literal reflection class targets")
        if not failures and not call_failures and not missing_classes:
            print("PASS", label, "complete enumerated DarkAod reflection contract")
    print("LIMIT: archived ABI only; no ART install, sensor/display/visual/power/recovery acceptance")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
