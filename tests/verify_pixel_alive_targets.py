"""Exact Alive/DarkAod archived ABI and invocation checks, not ART/device acceptance.

Python stdlib only; missing archives exit 2 (SKIP), missing targets exit 1.
All reflective host targets in Alive (including optional visual/trace hooks) and
DarkAodAccess are enumerated, not a sample. Default is dada; --host pixelui checks the
QPR2 shapes the source resolves (see QPR2), --host all checks both.
Launcher has no Alive/DarkAod hook (its supplied archive identity is checked).
"""
from pathlib import Path
import hashlib
import re
import sys
import verify_test4_target as archive_reader

ROOT = Path(__file__).resolve().parents[1]
S = "Lcom/android/systemui/"
A = "Landroid/"
K = "Lkotlinx/coroutines/"
CHECKS = []


def checks(owner, methods=(), fields=(), inherited=False):
    for name, sig in methods:
        CHECKS.append((owner, "methods", name, sig, inherited))
    for name, sig in fields:
        CHECKS.append((owner, "fields", name, sig, inherited))


WMS = "Lcom/android/server/wallpaper/WallpaperManagerService;"
BIND = "(Landroid/app/wallpaper/WallpaperDescription;ZZLcom/android/server/wallpaper/WallpaperData;Landroid/os/IRemoteCallback;)Z"
checks(WMS, [("bindWallpaperDescriptionLocked", BIND)])
checks("Lcom/android/server/wallpaper/WallpaperData;", fields=[("userId", "I")])
checks(A+"app/wallpaper/WallpaperDescription;", [("getComponent", "()Landroid/content/ComponentName;")])
checks(A+"app/WallpaperInfo;", [("getComponent", "()Landroid/content/ComponentName;"), ("supportsAmbientMode", "()Z")])
checks(A+"app/WallpaperManager;", [("getWallpaperInfo", "(II)Landroid/app/WallpaperInfo;"), ("getWallpaperIdForUser", "(II)I")])
checks(A+"content/pm/PackageManager;", [("getApplicationInfoAsUser", "(Ljava/lang/String;II)Landroid/content/pm/ApplicationInfo;")])
checks(A+"content/pm/ApplicationInfo;", fields=[("uid", "I")])
checks(A+"content/pm/UserInfo;", fields=[("id", "I")])
checks(A+"os/SystemProperties;", [("get", "(Ljava/lang/String;)Ljava/lang/String;")])
checks(A+"view/ViewGroup;", [("drawChild", "(Landroid/graphics/Canvas;Landroid/view/View;J)Z")])
checks(A+"app/BroadcastOptions;", [("makeBasic", "()Landroid/app/BroadcastOptions;"), ("setShareIdentityEnabled", "(Z)Landroid/app/BroadcastOptions;")])
checks(A+"content/BroadcastReceiver;", [("getSentFromUid", "()I"), ("getSentFromPackage", "()Ljava/lang/String;")])
THEME = S+"theme/ThemeOverlayController;"
COLORS_LISTENER = S+"theme/ThemeOverlayController$2;"
COLORS_CALLBACK = "(Landroid/app/WallpaperColors;II)V"
checks(THEME, fields=[("mOnColorsChangedListener", COLORS_LISTENER), ("mAcceptColorEvents", "Z"),
                      ("mUserTracker", S+"settings/UserTracker;")])
checks(COLORS_LISTENER, [("onColorsChanged", COLORS_CALLBACK)], [("this$0", THEME)])
checks(S+"settings/UserTrackerImpl;", [("getUserId", "()I")])
REPO = S+"wallpapers/data/repository/WallpaperRepositoryImpl;"
FLOW = K+"flow/Flow;"
READONLY = K+"flow/ReadonlyStateFlow;"
checks(REPO, [("<init>", "("+K+"CoroutineScope;"+K+"CoroutineDispatcher;"+S+"broadcast/BroadcastDispatcher;"+S+"user/data/repository/UserRepository;Landroid/app/WallpaperManager;Landroid/content/Context;"+S+"util/settings/SecureSettings;"+S+"common/ui/domain/interactor/ConfigurationInteractorImpl;)V")],
       [("wallpaperSupportsAmbientMode", FLOW), ("lockscreenWallpaperInfo", READONLY), ("wallpaperInfo", READONLY),
        ("secureSettings", S+"util/settings/SecureSettings;"), ("wallpaperManager", A+"app/WallpaperManager;"), ("bgDispatcher", K+"CoroutineDispatcher;")])
