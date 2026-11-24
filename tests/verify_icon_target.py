"""Exact two-archive appearance preflight; DEX evidence is not device acceptance.

Complements verify_navbar_target/verify_stacked_signal/verify_drawer_monet with
current notification status/card, Settings framework artwork boundary, splash,
launcher refresh, shade and signal input paths. Settings APK page/cache chains
are not archived here and remain explicitly unverified.
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
WRAPPER = S + "statusbar/notification/row/wrapper/NotificationViewWrapper;"
HEADER = S + "statusbar/notification/row/wrapper/NotificationHeaderViewWrapper;"
TEMPLATES = [S + "statusbar/notification/row/wrapper/Notification" + name + "ViewWrapper;"
             for name in ("Template", "BigTextTemplate", "BigPictureTemplate", "MediaTemplate", "ProgressTemplate", "MetricTemplate")]
ROW = S + "statusbar/notification/row/ExpandableNotificationRow;"
ENTRY = S + "statusbar/notification/collection/EntryAdapter;"
CACHING = "Lcom/android/internal/widget/CachingIconView;"
ROW_ICON = "Lcom/android/internal/widget/NotificationRowIconView;"
ROW_PROVIDER = ROW_ICON[:-1] + "$NotificationIconProvider;"
ROW_INFLATER = S + "statusbar/notification/row/icon/NotificationRowIconViewInflaterFactory;"
ROW_INSTANTIATE = "(" + ROW + "ILjava/lang/String;" + C + "Landroid/util/AttributeSet;)Landroid/view/View;"
IMAGE = "Landroid/widget/ImageView;"
CANVAS = "(Landroid/graphics/Canvas;)V"
UPDATED = "(" + ROW + ")V"
PM = "Landroid/app/ApplicationPackageManager;"
PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
FACTORY = "Landroid/util/IconDrawableFactory;"
PACKAGE_ITEM = "Landroid/content/pm/PackageItemInfo;"
APP_INFO = "Landroid/content/pm/ApplicationInfo;"
UNBADGED = "(" + PACKAGE_ITEM + APP_INFO + ")" + D
BADGED = "(" + PACKAGE_ITEM + APP_INFO + "I)" + D
USER = "Landroid/os/UserHandle;"
# Required reflection members plus the native bind/draw calls proving these boundaries.
ARTWORK_METHODS = {
    HEADER: [("onContentUpdated", UPDATED), ("resolveHeaderViews", "()V")],
    ENTRY: [("getSbn", "()Landroid/service/notification/StatusBarNotification;")],
    IMAGE: [("onDraw", CANVAS)],
    ROW_ICON: [("setIconProvider", "(" + ROW_PROVIDER + ")V")],
    ROW_INFLATER: [("instantiate", ROW_INSTANTIATE)],
    CACHING: [("setImageDrawable", "(" + D + ")V"),
              ("setImageIcon", "(Landroid/graphics/drawable/Icon;)V"),
              ("setImageIconAsync", "(Landroid/graphics/drawable/Icon;)Ljava/lang/Runnable;")],
    PM: [("loadUnbadgedItemIcon", UNBADGED)],
    FACTORY: [("getBadgedIcon", BADGED)],
    "Landroid/app/Application;": [("attach", "(" + C + ")V")],
    C: [("getUserId", "()I")],
}
ARTWORK_FIELDS = {
    HEADER: [("mIcon", CACHING)],
    WRAPPER: [("mRow", ROW), ("mView", "Landroid/view/View;")],
    ROW: [("mEntryAdapter", ENTRY)],
    IMAGE: [("mDrawable", D), ("mDrawMatrix", "Landroid/graphics/Matrix;")],
    PM: [("mContext", "Landroid/app/ContextImpl;")],
    FACTORY: [("mContext", C), ("mPm", PACKAGE_MANAGER)],
    PACKAGE_ITEM: [("showUserIcon", "I")],
    USER: [("USER_NULL", "I")],
}
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
    L + "LauncherModel;": [("newModelCallbacks", "()" + L + "model/ModelLauncherCallbacks;"),
                           ("rebindCallbacks", "(Ljava/lang/String;)V")],
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


def verify_artwork(label, classes, body):
    def call(instructions, owner, name, signature):
        return [i for i in instructions if i.get("method") ==
                {"owner": owner, "name": name, "descriptor": signature}]

    def field(instructions, owner, name, kind, opcode):
        return any(i.get("field") == {"owner": owner, "name": name, "type": kind}
                   and i["opcode"] == opcode for i in instructions)

    for owner, required in ARTWORK_METHODS.items():
        for name, signature in required:
            member = next(m for m in classes[owner]["methods"] if m["name"] == name and m["descriptor"] == signature)
            assert not member["access_flags"] & 8, (label, owner, name, "must be instance method")
    for owner, required in ARTWORK_FIELDS.items():
        for name, kind in required:
            member = next(f for f in classes[owner]["fields"] if f["name"] == name and f["type"] == kind)
            assert bool(member["access_flags"] & 8) == (owner == USER), (label, owner, name, "static mismatch")
    assert classes[HEADER]["superclass"] == WRAPPER, label
    # Exact allowlisted subclasses either inherit the update or invoke-super back to Header.
    for owner in TEMPLATES:
        assert owner in classes, (label, owner)
        parent = HEADER if owner == TEMPLATES[0] else TEMPLATES[0]
        assert classes[owner]["superclass"] == parent, (label, owner)
        for method in classes[owner]["methods"]:
            if method["name"] == "onContentUpdated" and method["descriptor"] == UPDATED:
                assert any(i["opcode"] in ("6f", "75") for i in
                           call(body(owner, "onContentUpdated", UPDATED), parent, "onContentUpdated", UPDATED)), (label, owner)
    updated = body(HEADER, "onContentUpdated", UPDATED)
    assert call(updated, HEADER, "resolveHeaderViews", "()V"), label
    assert field(updated, ROW, "mEntryAdapter", ENTRY, "54"), label
    assert call(updated, ENTRY, "getSbn", "()Landroid/service/notification/StatusBarNotification;"), label
    resolved = body(HEADER, "resolveHeaderViews", "()V")
    assert field(resolved, WRAPPER, "mView", "Landroid/view/View;", "54"), label
    assert call(resolved, "Landroid/view/View;", "findViewById", "(I)Landroid/view/View;"), label
    assert field(resolved, HEADER, "mIcon", CACHING, "5b"), label
    # Header.mIcon is declared as CachingIconView, NOT an exact-instance type promise.
    assert any(i["opcode"] == "14" and i["units"][1:] == [0x0006, 0x0102]
               for i in resolved), (label, "Header resolves android.R.id.icon (0x01020006)")
    assert classes[ROW_ICON]["superclass"] == CACHING, label
    assert classes[CACHING]["superclass"] == IMAGE, label
    for owner in (ROW_ICON, CACHING):
        assert not any(m["name"] == "onDraw" for m in classes[owner]["methods"]), (label, owner)
    inflated = body(ROW_INFLATER, "instantiate", ROW_INSTANTIATE)
    assert any(i.get("type") == ROW_ICON and i["opcode"] == "22" for i in inflated), label
    assert call(inflated, ROW_ICON, "<init>", "(" + C + "Landroid/util/AttributeSet;)V"), label
    assert call(inflated, ENTRY, "getSbn", "()Landroid/service/notification/StatusBarNotification;"), label
    assert call(inflated, ROW_ICON, "setIconProvider", "(" + ROW_PROVIDER + ")V"), label
    # Both known icon classes inherit ImageView.onDraw; native setters stay native.
    for name, signature in ARTWORK_METHODS[CACHING]:
        assert call(body(CACHING, name, signature), IMAGE, name, signature), (label, name)
    drawing = body(IMAGE, "onDraw", CANVAS)
    assert field(drawing, IMAGE, "mDrawable", D, "54"), label
    assert field(drawing, IMAGE, "mDrawMatrix", "Landroid/graphics/Matrix;", "54"), label
    assert call(drawing, D, "draw", CANVAS), label
    assert call(drawing, "Landroid/graphics/Canvas;", "concat", "(Landroid/graphics/Matrix;)V"), label
    # Framework-only evidence: replacing the unbadged input leaves native shadows/badges after it.
    assert classes[PM]["superclass"] == PACKAGE_MANAGER, label
    factory = body(FACTORY, "getBadgedIcon", BADGED)
    assert field(factory, FACTORY, "mPm", PACKAGE_MANAGER, "54"), label
    assert field(factory, FACTORY, "mContext", C, "54"), label
    loading = call(factory, PACKAGE_MANAGER, "loadUnbadgedItemIcon", UNBADGED)
    shadow = call(factory, FACTORY, "getShadowedIcon", "(" + D + ")" + D)
    badges = call(factory, "Landroid/util/LauncherIcons;", "getBadgedDrawable", "(" + D + D + "I)" + D)
    assert len(loading) == 1 and shadow and badges, label
    assert all(i["offset"] > loading[0]["offset"] for i in shadow + badges), label
    loading = body(PM, "loadUnbadgedItemIcon", UNBADGED)
    assert field(loading, PM, "mContext", "Landroid/app/ContextImpl;", "54"), label
    assert field(loading, PACKAGE_ITEM, "showUserIcon", "I", "52"), label


def verify(label, artifacts, qpr2):
    methods, fields = {**METHODS, **ARTWORK_METHODS}, {**FIELDS, **ARTWORK_FIELDS}
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
    classes, definitions = load(artifacts, set(methods) | set(fields) | set(TEMPLATES) | {initial})
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
    verify_artwork(label, classes, body)
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
    print(f"PASS {label}: {sum(map(len, methods.values()))} exact methods, {sum(map(len, fields.values()))} fields; notification status/card bind/draw, Settings framework unbadged/shadow/badge boundary, splash, status scale, shade initial/config emission, signal drawable, desktop/drawer/cache/animation paths")


def main():
    for label, artifacts in ARCHIVES.items():
        qpr2 = label.startswith("Pixel")
        launcher = ("verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk", "109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c") if qpr2 else ("verification/navbar-system/NexusLauncher.apk", "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56")
        verify(label, artifacts + [launcher], qpr2)
    print("UNVERIFIED: target framework-res.apk layouts are not archived in these fixtures; DEX proves Header id/icon lookup and RowIconView inflater/provider wiring, not the installed layout selection or actual view instance")
    print("UNVERIFIED: actual Settings.apk is not archived in these fixtures; Settings application-list/detail page call chains and optional ApplicationsState/AppIconCacheManager cache invalidation are not verified by framework or SystemUI settingslib copies")
    print("UNVERIFIED: device path selection, notification card rendering/rebind, Settings page rendering/cache refresh, visuals, rotation, updates and restoration; archive ABI/call evidence is not device acceptance; run dedicated navbar/stacked/drawer checks too")


if __name__ == "__main__":
    main()
