"""Exact two-archive appearance preflight; DEX evidence is not device acceptance.

Complements verify_navbar_target/verify_stacked_signal/verify_drawer_monet with
current notification, splash, launcher refresh, shade and signal input paths.
"""
import hashlib
from pathlib import Path
import zipfile
import verify_signal_target as dex
from verify_navbar_target import dex_views, ARCHIVES

if not __debug__:
    raise RuntimeError("Run without -O: exact archive checks require assertions")
ROOT = Path(__file__).resolve().parents[1]
L = "Lcom/android/launcher3/"
S = "Lcom/android/systemui/"
D = "Landroid/graphics/drawable/Drawable;"
C = "Landroid/content/Context;"
ITEM = L + "model/data/ItemInfoWithIcon;"
BITMAP = L + "icons/BitmapInfo;"
FLAG = L + "icons/cache/CacheLookupFlag;"
BUBBLE = L + "BubbleTextView;"
SPLASH = "Lcom/android/wm/shell/startingsurface/SplashscreenContentDrawer"
REPO = S + "shade/data/repository/ShadeConfigRepository"
CONFIG = S + "common/ui/data/repository/ConfigurationRepositoryImpl$onConfigurationChange$1$callback$1;"
MOBILE = S + "statusbar/pipeline/mobile/"
EMITTER = MOBILE + "ui/binder/MobileIconBinder$bind$1$2$1$2$2;"
CELL = MOBILE + "domain/model/SignalIconModel$CellularTypeIconModel$Cellular;"
CONT = "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
ICON = S + "statusbar/StatusBarIconView;"
METHODS = {
    ICON: [("getIcon", "(Lcom/android/internal/statusbar/StatusBarIcon;)" + D), ("updateDrawable", "(Z)Z"), ("onDraw", "(Landroid/graphics/Canvas;)V")],
    SPLASH + "$SplashViewBuilder;": [("build", "()Landroid/window/SplashScreenView;")],
    S + "statusbar/phone/PhoneStatusBarView;": [("updateStatusBarHeight", "()V")],
    S + "statusbar/phone/KeyguardStatusBarView;": [],
    "Landroid/telephony/SignalStrength;": [("getPrimary", "()Landroid/telephony/CellSignalStrength;")],
    "Landroid/telephony/ServiceState;": [("isEmergencyOnly", "()Z")],
    CONFIG: [("onConfigChanged", "(Landroid/content/res/Configuration;)V")],
    EMITTER: [("emit", CONT)],
    MOBILE + "ui/viewmodel/LocationBasedMobileViewModel;": [("getSubscriptionId", "()I")],
    CELL: [("<init>", "(IIZZ)V"), ("toSignalDrawableState", "()I")],
    "Lcom/android/settingslib/graph/SignalDrawable;": [("onLevelChange", "(I)Z")],
    L + "icons/IconProvider;": [("getIcon", "(Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;I)" + D)],
    "Landroid/content/pm/LauncherActivityInfo;": [(n, "(I)" + D) for n in ("getIcon", "getBadgedIcon")],
    "Landroid/app/Application;": [("attach", "(" + C + ")V")],
    L + "allapps/BaseAllAppsAdapter;": [("getIconOnCreateSetup", "(Landroid/view/ViewGroup;)" + BUBBLE)],
    BUBBLE: [("setStandardIcon", "(" + ITEM + ")V"), ("applyIconAndLabel", "(" + ITEM + ")V"),
             ("applyFromApplicationInfo", "(" + L + "model/data/AppInfo;)V"),
             ("applyFromWorkspaceItem", "(" + L + "model/data/WorkspaceItemInfo;)V")],
    BITMAP: [("newIcon", "(" + C + "I" + L + "icons/IconShape;)" + L + "icons/FastBitmapDrawable;"), ("getMatchingLookupFlag", "()" + FLAG)],
    FLAG: [("updateMask", "(IZ)" + FLAG)],
    L + "icons/IconCache;": [("getTitleAndIcon", "(" + ITEM + FLAG + "Ljava/util/function/Supplier;)V"),
                             ("updateIconInBackground", "(Ljava/util/concurrent/Executor;" + L + "icons/IconCache$ItemInfoUpdateReceiver;" + ITEM + FLAG + ")" + L + "util/CancellableTask;")],
    L + "model/data/IconRequestInfo;": [("<init>", "(" + ITEM + "Landroid/content/pm/LauncherActivityInfo;[BZ" + FLAG + ")V")],
    L + "icons/cache/BaseIconCache;": [("removeIconsForPkg", "(Landroid/os/UserHandle;Ljava/lang/String;)V")],
    L + "util/DaggerSingletonObject;": [("get", "(" + C + ")Ljava/lang/Object;")],
    L + "LauncherModel;": [("newModelCallbacks", "()" + L + "model/ModelLauncherCallbacks;")],
    L + "model/ModelLauncherCallbacks;": [("onPackageChanged", "(Ljava/lang/String;Landroid/os/UserHandle;)V")],
    L + "graphics/ThemeManager;": [("verifyIconState", "()V")],
    L + "views/FloatingIconView;": [("setIcon", "(" + D + D + "Ljava/util/function/Supplier;IZ)V")],
    L + "model/data/ItemInfo;": [("getTargetComponent", "()Landroid/content/ComponentName;")],
}
FIELDS = {
    ICON: [("mNotification", "Landroid/service/notification/StatusBarNotification;"), ("mShowsConversation", "Z"),
           ("mOriginalStatusBarIconSize", "I"), ("mIconScale", "F"), ("mSlot", "Ljava/lang/String;"),
           ("mBundleEntry", S + "statusbar/notification/collection/BundleEntry;")],
    SPLASH + "$SplashViewBuilder;": [("mActivityInfo", "Landroid/content/pm/ActivityInfo;"), ("mSuggestType", "I"), ("this$0", SPLASH + ";")],
    SPLASH + ";": [("mTmpAttrs", SPLASH + "$SplashScreenWindowAttrs;")],
    SPLASH + "$SplashScreenWindowAttrs;": [("mSplashScreenIcon", D), ("mIconBgColor", "I")],
    EMITTER: [("$mobileDrawable", "Ljava/lang/Object;"), ("$viewModel", MOBILE + "ui/viewmodel/LocationBasedMobileViewModel;")],
    CELL: [("carrierNetworkChange", "Z"), ("level", "I"), ("numberOfLevels", "I"), ("showExclamationMark", "Z")],
    "Lkotlin/Pair;": [("second", "Ljava/lang/Object;")],
    ITEM: [("bitmap", BITMAP)], BITMAP: [("themedBitmap", L + "icons/ThemedBitmap;")],
    L + "LauncherAppState;": [("INSTANCE", L + "util/DaggerSingletonObject;"), ("iconCache", L + "icons/IconCache;"), ("model", L + "LauncherModel;")],
    L + "icons/cache/BaseIconCache;": [("workerHandler", "Landroid/os/Handler;")],
    L + "graphics/ThemeManager;": [("INSTANCE", L + "util/DaggerSingletonObject;"), ("iconState", L + "graphics/ThemeManager$IconState;")],
    L + "graphics/ThemeManager$IconState;": [("themeController", L + "icons/IconThemeController;")],
    L + "views/FloatingIconView;": [(n, L + "views/FloatingIconView$IconLoadResult;") for n in ("mIconLoadResult", "sIconLoadResult")] + [("mClipIconView", L + "views/ClipIconView;")],
    L + "views/FloatingIconView$IconLoadResult;": [("itemInfo", L + "model/data/ItemInfo;"), ("isThemed", "Z")],
    L + "views/ClipIconView;": [("mBackground", D), ("mForeground", D)],
    L + "model/data/ItemInfo;": [("user", "Landroid/os/UserHandle;")],
}
for suffix, field in [("Impl$callbackEvents$1$1$callback$1;", "$this_run"), ("KairosImpl$callbackEvents$1$2$callback$1;", "this$0")]:
    owner = MOBILE + "data/repository/prod/MobileConnectionRepository" + suffix
    repository = owner.split("$", 1)[0] + ";"
    METHODS[owner] = [("onSignalStrengthsChanged", "(Landroid/telephony/SignalStrength;)V"), ("onServiceStateChanged", "(Landroid/telephony/ServiceState;)V"), ("onCarrierRoamingNtnModeChanged", "(Z)V")]
    FIELDS[owner], FIELDS[repository] = [(field, repository)], [("subId", "I")]