checks(S+"user/data/repository/UserRepositoryImpl;", fields=[("selectedUser", READONLY)])
checks(S+"user/data/model/SelectedUserModel;", fields=[("userInfo", A+"content/pm/UserInfo;"), ("selectionStatus", S+"user/data/model/SelectionStatus;")])
checks(S+"user/data/model/SelectionStatus;", fields=[("SELECTION_COMPLETE", S+"user/data/model/SelectionStatus;")])
checks(K+"flow/StateFlow;", [("getValue", "()Ljava/lang/Object;")], inherited=True)
checks(K+"flow/FlowKt;", [("combine", "("+FLOW*4+"Lkotlin/jvm/functions/Function5;)"+FLOW), ("flowOn", "("+FLOW+"Lkotlin/coroutines/CoroutineContext;)"+FLOW)])
checks("Lkotlin/jvm/functions/Function5;", [("invoke", "("+"Ljava/lang/Object;"*5+")Ljava/lang/Object;")])
checks(S+"util/settings/UserSettingsProxy;", [("getIntForUser", "(IILjava/lang/String;)I")], inherited=True)
DEPTH = S+"statusbar/NotificationShadeDepthController;"
INTERACTOR = S+"wallpapers/domain/interactor/WallpaperInteractor;"
CALLBACK = S+"statusbar/NotificationShadeDepthController$statusBarStateCallback$1;"
COLLECTOR = S+"statusbar/NotificationShadeDepthController$2$1;"
EMIT = "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
checks(DEPTH, [("setWakeAndUnlockBlurRadius", "(F)V")], [("wallpaperInteractor", INTERACTOR), ("wallpaperSupportsAmbientMode", "Z"), ("prevDozeAmount", "F")])
checks(INTERACTOR, fields=[("wallpaperRepository", REPO)])
checks(CALLBACK, [("onDozeAmountChanged", "(FF)V")], [("this$0", DEPTH)])
checks(COLLECTOR, [("emit", EMIT)], [("this$0", DEPTH), ("$r8$classId", "I")])
WINDOW = S+"shade/NotificationShadeWindowControllerImpl;"
WINDOW_STATE = S+"shade/NotificationShadeWindowState;"
checks(WINDOW, [("applyWindowLayoutParams", "()V"), ("apply", "("+WINDOW_STATE+")V")],
       [("mWindowRootView", S+"scene/ui/view/WindowRootView;"), ("mLpChanged", A+"view/WindowManager$LayoutParams;"), ("mCurrentState", WINDOW_STATE),
        ("mKeyguardStateController", S+"statusbar/policy/KeyguardStateController;"), ("mScrimsVisibilityListener", S+"statusbar/NotificationShadeDepthController$1;")])
checks(S+"statusbar/NotificationShadeDepthController$1;", fields=[("this$0", DEPTH)])
checks(WINDOW_STATE, fields=[(x, "Z") for x in ("keyguardShowing", "keyguardOccluded", "bouncerShowing", "keyguardGoingAway")])
checks(S+"statusbar/policy/KeyguardStateControllerImpl;", fields=[(x, "Z") for x in ("mShowing", "mOccluded", "mKeyguardGoingAway", "mKeyguardFadingAway", "mPrimaryBouncerShowing")])
SCRIM = S+"statusbar/LightRevealScrim;"
checks(SCRIM, [("onDraw", "(Landroid/graphics/Canvas;)V"), ("setRevealEffect", "("+S+"statusbar/LightRevealEffect;)V"), ("setRevealAmount", "(F)V"), ("setAlpha", "(F)V")], [("revealAmount", "F"), ("revealEffect", S+"statusbar/LightRevealEffect;")])
checks(S+"keyguard/KeyguardService$2;", [("onStartedGoingToSleep", "(I)V"), ("onStartedWakingUp", "(IZ)V")])
SCREENOFF = S+"statusbar/phone/ScreenOffAnimationController;"
UNLOCKED = S+"statusbar/phone/UnlockedScreenOffAnimationController;"
checks(SCREENOFF, [("onStartedGoingToSleep", "()V")], [("animations", "Ljava/util/List;")])
checks(UNLOCKED, [("startAnimation", "()Z"), ("shouldPlayUnlockedScreenOffAnimation", "()Z")],
       [("notifShadeWindowControllerLazy", "Ldagger/Lazy;"), ("lightRevealScrim", SCRIM), ("lightRevealAnimationPlaying", "Z"), ("lightRevealAnimator", A+"animation/ValueAnimator;"), ("decidedToAnimateGoingToSleep", "Ljava/lang/Boolean;")])
