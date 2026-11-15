"""Verify final APK keeps host-loader reflection names (run after R8 packaging)."""
import sys
import zipfile
from pathlib import Path
import verify_signal_target as dex

PREFIX = "Ldev/local/sunwindowdemo/"
REQUIRED = {
    (PREFIX + "TaskCover;", "refreshScreenCorners"): {"com.android.internal.policy.ScreenDecorationsUtils"},
    (PREFIX + "WindowControls;", "buildControlsRoot"): {
        "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", "android.view.ViewTreeObserver$InternalInsetsInfo"},
    (PREFIX + "features/navbar/NavbarHost;", "<init>"): {
        "mContext", "mNavBarMode", "mNavigationBarTransitions", "mBarBackground", "repositionNavigationBar",
        "providedInsets", "getType", "setInsetsSize"},
    (PREFIX + "features/navbar/NavbarHost;", "install"): {
        "com.android.systemui.navigationbar.views.NavigationBar", "android.view.InsetsFrameProvider",
        "getBarLayoutParamsForRotation", "onInit", "draw", "callApplicationOnCreate", "IMMERSIVE_PREFLIGHT_READY"},
    (PREFIX + "features/navbar/NavbarTaskbarHost;", "<init>"): {
        "context", "mIsDestroyed", "mDragLayer", "mWindowManager", "mWindowLayoutParams",
        "mBackgroundRenderer", "mControllers", "navbarButtonsViewController", "mTaskbarTransitions",
        "mBarBackground", "isGestureNav", "onTaskbarOrBubblebarWindowHeightOrInsetsChanged", "getType", "setInsetsSize"},
    (PREFIX + "features/navbar/NavbarTaskbarHost;", "install"): {
        "com.android.launcher3.taskbar.TaskbarInsetsController", "android.view.InsetsFrameProvider",
        "setProviderInsets", "draw", "dispatchDraw", "callApplicationOnCreate"},
    (PREFIX + "BackBridge;", "installEdgeTouchAdmission"): {"kotlin.Unit"},
    (PREFIX + "features/shade/ComposeShadeLayout;", "install"): {"kotlin.coroutines.Continuation"},
    (PREFIX + "features/shade/SignalDisplay;", "install"): {"kotlin.Pair"},
    (PREFIX + "features/shade/SignalDisplay;", "installStacked"): {
        "kotlin.Pair", "androidx.compose.runtime.ParcelableSnapshotMutableIntState",
        "androidx.compose.runtime.Composer", "androidx.compose.ui.Modifier"},
    (PREFIX + "features/shade/StackedSignalDisplay;", "<init>"): {"sun.misc.Unsafe"},
    (PREFIX + "TaskCleanupGuard;", "install"): {"com.android.server.am.ActivityManagerService", "forceStopPackage"},
}

# Verify the delivered APK includes the restored runtime and the integrated navigation host.
RUNTIME_METHODS = {
    "WindowEngine": {"blockBackgroundTouch", "trackTouchGuard", "clientOwner", "ownerInsets", "idle", "mayAllowResize", "visibleFullscreen"},
    "WindowPolicy": {"backgroundTouchGuard", "startsTouchGuard", "coveredFullscreen"},
    "BackBridge": {"connect", "cancelNativeUp", "abort"},
    "Config": {"longBackWindow"},
    "TaskCover": {"beforeReveal", "canRetarget", "reusesPresentation", "handoff"},
    "TaskCleanupGuard": {"install", "delay", "acquired", "check"},
    "LaunchCoverFlow": {"ready", "changed", "visibility"},
    "WindowControls": {"buildControlsRoot", "fillTouchRegion"},
    "features/edgetouch/EdgeInputFilter": {"install", "sync"},
    "features/edgetouch/EdgeInputPolicy": {"accepted", "regions"},
    "features/iconlayers/IconOverrides": {"prune"},
    "features/iconlayers/IconPolicy": {"batch"},
    "features/navbar/NavbarModule": {"onModuleLoaded", "onPackageLoaded"},
    "features/navbar/NavbarHost": {"install", "eligible", "onLayout", "removeNavigationInset", "hideBackground", "applyEnabled", "refreshBars", "log"},
    "features/navbar/NavbarTaskbarHost": {"install", "eligible", "onProvider", "hideBackground", "applyEnabled", "refreshControllers"},
    "core/HostSnapshot": {"read", "cached", "store"},
    "features/iconlayers/IconAssets": {"imageBytes"},
    "features/iconlayers/IconImages": {"notificationSize"},
}

REMOVED_NAVBAR_CLASSES = {
    "NavbarApp", "NavbarSystem", "NavbarContainer", "NavbarRules", "NavbarStructure", "NavbarSignature",
    "NavbarTrace", "NavbarLabelHost", "NavbarViewTree", "NavbarInsets", "NavbarReflect",
    "NavbarColorCache", "NavbarColors", "NavbarWindowLayer", "NavbarLayout", "NavbarScreenKt",
}
REMOVED_NAVBAR_OWNERS = {PREFIX + "features/navbar/" + name + ";" for name in REMOVED_NAVBAR_CLASSES}


def check(apk):
    runtime = {PREFIX + owner + ";": methods for owner, methods in RUNTIME_METHODS.items()}
    dex.TARGETS = {owner for owner, _ in REQUIRED} | set(runtime)
    found = set()
    runtime_found = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith(".dex"):
                continue
            data = dex.Dex(archive.read(name))
            # Inspect definitions, not type references: include nested remnants without parsing every class body.
            owners = {data.types[data.u32(data.u32(100) + 32 * i)] for i in range(data.u32(96))}
            obsolete = {owner for owner in owners if owner.split("$", 1)[0].rstrip(";") + ";" in REMOVED_NAVBAR_OWNERS}
            if obsolete:
                raise RuntimeError(f"Removed navigation runtime still present in final DEX: {sorted(obsolete)}")
            for owner, cls in data.classes.items():
                if owner in runtime:
                    missing = runtime[owner] - {method["name"] for method in cls["methods"]}
                    if missing:
                        raise RuntimeError(f"Missing integrated runtime methods in {owner}: {sorted(missing)}")
                    runtime_found.add(owner)
                for method in cls["methods"]:
                    key = owner, method["name"]
                    if key not in REQUIRED:
                        continue
                    strings = set()
                    for ins in data.instructions(owner, method["name"], method["descriptor"]):
                        if ins["opcode"] == "1a":
                            strings.add(data.strings[ins["units"][1]])
                        elif ins["opcode"] == "1b":
                            strings.add(data.strings[ins["units"][1] | ins["units"][2] << 16])
                    missing = REQUIRED[key] - strings
                    if missing:
                        raise RuntimeError(f"Host ABI name rewritten/removed in {key}: {sorted(missing)}")
                    found.add(key)
    if found != set(REQUIRED):
        raise RuntimeError(f"Missing hook methods: {set(REQUIRED) - found}")
    if runtime_found != set(runtime):
        raise RuntimeError(f"Missing integrated runtime classes: {set(runtime) - runtime_found}")
    print("PASS: final DEX retains Kotlin/Compose host-loader reflection names at all checked hooks")
    print("PASS: final DEX includes restored window optimizations, icon cleanup and immersive navigation host; removed navigation runtime absent")

if __name__ == "__main__":
    check(Path(sys.argv[1]))