def load(artifacts, targets):
    dex.TARGETS = targets
    classes, definitions = {}, {}
    for relative, digest in artifacts:
        path = ROOT / relative
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, relative
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                    for data in dex_views(archive.read(name)):
                        for owner, cls in data.classes.items():
                            # SystemUI bundles iconloader copies; launcher hooks resolve its own definitions.
                            if owner.startswith(L) != ("Launcher" in path.name):
                                continue
                            assert owner not in classes, (relative, owner)
                            classes[owner], definitions[owner] = cls, data
    return classes, definitions


def verify(label, artifacts, qpr2):
    methods, fields = dict(METHODS), dict(FIELDS)
    emitter = REPO + ("$onActivated$2$1$1;" if qpr2 else "$special$$inlined$map$1$2;")
    initial = REPO + ";" if qpr2 else S + "shade/domain/interactor/ShadeModeInteractorImpl;"
    methods[emitter] = [("emit", CONT)]
    if not qpr2:
        methods[S + "statusbar/policy/SplitShadeStateControllerImpl;"] = [("shouldUseSplitNotificationShade", "(Landroid/content/res/Resources;)Z")]
        for owner in ("NotificationPanelViewController", "QuickSettingsControllerImpl", "NotificationsQSContainerController"):
            methods[S + "shade/" + owner + ";"] = [("updateResources", "()V")]
        fields[S + "shade/NotificationPanelViewController;"] = [("mSplitShadeStateController", S + "statusbar/policy/SplitShadeStateControllerImpl;"), ("mResources", "Landroid/content/res/Resources;")]
    fields[REPO + ";"] = [("legacyUseSplitShade$delegate", "Landroidx/compose/runtime/MutableState;")] if qpr2 else [("legacyUseSplitShade", "Lkotlinx/coroutines/flow/Flow;")]
    fields[EMITTER] = fields[EMITTER] + [("$r8$classId", "B" if qpr2 else "I")]
    item_new = "(I" + C + ")" if qpr2 else "(" + C + "I)"
    methods[ITEM] = [("newIcon", item_new + L + "icons/FastBitmapDrawable;")]
    classes, definitions = load(artifacts, set(methods) | set(fields) | {initial})
    for owner, required in methods.items():
        assert owner in classes, (label, owner)
        for name, signature in required:
            assert any(m["name"] == name and m["descriptor"] == signature for m in classes[owner]["methods"]), (label, owner, name, signature)
    for owner, required in fields.items():
        assert owner in classes, (label, owner)
        for name, signature in required:
            assert any(f["name"] == name and f["type"] == signature for f in classes[owner]["fields"]), (label, owner, name, signature)
    def body(owner, name, descriptor):
        return definitions[owner].instructions(owner, name, descriptor)
    def calls(instructions, owner, name):
        return any(i.get("method", {}).get("owner") == owner and i["method"]["name"] == name for i in instructions)
    # Notification updates must enter the hooked getter, and native drawing must read scale.
    assert calls(body(ICON, "updateDrawable", "(Z)Z"), ICON, "getIcon"), label
    assert any(i.get("field", {}).get("name") == "mIconScale" for i in body(ICON, "onDraw", "(Landroid/graphics/Canvas;)V")), label
    splash = body(SPLASH + "$SplashViewBuilder;", "build", "()Landroid/window/SplashScreenView;")
    assert {i.get("field", {}).get("name") for i in splash} >= {"mSplashScreenIcon", "mIconBgColor", "mSuggestType"}, label
    if not qpr2:
        for owner in ("NotificationPanelViewController", "QuickSettingsControllerImpl", "NotificationsQSContainerController"):
            assert calls(body(S + "shade/" + owner + ";", "updateResources", "()V"),
                         S + "statusbar/policy/SplitShadeStateControllerImpl;", "shouldUseSplitNotificationShade"), (label, owner)
    # Both initial state and later configuration emissions read resources inside our scope.
    initializers = [m for m in classes[initial]["methods"] if m["name"] == "<init>"]
    assert initializers and any(calls(body(initial, "<init>", m["descriptor"]), "Landroid/content/res/Resources;", "getBoolean") for m in initializers), label
    assert calls(body(emitter, "emit", CONT), "Landroid/content/res/Resources;", "getBoolean"), label
    assert calls(body(CONFIG, "onConfigChanged", "(Landroid/content/res/Configuration;)V"), "Lkotlinx/coroutines/channels/SendChannel;", "trySend-JP2dKIU"), label
    signal = body(EMITTER, "emit", CONT)
    assert calls(signal, "Landroid/graphics/drawable/DrawableWrapper;", "setLevel"), label
    assert calls(signal, CELL.replace("$Cellular;", ";"), "toSignalDrawableState"), label
    # The themed drawer still reaches the native BitmapInfo factory through our two item ABIs.
    assert calls(body(BUBBLE, "applyFromApplicationInfo", "(" + L + "model/data/AppInfo;)V"), BUBBLE, "applyIconAndLabel"), label
    assert calls(body(BUBBLE, "applyIconAndLabel", "(" + ITEM + ")V"), BUBBLE, "setStandardIcon"), label
    assert calls(body(BUBBLE, "setStandardIcon", "(" + ITEM + ")V"), ITEM, "newIcon"), label
    assert calls(body(ITEM, "newIcon", methods[ITEM][0][1]), BITMAP, "newIcon"), label
    native = body(BITMAP, "newIcon", methods[BITMAP][0][1])
    assert calls(native, L + "icons/ThemedBitmap;", "newDelegateFactory"), label
    print(f"PASS {label}: {sum(map(len, methods.values()))} exact methods, {sum(map(len, fields.values()))} fields; notification/splash, status scale, shade initial/config emission, signal drawable, desktop/drawer/cache/animation paths")


def main():
    for label, artifacts in ARCHIVES.items():
        qpr2 = label.startswith("Pixel")
        launcher = ("verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk", "109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c") if qpr2 else ("verification/navbar-system/NexusLauncher.apk", "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56")
        verify(label, artifacts + [launcher], qpr2)
    print("UNVERIFIED: device path selection, visuals, rotation, updates and restoration; run dedicated navbar/stacked/drawer checks too")


if __name__ == "__main__":
    main()