checks("Ldagger/Lazy;", [("get", "()Ljava/lang/Object;")])
checks(S+"biometrics/AuthRippleView;", [("onDraw", "(Landroid/graphics/Canvas;)V")])
CLOCK = "Lcom/android/keyguard/ClockEventController;"
CLOCK_CONTROLLER = S+"plugins/keyguard/ui/clocks/ClockController;"
FACE = S+"plugins/keyguard/ui/clocks/ClockFaceController;"
checks(CLOCK, [("access$handleDoze", "("+CLOCK+"F)V"), ("setClock", "("+CLOCK_CONTROLLER+")V")], [("isPreview", "Z"), ("clock", CLOCK_CONTROLLER), ("smallClockFrame", A+"view/ViewGroup;")])
checks(CLOCK_CONTROLLER, [("getSmallClock", "()"+FACE), ("getLargeClock", "()"+FACE)])
checks(FACE, [("getView", "()Landroid/view/View;")])
NOTIFICATIONS = S+"statusbar/notification/stack/NotificationStackScrollLayoutController;"
checks(NOTIFICATIONS, [("updateAlpha", "()V"), ("setMaxAlphaForKeyguard", "(Ljava/lang/String;F)V")], [("mView", S+"statusbar/notification/stack/NotificationStackScrollLayout;")])
checks(S+"keyguard/domain/interactor/KeyguardTransitionInteractor$1$1;", [("emit", EMIT)], [("$r8$classId", "I")])
checks(S+"keyguard/shared/model/TransitionStep;", fields=[("from", S+"keyguard/shared/model/KeyguardState;"), ("to", S+"keyguard/shared/model/KeyguardState;"), ("value", "F"), ("transitionState", S+"keyguard/shared/model/TransitionState;")])
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
checks(PROX, [("isNear", "()Ljava/lang/Boolean;")], [("mRegistered", "Z"), ("mPaused", "Z")], True)
checks(STATE, fields=[(n, STATE) for n in ("DOZE_AOD", "DOZE_AOD_PAUSING", "DOZE_AOD_PAUSED")])

# Class.forName/type-only host lookups also fail the contract when absent, even
# where no reflected member is read (scope, continuation, interfaces, enums).
ALIVE_SOURCE = ROOT/"app/src/full/java/dev/local/sunwindowdemo/features/alive"
DARK_SOURCE = ROOT/"app/src/main/java/dev/local/sunwindowdemo/features/darkaod"
SOURCES = tuple(ALIVE_SOURCE.glob("*.java")) + (DARK_SOURCE/"DarkAodAccess.java", DARK_SOURCE/"DarkAodHost.java")
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


