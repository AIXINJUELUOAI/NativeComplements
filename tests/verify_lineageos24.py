"""Static LineageOS 24.0 contract audit against the official local source tree.

This does not build or run LineageOS. It proves that the class/member shapes used by the
module exist in the pinned LineageOS 24.0 source, including the Kotlin property migrations
that differ from PixelOS.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "域外源码库" / "LineageOS-24.0"
MANIFEST = ROOT.parent / "域外源码库" / "LineageOS-24.0-manifest"
PROVENANCE = ROOT / "tests" / "lineageos24-provenance.json"


def fail(message: str) -> None:
    raise AssertionError(message)


def text(relative: str) -> str:
    path = SOURCE / relative
    if path.is_file():
        return path.read_text(encoding="utf-8", errors="replace")
    for prefix in ("frameworks_base", "frameworks_libs_systemui", "Launcher3",
                   "packages_services/Telecomm", "packages_apps_ThemePicker"):
        if relative.startswith(prefix + "/"):
            repo = SOURCE / prefix
            inner = relative[len(prefix) + 1:]
            try:
                return subprocess.check_output(
                    ["git", "-C", str(repo), "show", f"HEAD:{inner}"],
                    text=True, stderr=subprocess.DEVNULL,
                )
            except (OSError, subprocess.CalledProcessError):
                break
    fail(f"missing LineageOS source: {relative}")


def has(relative: str, pattern: str, label: str) -> None:
    if re.search(pattern, text(relative), re.MULTILINE | re.DOTALL) is None:
        fail(f"{label}: {relative} does not match /{pattern}/")


def commit(relative: str) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(SOURCE / relative), "rev-parse", "HEAD"],
            text=True, stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def repo_head(key: str) -> str:
    if key == "manifest":
        try:
            return subprocess.check_output(
                ["git", "-C", str(MANIFEST), "rev-parse", "HEAD"],
                text=True, stderr=subprocess.DEVNULL,
            ).strip()
        except (OSError, subprocess.CalledProcessError):
            return ""
    return commit({
        "frameworks_base": "frameworks_base",
        "frameworks_native": "frameworks_native",
        "frameworks_libs_systemui": "frameworks_libs_systemui",
        "launcher3": "Launcher3",
        "telecomm": "packages_services/Telecomm",
        "themepicker": "packages_apps_ThemePicker",
    }.get(key, key))


def main() -> None:
    if not SOURCE.is_dir():
        print(f"SKIP: LineageOS source not present at {SOURCE}")
        return
    data = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    manifest_file = MANIFEST / "default.xml"
    if not manifest_file.is_file():
        fail("missing official LineageOS manifest checkout")
    manifest = manifest_file.read_text(encoding="utf-8")
    if 'revision="refs/heads/lineage-24.0"' not in manifest:
        fail("manifest is not pinned to lineage-24.0")
    if 'path="packages/apps/Launcher3"' not in manifest:
        fail("manifest does not contain Launcher3")
    if 'path="packages/services/Telecomm"' not in manifest:
        fail("manifest does not contain Telecomm")
    for key, expected in data.items():
        if key in {"distribution", "source_root"}:
            continue
        if isinstance(expected, dict):
            actual = repo_head(key)
            if not actual:
                fail(f"missing local checkout for {key}")
            if actual != expected["commit"]:
                fail(f"{key} commit changed: {actual} != {expected['commit']}")

    # Framework/task/transition/lifecycle shapes used by WindowEngine and SunWindowModule.
    wm = "frameworks_base/services/core/java/com/android/server/wm"
    has(f"{wm}/WindowManagerService.java",
        r"public static WindowManagerService main\(@NonNull final Context context,", "WMS.main 5-arg")
    has(f"{wm}/Task.java", r"void prepareSurfaces\(\)", "Task.prepareSurfaces")
    has(f"{wm}/Task.java", r"private void computeFreeformBounds\(", "Task.computeFreeformBounds")
    has(f"{wm}/Task.java", r"private void moveTaskToBackInner\(", "Task.moveTaskToBackInner")
    has(f"{wm}/Task.java", r"boolean isResizeable\(", "Task.isResizeable")
    has(f"{wm}/ActivityRecord.java", r"void setState\(State state, String reason\)", "ActivityRecord.setState")
    has(f"{wm}/ActivityRecord.java", r"static void activityResumedLocked\(", "ActivityRecord.activityResumedLocked")
    has(f"{wm}/ActivityRecord.java", r"boolean updateAndCheckVisibility\(", "ActivityRecord.updateAndCheckVisibility")
    has(f"{wm}/ActivityRecord.java", r"boolean shouldBeVisibleUnchecked\(", "ActivityRecord.shouldBeVisibleUnchecked")
    has(f"{wm}/TaskFragment.java", r"final boolean resumeTopActivity\(", "TaskFragment.resumeTopActivity")
    has(f"{wm}/TaskFragment.java", r"boolean shouldSleepActivities\(", "TaskFragment.shouldSleepActivities")
    has(f"{wm}/TaskFragment.java", r"boolean sleepIfPossible\(", "TaskFragment.sleepIfPossible")
    has(f"{wm}/WindowState.java", r"boolean finishDrawing\(", "WindowState.finishDrawing")
    has(f"{wm}/WindowState.java", r"boolean canReceiveTouchInput\(", "WindowState.canReceiveTouchInput")
    has(f"{wm}/WindowState.java", r"public boolean canReceiveKeys\(boolean", "WindowState.canReceiveKeys")
    has(f"{wm}/ActivityRecordInputSink.java",
        r"private InputWindowHandleWrapper getInputWindowHandleWrapper\(", "InputSink wrapper")
    has(f"{wm}/ActivityStarter.java",
        r"private @Nullable Task handleStartResult\(@NonNull ActivityRecord started,", "ActivityStarter.handleStartResult")
    has(f"{wm}/ActivityStarter.java",
        r"private int startActivityUnchecked\(final ActivityRecord r, ActivityRecord sourceRecord,", "ActivityStarter.startActivityUnchecked")
    has(f"{wm}/ActivityStarter.java", r"private ActivityRecord mSourceRecord;", "ActivityStarter.mSourceRecord")
    has(f"{wm}/ActivityStarter.java", r"private int mRealCallingUid;", "ActivityStarter.mRealCallingUid")
    has(f"{wm}/ActivityCallerState.java", r"public static final class CallerInfo", "ActivityCallerState.CallerInfo")

    # SystemUI paths changed from PixelOS synthetic callbacks to Kotlin receivers.
    ui = "frameworks_base/packages/SystemUI/src/com/android/systemui"
    has(f"{ui}/navigationbar/gestural/DisplayBackGestureHandler.kt",
        r"private val onInputEvent: \(InputEvent\) -> Unit", "LOS input callback")
    has(f"{ui}/navigationbar/gestural/EdgeBackGestureHandler.java",
        r"onInputEvent\(ev\);", "LOS edge callback dispatch")
    has(f"{ui}/navigationbar/gestural/EdgeBackGestureHandler.java",
        r"private void cancelGesture\(MotionEvent ev\)", "LOS edge cancelGesture")
    has(f"{ui}/navigationbar/gestural/EdgeBackGestureHandler.java",
        r"mDisplayBackGestureHandlerFactory\.create\([^;]*?\(ev\) -> \{", "LOS edge lambda construction")
    has(f"{ui}/navigationbar/gestural/EdgeBackGestureHandler.java",
        r"return Unit\.INSTANCE;", "LOS edge Kotlin Unit return")
    has(f"{ui}/navigationbar/gestural/DisplayBackGestureHandler.kt",
        r"private val inputMonitorCompat = InputMonitorCompat", "LOS edge input monitor")
    has(f"{ui}/navigationbar/gestural/DisplayBackGestureHandler.kt",
        r"override fun pilferPointers\(\) = inputMonitorCompat\.pilferPointers\(\)",
        "LOS edge pilfer")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/system/InputMonitorCompat.java",
        r"private final InputMonitor mInputMonitor", "LOS shared input monitor")
    has("frameworks_base/core/java/android/view/InputMonitor.java",
        r"public void pilferPointers\(\)", "LOS native pilfer")
    has(f"{ui}/screenshot/ScreenshotWindow.kt", r"val window: PhoneWindow", "ScreenshotWindow getter")
    has(f"{ui}/screenshot/ScreenshotController.kt", r"override fun handleScreenshot\(", "ScreenshotController.handleScreenshot")
    has(f"{ui}/statusbar/phone/StatusBarNotificationActivityStarter.java",
        r"private int sendPendingIntent\(\s*PendingIntent intent, Intent fillInIntent, Bundle options, NotificationEntry entry\)",
        "LOS sendPendingIntent")
    has(f"{ui}/statusbar/NotificationListener.java",
        r"public void onNotificationPosted\(", "NotificationListener.posted")
    has(f"{ui}/statusbar/NotificationListener.java",
        r"public void onNotificationRemoved\(", "NotificationListener.removed")
    has(f"{ui}/statusbar/notification/collection/NotificationEntry.java",
        r"public StatusBarNotification getSbn\(\)", "LOS NotificationEntry.getSbn")
    has(f"{ui}/statusbar/pipeline/mobile/ui/binder/MobileIconBinder.kt",
        r"fun bind\(\s*view: ViewGroup,\s*viewModel: LocationBasedMobileViewModel,"
        r"\s*@StatusBarIconView\.VisibleState initialVisibilityState: Int = STATE_HIDDEN,"
        r"\s*logger: MobileViewLogger,\s*\): ModernStatusBarViewBinding",
        "LOS MobileIconBinder.bind exact")
    has(f"{ui}/statusbar/pipeline/mobile/ui/binder/MobileIconBinder.kt",
        r"mobile_signal", "LOS mobile signal view id")
    has(f"{ui}/statusbar/pipeline/mobile/ui/binder/MobileIconBinder.kt",
        r"iconView\.setImageDrawable\(mobileDrawable\)", "LOS mobile drawable attach")
    has(f"{ui}/statusbar/pipeline/mobile/ui/binder/MobileIconBinder.kt",
        r"mobileDrawable\.level = packedSignalDrawableState", "LOS mobile drawable level")
    has(f"{ui}/statusbar/pipeline/mobile/ui/model/DualSim.kt",
        r"data class DualSim", "LOS DualSim")
    has(f"{ui}/shade/data/repository/ShadeConfigRepository.kt",
        r"val legacyUseSplitShade: Flow<Boolean>", "LOS shade flow")
    has(f"{ui}/statusbar/policy/SplitShadeStateControllerImpl.kt",
        r"override fun shouldUseSplitNotificationShade\(", "LOS split-shade controller")
    has(f"{ui}/navigationbar/views/NavigationBar.java",
        r"private WindowManager.LayoutParams getBarLayoutParamsForRotation\(", "LOS navbar layout")
    has(f"{ui}/navigationbar/views/NavigationBar.java", r"private int mNavBarMode", "LOS navbar mode")
    has(f"{ui}/navigationbar/views/NavigationBar.java", r"private final Context mContext", "LOS navbar context")
    has(f"{ui}/navigationbar/views/NavigationBar.java",
        r"private final NavigationBarTransitions mNavigationBarTransitions", "LOS navbar transitions field")
    has(f"{ui}/navigationbar/views/NavigationBar.java",
        r"private void repositionNavigationBar\(int", "LOS navbar reposition")
    has(f"{ui}/navigationbar/views/NavigationBarTransitions.java",
        r"class NavigationBarTransitions extends BarTransitions", "LOS navbar transitions")
    has("frameworks_base/telephony/java/android/telephony/TelephonyManager.java",
        r"public void registerTelephonyCallback\(\s*@NonNull @CallbackExecutor Executor executor,"
        r"\s*@NonNull TelephonyCallback callback\)", "LOS TelephonyManager.registerTelephonyCallback")
    has("frameworks_base/core/java/android/widget/ImageView.java",
        r"public void setImageDrawable\(@Nullable Drawable drawable\)", "LOS ImageView.setImageDrawable")
    has("frameworks_base/graphics/java/android/graphics/drawable/Drawable.java",
        r"public final boolean setLevel\(@IntRange\(from=0,to=10000\) int level\)", "LOS Drawable.setLevel")

    # Remaining SystemUI/launcher adapters used by status bar, rotation and recording tile.
    has(f"{ui}/statusbar/phone/PhoneStatusBarView.java",
        r"private void updateStatusBarHeight\(\)", "LOS status bar height")
    has(f"{ui}/statusbar/StatusBarIconView.java",
        r"private boolean updateDrawable\(boolean withClear\)", "LOS status icon drawable")
    has(f"{ui}/statusbar/StatusBarIconView.java",
        r"Drawable getIcon\(StatusBarIcon icon\)", "LOS status icon getter")
    has(f"{ui}/statusbar/StatusBarIconView.java",
        r"private boolean mShowsConversation", "LOS status icon conversation")
    has(f"{ui}/statusbar/StatusBarIconView.java",
        r"mOriginalStatusBarIconSize", "LOS status icon reference size")
    has(f"{ui}/statusbar/phone/KeyguardStatusBarView.java",
        r"public class KeyguardStatusBarView", "LOS keyguard status bar")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButtonController.java",
        r"setRotateSuggestionButtonState\(final boolean visible, final boolean force\)",
        "LOS rotation suggestion state")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButtonController.java",
        r"mCancelPendingRotationProposal", "LOS rotation cancellation")
    has(f"{ui}/qs/tileimpl/QSFactoryImpl.java",
        r"public final QSTile createTile\(String tileSpec\)", "LOS QS createTile")
    has(f"{ui}/qs/tileimpl/QSTileImpl.java",
        r"public void setTileSpec\(String tileSpec\)", "LOS QS setTileSpec")
    has(f"{ui}/qs/tileimpl/QSTileImpl.java",
        r"public void click\(@Nullable Expandable expandable\)", "LOS QS click")
    has("frameworks_base/packages/SystemUI/animation/src/com/android/systemui/animation/Expandable.kt",
        r"class Expandable", "LOS Expandable")
    has("frameworks_base/libs/WindowManager/Shell/src/com/android/wm/shell/startingsurface/SplashscreenContentDrawer.java",
        r"private class SplashViewBuilder", "LOS splash builder")
    has("frameworks_base/libs/WindowManager/Shell/src/com/android/wm/shell/startingsurface/SplashscreenContentDrawer.java",
        r"private final SplashScreenWindowAttrs mTmpAttrs", "LOS splash attrs")
    has("frameworks_base/services/core/java/com/android/server/input/InputManagerService.java",
        r"final boolean filterInputEvent\(InputEvent event, int policyFlags\)", "LOS input filter")
    has("frameworks_base/services/core/java/com/android/server/input/InputManagerService.java",
        r"public void setInputFilter\(IInputFilter filter\)", "LOS input filter setter")
    has("frameworks_base/services/core/java/com/android/server/input/NativeInputManagerService.java",
        r"void setInputFilterEnabled\(boolean enable\)", "LOS native filter switch")
    has("frameworks_base/services/core/java/com/android/server/am/ActivityManagerService.java",
        r"void forceStopPackage\(final String packageName,", "LOS force stop")
    has(f"{wm}/ActivityRecord.java", r"boolean isFocusedActivityOnDisplay\(\)", "LOS focused activity")

    # Launcher3/iconloader property migrations.
    launcher = "Launcher3"
    has(f"{launcher}/AndroidManifest.xml", r'package="com.android.launcher3"', "Launcher3 package")
    lib = "frameworks_libs_systemui/iconloaderlib/src/com/android/launcher3/icons"
    has(f"{lib}/IconProvider.java",
        r"private Drawable getIcon\(PackageItemInfo info, ApplicationInfo appInfo, int iconDpi\)",
        "LOS IconProvider private getIcon")
    has(f"{lib}/IconProvider.java", r"public class IconProvider", "LOS IconProvider class")
    has(f"{lib}/BitmapInfo.kt", r"data class BitmapInfo", "LOS BitmapInfo")
    has(f"{lib}/BitmapInfo.kt", r"val themedBitmap: ThemedBitmap\?", "LOS themedBitmap getter")
    has(f"{lib}/BitmapInfo.kt", r"fun newIcon\(", "LOS BitmapInfo.newIcon")
    has(f"{lib}/cache/CacheLookupFlag.kt", r"fun updateMask\(", "LOS CacheLookupFlag.updateMask")
    has(f"{lib}/cache/BaseIconCache.kt", r"fun removeIconsForPkg\(packageName: String, user: UserHandle\)",
        "LOS removeIconsForPkg order")
    has(f"{launcher}/src/com/android/launcher3/icons/IconCache.kt",
        r"fun updateIconInBackground\(", "LOS IconCache.updateIconInBackground")
    has(f"{launcher}/src/com/android/launcher3/icons/IconCache.kt",
        r"fun getTitleAndIcon\(", "LOS IconCache.getTitleAndIcon")
    has(f"{launcher}/src/com/android/launcher3/LauncherAppState.kt",
        r"val iconCache: IconCache", "LOS LauncherAppState.iconCache")
    has(f"{launcher}/src/com/android/launcher3/LauncherAppState.kt",
        r"val model: LauncherModel", "LOS LauncherAppState.model")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"var iconState", "LOS ThemeManager.iconState")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"val themeController", "LOS ThemeManager.themeController")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"fun verifyIconState\(", "LOS ThemeManager.verifyIconState")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"@JvmField val INSTANCE", "LOS ThemeManager.INSTANCE")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"private val themePreference: ThemePreference", "LOS ThemeManager.themePreference")
    has(f"{launcher}/src/com/android/launcher3/graphics/theme/ThemePreference.kt",
        r"@JvmField val MONO_THEME_VALUE", "LOS ThemePreference.MONO_THEME_VALUE")
    has(f"{launcher}/src/com/android/launcher3/graphics/theme/ThemePreference.kt",
        r"@JvmOverloads\s+fun setValue\(", "LOS ThemePreference.setValue")
    has(f"{launcher}/src/com/android/launcher3/graphics/theme/ThemePreference.kt",
        r": ListenableRef<ThemeValue\?> by themePref", "LOS ThemePreference.getValue delegate")

    # ThemePicker's own capability probe for the "Minimal" (MONOCHROME) style.
    picker = "packages_apps_ThemePicker"
    has(f"{picker}/src/com/android/customization/model/themedicon/ThemedIconUtils.java",
        r"boolean isThemedIconAvailable\(\)", "LOS ThemedIconUtils.isThemedIconAvailable")
    has(f"{picker}/src/com/android/customization/picker/icon/shared/model/ThemePickerIconStyle.kt",
        r"MONOCHROME\(R\.string\.app_icons_style_minimal", "LOS Minimal icon style")
    has(f"{picker}/src/com/android/customization/picker/icon/data/repository/ThemePickerIconStyleRepository.kt",
        r"override val isCustomizationAvailable", "LOS ThemePicker availability flow")
    has(f"{picker}/res/values/strings.xml",
        r'<string name="app_icons_style_minimal"', "LOS Minimal label")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarInsetsController.kt",
        r"fun onTaskbarOrBubblebarWindowHeightOrInsetsChanged\(", "LOS Taskbar refresh")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarInsetsController.kt",
        r"private fun setProviderInsets\(", "LOS Taskbar provider setter")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java",
        r"private final TaskbarDragLayer mDragLayer", "LOS Taskbar drag layer")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java",
        r"private WindowManager.LayoutParams mWindowLayoutParams", "LOS Taskbar params")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java",
        r"private boolean mIsDestroyed", "LOS Taskbar destroyed flag")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java",
        r"private final WindowManager mWindowManager", "LOS Taskbar window manager")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java",
        r"public boolean isGestureNav\(\)", "LOS Taskbar gesture nav")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarControllers.java",
        r"public final NavbarButtonsViewController navbarButtonsViewController", "LOS Taskbar controllers")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/NavbarButtonsViewController.java",
        r"private TaskbarTransitions mTaskbarTransitions", "LOS Taskbar nav transitions")
    has(f"{launcher}/quickstep/src/com/android/launcher3/taskbar/TaskbarTransitions.java",
        r"class TaskbarTransitions extends BarTransitions", "LOS Taskbar transitions base")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/statusbar/phone/BarTransitions.java",
        r"protected final BarBackgroundDrawable mBarBackground", "LOS bar background")
    has(f"{launcher}/src/com/android/launcher3/graphics/ThemeManager.kt",
        r"@JvmField val INSTANCE", "LOS ThemeManager.INSTANCE")
    has(f"{launcher}/src/com/android/launcher3/LauncherAppState.kt",
        r"@JvmField var INSTANCE", "LOS LauncherAppState.INSTANCE")
    has(f"{lib}/cache/BaseIconCache.kt", r"@JvmField val workerHandler", "LOS BaseIconCache.workerHandler")

    # Telecom bridge.
    telecom = "packages_services/Telecomm/src/com/android/server/telecom"
    has(f"{telecom}/CallsManager.java",
        r"public void onCallFilteringComplete\(Call incomingCall, CallFilteringResult result,", "LOS Telecom filtering")
    has(f"{telecom}/CallsManager.java", r"public Context getContext\(\)", "LOS Telecom context")
    has(f"{telecom}/Call.java", r"public boolean hasProperty\(int property\)", "LOS Call.hasProperty")
    has(f"{telecom}/callfiltering/CallFilteringResult.java", r"public static class Builder", "LOS filtering builder")
    has(f"{telecom}/callfiltering/CallFilteringResult.java", r"public static Builder from\(", "LOS filtering builder.from")

    # Wallpaper/ambient binding is still the same service shape.
    has("frameworks_base/services/core/java/com/android/server/wallpaper/WallpaperManagerService.java",
        r"boolean bindWallpaperDescriptionLocked\(WallpaperDescription description", "LOS ambient bind")

    print("PASS: LineageOS 24.0 source contract "
          "(framework/window, SystemUI, Launcher3/iconloader, Telecom, wallpaper); "
          "static source only, no device behavior implied")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