COMBINED = K+"flow/FlowKt__ZipKt$combine$$inlined$combineUnsafe$FlowKt__ZipKt$1;"
# PixelUI QPR2 shapes the source resolves after the older exact member is absent.
# None means the source treats the member as optional and keeps an equivalent guard.
QPR2 = {
    (REPO, "lockscreenWallpaperInfo"): (REPO, "fields", "wallpaperChanged", K+"flow/FlowKt__EmittersKt$onStart$$inlined$unsafeFlow$1;"),
    (REPO, "wallpaperInfo"): (REPO, "fields", "wallpaperInfo", K+"flow/StateFlow;"),
    (S+"user/data/repository/UserRepositoryImpl;", "selectedUser"): (S+"user/data/repository/UserRepositoryImpl;", "fields", "selectedUser", K+"flow/StateFlow;"),
    (K+"flow/FlowKt;", "combine"): (K+"flow/FlowKt__ZipKt;", "methods", "combine", "("+FLOW*4+"Lkotlin/jvm/functions/Function5;)"+COMBINED),
    (K+"flow/FlowKt;", "flowOn"): (K+"flow/FlowKt__ContextKt;", "methods", "flowOn", "("+FLOW+"Lkotlin/coroutines/CoroutineContext;)"+FLOW),
    (S+"util/settings/UserSettingsProxy;", "getIntForUser"): (S+"util/settings/UserSettingsProxy;", "methods", "getIntForUser", "(ILjava/lang/String;I)I"),
    # No setter: the field is written only, so Alive skips the doze-blur projection.
    (DEPTH, "setWakeAndUnlockBlurRadius"): (DEPTH, "fields", "wakeAndUnlockBlurRadius", "F"),
    (COLLECTOR, "$r8$classId"): (COLLECTOR, "fields", "$r8$classId", "B"),
    (S+"keyguard/domain/interactor/KeyguardTransitionInteractor$1$1;", "$r8$classId"): (S+"keyguard/domain/interactor/KeyguardTransitionInteractor$1$1;", "fields", "$r8$classId", "B"),
    (S+"statusbar/policy/KeyguardStateControllerImpl;", "mPrimaryBouncerShowing"): None,
    (UNLOCKED, "notifShadeWindowControllerLazy"): None,
    (CLOCK, "access$handleDoze"): (CLOCK, "methods", "handleDoze", "(F)V"),
    (NOTIFICATIONS, "setMaxAlphaForKeyguard"): (NOTIFICATIONS, "fields", "mMaxAlphaForKeyguard", "F"),
}
QPR2_CLASSES = {K+"flow/FlowKt;": {K+"flow/FlowKt__ZipKt;", K+"flow/FlowKt__ContextKt;"}}


def required_classes(qpr2):
    fallbacks = set().union(*QPR2_CLASSES.values())
    if not qpr2:
        return set(REQUIRED_CLASSES) - fallbacks
    result = set()
    for owner in REQUIRED_CLASSES - fallbacks:
        result |= QPR2_CLASSES.get(owner, {owner})
    return result


def host_checks(qpr2):
    """Current source contract: the older exact shape, or the QPR2 shape the source resolves.

    Only DEX covariance and fields whose Java access deliberately accepts the
    exact concrete/interface type are normalized; absent targets remain FAIL.
    """
    result = []
    for owner, kind, name, sig, inherited in CHECKS:
        if qpr2 and (owner, name) in QPR2:
            replacement = QPR2[(owner, name)]
            if replacement is not None:
                result.append((*replacement, inherited))
            continue
        if owner == K+"flow/FlowKt;" and name == "combine":
            sig = sig[:sig.index(")")+1]+COMBINED
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
        (WMS, "bindWallpaperDescriptionLocked", BIND, "method", A+"app/WallpaperInfo;", "supportsAmbientMode", "()Z"),
        (UNLOCKED, "startAnimation", "()Z", "method", UNLOCKED, "shouldPlayUnlockedScreenOffAnimation", "()Z"),
        (MACHINE, "requestState", "("+STATE+"I)V", "method", MACHINE, "transitionTo", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "field", MACHINE, "mParts", "["+S+"doze/DozeMachine$Part;"),
        (NOTIFICATIONS, "updateAlpha", "()V", "field", NOTIFICATIONS, "mView", S+"statusbar/notification/stack/NotificationStackScrollLayout;"),
    ]
    constructor = next(sig for owner, kind, name, sig, inherited in CHECKS if owner == REPO and name == "<init>")
    operators = K+("flow/FlowKt__ContextKt;" if qpr2 else "flow/FlowKt;")
    clock_method, clock_sig = ("handleDoze", "(F)V") if qpr2 else ("access$handleDoze", "("+CLOCK+"F)V")
    required += [
        (REPO, "<init>", constructor, "method", operators, "flowOn", "("+FLOW+"Lkotlin/coroutines/CoroutineContext;)"+FLOW),
        (REPO, "<init>", constructor, "field", REPO, "wallpaperSupportsAmbientMode", FLOW),
        (CLOCK, clock_method, clock_sig, "method", CLOCK_CONTROLLER, "getSmallClock", "()"+FACE),
        (CLOCK, clock_method, clock_sig, "method", CLOCK_CONTROLLER, "getLargeClock", "()"+FACE),
        (MACHINE, "requestState", "("+STATE+")V", "method", MACHINE, "requestState", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", MACHINE, "transitionTo", "("+STATE+"I)V"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", S+"settings/UserTrackerImpl;", "getUserId", "()I"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", A+"hardware/display/AmbientDisplayConfiguration;", "alwaysOnEnabled", "(I)Z"),
        (MACHINE, "transitionTo", "("+STATE+"I)V", "method", S+"doze/DozeMachine$Part;", "transitionTo", "("+STATE+STATE+")V"),
        (S+"doze/DozeService;", "onCreate", "()V", "field", S+"doze/DozeService;", "mDozeMachine", MACHINE),
        (S+"doze/DozeService;", "onDestroy", "()V", "field", S+"doze/DozeService;", "mDozeMachine", MACHINE),
        (NOTIFICATIONS, "updateAlpha", "()V", "field", NOTIFICATIONS, "mMaxAlphaForKeyguard", "F"),
        (COLORS_LISTENER, "onColorsChanged", COLORS_CALLBACK, "field", THEME, "mAcceptColorEvents", "Z"),
        (COLORS_LISTENER, "onColorsChanged", COLORS_CALLBACK, "field", THEME, "mDeferredWallpaperColors", "Landroid/util/SparseArray;"),
        (COLORS_LISTENER, "onColorsChanged", COLORS_CALLBACK, "method", THEME, "handleWallpaperColors", COLORS_CALLBACK),
    ]
    for owner, name, sig in ((CALLBACK, "onDozeAmountChanged", "(FF)V"), (COLLECTOR, "emit", EMIT)):
        required.append((owner, name, sig, "field" if qpr2 else "method", DEPTH,
                         "wakeAndUnlockBlurRadius" if qpr2 else "setWakeAndUnlockBlurRadius", "F" if qpr2 else "(F)V"))
    failures = []
    for owner, name, sig, kind, target, member, descriptor in required:
        try:
            stream = instructions(dexes, owner, name, sig)
            key = "type" if kind == "field" else "descriptor"
            assert any(i.get(kind, {}).get("owner") == target and i[kind]["name"] == member and i[kind][key] == descriptor for i in stream)
        except (AssertionError, KeyError) as error:
            failures.append((owner, name, target, member, str(error)))
    # Verify classId=1 is specifically the ambient producer branch, not merely
    # that some merged R8 branch happens to call the setter/write the field.
    try:
        stream = instructions(dexes, COLLECTOR, "emit", EMIT)
        branch = next(i["switch"] for i in stream if "switch" in i)
        start = branch["targets"][branch["keys"].index(1)]
        end = min((n for n in branch["targets"] if n > start), default=float("inf"))
        assert any(start <= i["offset"] < end and i.get("field", {}).get("name") == "wallpaperSupportsAmbientMode" for i in stream)
        ambient_branch = [i for i in stream if start <= i["offset"] < end]
        if qpr2:
            assert any(i.get("field", {}).get("name") == "wakeAndUnlockBlurRadius" and i["opcode"] == "59" for i in ambient_branch)
        else:
            assert any(i.get("method", {}).get("name") == "setWakeAndUnlockBlurRadius" for i in ambient_branch)
    except (AssertionError, KeyError, StopIteration, ValueError) as error:
        failures.append((COLLECTOR, "emit", "classId=1 ambient branch", str(error)))
    return failures, len(required)+1


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
            print("PASS", label, "complete enumerated Alive/DarkAod reflection contract")
    print("LIMIT: archived ABI only; no ART install, sensor/display/visual/power/recovery acceptance")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
