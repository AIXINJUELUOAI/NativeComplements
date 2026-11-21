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
                   "packages_services/Telecomm", "packages_apps_ThemePicker",
                   "frameworks_opt_telephony", "packages_services_Telephony"):
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
        "telephony": "frameworks_opt_telephony",
        "telephony_app": "packages_services_Telephony",
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
    has(f"{ui}/shade/NotificationPanelViewController.java",
        r"private final SplitShadeStateController mSplitShadeStateController",
        "LOS panel split-shade interface field")
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
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButtonController.java",
        r"private RotationButton mRotationButton", "LOS rotation button interface")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButton.java",
        r"default View getCurrentView\(\)", "LOS rotation current view")
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

    # Additional post-integration ABI shapes: Lifecycle/Doze/Compose shade/desktop sleep.
    has("Launcher3/src/com/android/launcher3/Launcher.java",
        r"public LauncherDragController getDragController\(\)", "LOS covariant Launcher drag controller")
    has("Launcher3/src/com/android/launcher3/dragndrop/LauncherDragController.java",
        r"class LauncherDragController extends DragController", "LOS LauncherDragController base")
    has(f"{ui}/doze/DozeMachine.java", r"private final DozeHost mDozeHost;", "LOS DozeMachine host interface")
    has(f"{ui}/doze/DozeMachine.java", r"private void transitionTo\(State requestedState, int pulseReason\)",
        "LOS DozeMachine transition")
    has(f"{ui}/doze/DozeMachine.java", r"public void requestState\(State requestedState\)",
        "LOS DozeMachine request")
    for state in ("DOZE_AOD", "DOZE_AOD_PAUSING", "DOZE_AOD_PAUSED"):
        has(f"{ui}/doze/DozeMachine.java", rf"\b{state}\b", f"LOS doze state {state}")
    has(f"{ui}/doze/DozeService.java", r"private DozeMachine mDozeMachine;", "LOS DozeService machine")
    has(f"{ui}/doze/DozeService.java", r"public void onCreate\(\)", "LOS DozeService create")
    has(f"{ui}/doze/DozeService.java", r"public void onDestroy\(\)", "LOS DozeService destroy")
    has(f"{ui}/doze/DozeTriggers.java", r"private final DozeSensors mDozeSensors;", "LOS DozeTriggers sensors")
    has(f"{ui}/statusbar/phone/DozeServiceHost.java", r"private boolean mAlwaysOnSuppressed;",
        "LOS DozeServiceHost suppressed field")
    has(f"{ui}/statusbar/phone/DozeServiceHost.java", r"private final BatteryController mBatteryController;",
        "LOS DozeServiceHost battery field")
    has(f"{ui}/doze/DozeSensors.java", r"private final ProximitySensor mProximitySensor;",
        "LOS DozeSensors proximity")
    has(f"{ui}/util/sensors/ProximitySensorImpl.java", r"protected boolean mPaused;", "LOS proximity paused")
    has(f"{ui}/shade/NotificationShadeWindowControllerImpl.java", r"private ViewGroup mWindowRootView;",
        "LOS shade root ViewGroup")
    has(f"{ui}/shade/NotificationShadeWindowControllerImpl.java",
        r"private Consumer<Integer> mScrimsVisibilityListener;", "LOS scrims Consumer")
    has(f"{ui}/shade/NotificationShadeWindowState.kt", r"@JvmField var keyguardShowing", "LOS shade state fields")
    has(f"{ui}/keyguard/shared/model/TransitionStep.kt", r"data class TransitionStep", "LOS transition step")
    has(f"{ui}/statusbar/LightRevealScrim.kt", r"var revealAmount: Float", "LOS reveal amount")
    has(f"{ui}/statusbar/phone/ScreenOffAnimationController.kt", r"private val animations: List<ScreenOffAnimation>",
        "LOS screen-off animations")
    has(f"{ui}/statusbar/phone/UnlockedScreenOffAnimationController.kt",
        r"private var lightRevealAnimationPlaying = false", "LOS unlocked animation flag")
    has(f"{ui}/statusbar/phone/UnlockedScreenOffAnimationController.kt",
        r"private var decidedToAnimateGoingToSleep: Boolean\? = null", "LOS unlocked decision")
    has(f"{ui}/biometrics/AuthRippleView.kt", r"override fun onDraw\(canvas: Canvas\)", "LOS auth ripple draw")
    has(f"{ui}/wallpapers/data/repository/WallpaperRepository.kt",
        r"@ShadeDisplayAware configurationInteractor: ConfigurationInteractor", "LOS wallpaper repo interface")
    has(f"{ui}/wallpapers/domain/interactor/WallpaperInteractor.kt",
        r"val wallpaperRepository: WallpaperRepository", "LOS wallpaper interactor interface")
    has("frameworks_base/packages/SystemUI/pods/util/settings/src/api/UserSettingsProxy.kt",
        r"public fun getIntForUser\(name: String, default: Int, userHandle: Int\): Int",
        "LOS settings name-first")
    has(f"{ui}/shade/data/repository/ShadeConfigRepository.kt",
        r"val legacyUseSplitShade: Flow<Boolean>", "LOS shade getter flow")
    has(f"{ui}/common/ui/data/repository/ConfigurationRepository.kt",
        r"override val onConfigurationChange: Flow<Unit>", "LOS config change flow")

    # Desktop double-tap sleep and the exact launcher child geometry.
    has("Launcher3/src/com/android/launcher3/touch/WorkspaceTouchListener.java",
        r"private final Launcher mLauncher;", "LOS desktop launcher field")
    has("Launcher3/src/com/android/launcher3/touch/WorkspaceTouchListener.java",
        r"private final Workspace<\?> mWorkspace;", "LOS desktop workspace field")
    has("Launcher3/src/com/android/launcher3/touch/WorkspaceTouchListener.java",
        r"public boolean onTouch\(View view, MotionEvent ev\)", "LOS desktop touch entry")
    has("Launcher3/src/com/android/launcher3/PagedView.java", r"public boolean isPageInTransition\(\)",
        "LOS paged transition")
    has("Launcher3/src/com/android/launcher3/PagedView.java", r"public boolean isHandlingTouch\(\)",
        "LOS paged handling")
    has("Launcher3/src/com/android/launcher3/PagedView.java", r"public View getPageAt\(int index\)",
        "LOS paged page access")
    has("Launcher3/src/com/android/launcher3/CellLayout.java", r"public void pointToCellExact\(int x, int y, int\[\] result\)",
        "LOS cell mapping")
    has("Launcher3/src/com/android/launcher3/CellLayout.java", r"public boolean isOccupied\(int x, int y\)",
        "LOS cell occupancy")
    has("Launcher3/src/com/android/launcher3/CellLayout.java",
        r"public void cellToRect\(int cellX, int cellY, int cellHSpan, int cellVSpan, Rect resultRect\)",
        "LOS cell geometry")
    has("Launcher3/src/com/android/launcher3/Workspace.java", r"private boolean mIsSwitchingState = false;",
        "LOS workspace switch flag")
    has("Launcher3/src/com/android/launcher3/Workspace.java", r"private boolean mOverlayShown = false;",
        "LOS workspace overlay flag")
    has("Launcher3/src/com/android/launcher3/AbstractFloatingView.java",
        r"public static AbstractFloatingView getTopOpenView\(ActivityContext activity\)",
        "LOS floating view probe")
    has("Launcher3/src/com/android/launcher3/Launcher.java",
        r"public LauncherDragController getDragController\(\)", "LOS desktop drag owner")
    has("Launcher3/src/com/android/launcher3/dragndrop/DragController.java",
        r"public boolean isDragging\(\)", "LOS drag state")

    # Icon animation probe and package refresh.
    has("Launcher3/src/com/android/launcher3/views/FloatingIconView.java",
        r"private IconLoadResult mIconLoadResult;", "LOS floating icon result")
    has("Launcher3/src/com/android/launcher3/views/FloatingIconView.java",
        r"private ClipIconView mClipIconView;", "LOS floating clip view")
    has("Launcher3/src/com/android/launcher3/views/FloatingIconView.java",
        r"private void setIcon\(@Nullable Drawable drawable, @Nullable Drawable badge,", "LOS floating setIcon")
    has("Launcher3/src/com/android/launcher3/views/ClipIconView.java",
        r"private @Nullable Drawable mForeground;", "LOS clip foreground")
    has("Launcher3/src/com/android/launcher3/views/ClipIconView.java",
        r"private @Nullable Drawable mBackground;", "LOS clip background")
    has("Launcher3/src/com/android/launcher3/views/FloatingIconView.java",
        r"final ItemInfo itemInfo;", "LOS icon load item info")
    has("Launcher3/src/com/android/launcher3/views/FloatingIconView.java",
        r"final boolean isThemed;", "LOS icon load theme flag")
    has("Launcher3/src/com/android/launcher3/LauncherModel.kt", r"fun newModelCallbacks\(\)",
        "LOS model callbacks")
    has("Launcher3/src/com/android/launcher3/model/ModelLauncherCallbacks.kt",
        r"override fun onPackageChanged\(packageName: String, user: UserHandle\)",
        "LOS package changed callback")

    # Rotation suggestion uses the RotationButton interface, not a narrowed key view.
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButton.java",
        r"default boolean hide\(\)", "LOS rotation hide")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButtonController.java",
        r"private final Handler mUiHandler;", "LOS rotation handler")
    has("frameworks_base/packages/SystemUI/shared/src/com/android/systemui/shared/rotation/RotationButtonController.java",
        r"private boolean mPendingRotationSuggestion;", "LOS rotation pending")

    # Undim suggestion and notification listener.
    power = "frameworks_base/services/core/java/com/android/server/power"
    has(f"{power}/PowerManagerService.java",
        r"private boolean userActivityNoUpdateLocked\(final PowerGroup powerGroup, long eventTime,",
        "LOS power activity entry")
    has(f"{power}/PowerManagerService.java", r"private final Context mContext;", "LOS power context")
    has(f"{power}/PowerGroup.java", r"int getWakefulnessLocked\(\)", "LOS power group wakefulness")
    has(f"{power}/PowerGroup.java", r"int getGroupId\(\)", "LOS power group id")
    has(f"{power}/PowerGroup.java", r"public boolean isPolicyDimLocked\(\)", "LOS power group dim")
    has(f"{ui}/statusbar/NotificationListener.java",
        r"public void onNotificationPosted\(final StatusBarNotification sbn,", "LOS notification posted")
    has(f"{ui}/statusbar/NotificationListener.java",
        r"public void onNotificationRemoved\(StatusBarNotification sbn, RankingMap rankingMap,",
        "LOS notification removed")

    # Root back and linked-window server entry points.
    has(f"{wm}/ActivityClientController.java",
        r"public void onBackPressed\(IBinder token, IRequestFinishCallback callback\)",
        "LOS back press entry")
    has(f"{wm}/ActivityClientController.java",
        r"public boolean finishActivity\(IBinder token, int resultCode, Intent resultData,",
        "LOS finish activity entry")
    has(f"{wm}/WindowState.java", r"OnBackInvokedCallbackInfo getOnBackInvokedCallbackInfo\(\)",
        "LOS back callback info")
    has("frameworks_base/core/java/android/window/OnBackInvokedCallbackInfo.java",
        r"public final class OnBackInvokedCallbackInfo", "LOS back callback class")
    has(f"{wm}/ActivityTaskManagerService.java", r"private RecentTasks mRecentTasks;", "LOS recent tasks field")
    has(f"{wm}/RecentTasks.java", r"private boolean isRecentTasksLoaded\(int userId\)", "LOS recent loaded flag")
    has(f"{wm}/ActivityRecord.java", r"final ComponentName mActivityComponent;", "LOS activity component")
    has("frameworks_base/core/java/android/view/Choreographer.java",
        r"public static Choreographer getSfInstance\(\)", "LOS SF choreographer")
    has("frameworks_base/core/java/android/view/Choreographer.java",
        r"public void postVsyncCallback\(@NonNull VsyncCallback callback\)", "LOS vsync callback")
    has("frameworks_base/core/java/android/view/Choreographer.java",
        r"public long getFrameTimeNanos\(\)", "LOS frame time")
    has("frameworks_base/core/java/android/view/Choreographer.java",
        r"public FrameTimeline getPreferredFrameTimeline\(\)", "LOS frame timeline")
    has(f"{wm}/Task.java", r"mLastSurfaceSize", "LOS task surface size")
    has(f"{wm}/Task.java", r"mLastTimeMoved", "LOS task move time")
    has(f"{wm}/WindowState.java", r"mLastReportedConfiguration", "LOS window reported config")
    has(f"{wm}/WindowState.java", r"getMergedConfiguration\(", "LOS merged configuration")
    has(f"{wm}/DisplayPolicy.java", r"mTopFullscreenOpaqueWindowState", "LOS top fullscreen")
    has(f"{wm}/WindowManagerService.java", r"mHidingNonSystemOverlayWindows", "LOS overlay hide list")
    has(f"{wm}/WindowManagerService.java",
        r"public int addWindow\(Session session, IWindow client, LayoutParams attrs,", "LOS addWindow int")
    has("frameworks_base/apex/jobscheduler/framework/java/android/os/PowerExemptionManager.java",
        r"public void addToTemporaryAllowList\(", "LOS temporary allow list")
    has("frameworks_base/apex/jobscheduler/framework/java/android/os/PowerExemptionManager.java",
        r"REASON_OTHER", "LOS allow-list reason")
    has("frameworks_base/core/java/android/view/ViewRootImpl.java",
        r"public BackgroundBlurDrawable createBackgroundBlurDrawable\(\)", "LOS blur drawable")
    has("frameworks_base/core/java/com/android/internal/graphics/drawable/BackgroundBlurDrawable.java",
        r"public void setBlurRadius\(int blurRadius\)", "LOS blur radius")
    has("frameworks_base/core/java/com/android/internal/graphics/drawable/BackgroundBlurDrawable.java",
        r"public void setCornerRadius\(float cornerRadius\)", "LOS blur corner")
    has("frameworks_base/core/java/com/android/internal/graphics/drawable/BackgroundBlurDrawable.java",
        r"public void setColor\(@ColorInt int color\)", "LOS blur color")
    has("frameworks_base/core/java/android/view/WindowManager.java",
        r"public void setTrustedOverlay\(\)", "LOS trusted overlay")

    # Alive's remaining sleep/content/ambient source contracts.
    has(f"{ui}/shade/NotificationShadeWindowControllerImpl.java",
        r"private void applyWindowLayoutParams\(\)", "LOS shade layout apply")
    has(f"{ui}/shade/NotificationShadeWindowControllerImpl.java",
        r"private void apply\(NotificationShadeWindowState state\)", "LOS shade state apply")
    has(f"{ui}/statusbar/policy/KeyguardStateControllerImpl.java",
        r"private boolean mShowing;", "LOS keyguard showing")
    has(f"{ui}/statusbar/policy/KeyguardStateControllerImpl.java",
        r"private boolean mOccluded;", "LOS keyguard occluded")
    has(f"{ui}/statusbar/policy/KeyguardStateControllerImpl.java",
        r"private boolean mKeyguardGoingAway;", "LOS keyguard going away")
    has(f"{ui}/statusbar/policy/KeyguardStateControllerImpl.java",
        r"private boolean mKeyguardFadingAway;", "LOS keyguard fading")
    has(f"{ui}/keyguard/KeyguardService.java",
        r"public void onStartedGoingToSleep\(@PowerManager\.GoToSleepReason int pmSleepReason\)",
        "LOS keyguard sleep")
    has(f"{ui}/keyguard/KeyguardService.java",
        r"public void onStartedWakingUp\(", "LOS keyguard wake")
    has(f"{ui}/statusbar/phone/ScreenOffAnimationController.kt",
        r"override fun onStartedGoingToSleep\(\)", "LOS screen off entry")
    has(f"{ui}/statusbar/LightRevealScrim.kt", r"var revealEffect: LightRevealEffect = LiftReveal",
        "LOS reveal effect")
    has(f"{ui}/statusbar/LightRevealScrim.kt", r"override fun onDraw\(canvas: Canvas\)", "LOS scrim draw")
    has("frameworks_base/packages/SystemUI/src/com/android/keyguard/ClockEventController.kt",
        r"private var smallClockFrame: ViewGroup\? = null", "LOS clock frame")
    has("frameworks_base/packages/SystemUI/src/com/android/keyguard/ClockEventController.kt",
        r"private fun handleDoze\(doze: Float\)", "LOS clock doze")
    has(f"{ui}/statusbar/notification/stack/NotificationStackScrollLayoutController.java",
        r"private NotificationStackScrollLayout mView;", "LOS notification stack view")
    has(f"{ui}/statusbar/notification/stack/NotificationStackScrollLayoutController.java",
        r"private void updateAlpha\(\)", "LOS notification alpha")
    has(f"{ui}/statusbar/notification/stack/NotificationStackScrollLayoutController.java",
        r"private float mMaxAlphaForKeyguard = 1.0f;", "LOS notification keyguard alpha")
    has(f"{ui}/wallpapers/data/repository/WallpaperRepository.kt",
        r"override val lockscreenWallpaperInfo: StateFlow<WallpaperInfo\?>", "LOS lock wallpaper")
    has(f"{ui}/wallpapers/data/repository/WallpaperRepository.kt",
        r"override val wallpaperInfo: StateFlow<WallpaperInfo\?>", "LOS home wallpaper")
    has(f"{ui}/wallpapers/data/repository/WallpaperRepository.kt",
        r"override val wallpaperSupportsAmbientMode: Flow<Boolean>", "LOS ambient wallpaper")
    has(f"{ui}/user/data/repository/UserRepositoryImpl.kt",
        r"override val selectedUser: StateFlow<SelectedUserModel>", "LOS selected user")
    has("frameworks_base/packages/SystemUI/pods/user/data/src/api/model/SelectedUserModel.kt",
        r"public data class SelectedUserModel", "LOS selected user model")
    has("frameworks_base/packages/SystemUI/pods/user/data/src/api/model/SelectedUserModel.kt",
        r"SELECTION_COMPLETE", "LOS selection complete")
    has("frameworks_base/services/core/java/com/android/server/wallpaper/WallpaperData.java",
        r"final int userId;", "LOS wallpaper user")
    has("frameworks_base/services/core/java/com/android/server/wallpaper/WallpaperManagerService.java",
        r"boolean bindWallpaperDescriptionLocked\(WallpaperDescription description, boolean force,\s*boolean fromUser, WallpaperData wallpaper, IRemoteCallback reply\)",
        "LOS ambient bind exact")

    # Signal stack and telephony callback classes.
    has(f"{ui}/statusbar/pipeline/shared/ui/composable/StackedMobileIcon.kt",
        r"fun StackedMobileIcon\(viewModel: StackedMobileIconViewModel, modifier: Modifier = Modifier\)",
        "LOS stacked icon root")
    has(f"{ui}/statusbar/pipeline/shared/ui/composable/StackedMobileIcon.kt",
        r"private fun StackedMobileIcon\(\s*viewModel: DualSim,\s*color: Color,\s*contentDescription: String\?,",
        "LOS stacked icon render")
    has(f"{ui}/statusbar/pipeline/mobile/ui/model/DualSim.kt",
        r"val primary: SignalIconModel\.CellularTypeIconModel\.Cellular", "LOS dual primary")
    has(f"{ui}/statusbar/pipeline/mobile/ui/model/DualSim.kt",
        r"val secondary: SignalIconModel\.CellularTypeIconModel\.Cellular", "LOS dual secondary")
    has(f"{ui}/statusbar/pipeline/mobile/ui/model/DualSim.kt", r"fun tryParseDualSim\(",
        "LOS dual parser")
    has(f"{ui}/statusbar/pipeline/mobile/domain/model/SignalIconModel.kt",
        r"data class Cellular\(", "LOS cellular model")
    has(f"{ui}/statusbar/pipeline/mobile/data/repository/prod/MobileConnectionRepositoryImpl.kt",
        r"override fun onSignalStrengthsChanged\(signalStrength: SignalStrength\)",
        "LOS signal callback")
    has(f"{ui}/statusbar/pipeline/mobile/data/repository/prod/MobileConnectionRepositoryImpl.kt",
        r"override fun onServiceStateChanged\(serviceState: ServiceState\)",
        "LOS service callback")
    has(f"{ui}/statusbar/pipeline/mobile/data/repository/prod/MobileConnectionRepositoryImpl.kt",
        r"override fun onCarrierRoamingNtnModeChanged\(active: Boolean\)",
        "LOS NTN callback")

    # SMS tracker/handler members and telecom builder coverage.
    sms = "frameworks_opt_telephony/src/java/com/android/internal/telephony"
    telecom = "packages_services/Telecomm/src/com/android/server/telecom"
    has(f"{sms}/InboundSmsHandler.java", r"public final class SmsBroadcastReceiver extends BroadcastReceiver",
        "LOS SMS receiver")
    has(f"{sms}/InboundSmsHandler.java", r"private class DeliveringState extends State", "LOS SMS state")
    has(f"{sms}/InboundSmsTracker.java", r"public byte\[\] getPdu\(\)", "LOS tracker PDU")
    has(f"{sms}/InboundSmsTracker.java", r"public int getSubId\(\)", "LOS tracker sub id")
    has(f"{sms}/InboundSmsTracker.java", r"public int getMessageCount\(\)", "LOS tracker count")
    has(f"{sms}/InboundSmsTracker.java", r"public String\[\] getDeleteWhereArgs\(\)", "LOS tracker delete args")
    has(f"{sms}/BlockChecker.java",
        r"public static boolean isBlocked\(Context context, String phoneNumber, Bundle extras\)",
        "LOS block checker")
    has(f"{telecom}/Call.java", r"public boolean isIncoming\(\)", "LOS call incoming")
    has(f"{telecom}/Call.java", r"public boolean isSelfManaged\(\)", "LOS call self managed")
    has(f"{telecom}/Call.java", r"public boolean isEmergencyCall\(\)", "LOS call emergency")
    has(f"{telecom}/Call.java", r"public boolean isInECBM\(\)", "LOS call ecbm")
    has(f"{telecom}/Call.java", r"public Uri getHandle\(\)", "LOS call handle")
    has(f"{telecom}/Call.java", r"public int getState\(\)", "LOS call state")
    has(f"{telecom}/Call.java", r"public UserHandle getAssociatedUser\(\)", "LOS call user")
    has(f"{telecom}/Call.java", r"public int getHandlePresentation\(\)", "LOS call presentation")
    has(f"{telecom}/callfiltering/CallFilteringResult.java",
        r"public Builder setCallScreeningResponse\(\s*ParcelableCallResponse response, boolean isFromSystemDialer\)",
        "LOS screening response")
    has(f"{telecom}/callfiltering/CallFilteringResult.java",
        r"public Builder setShouldAllowCall\(boolean shouldAllowCall\)", "LOS allow setter")
    has(f"{telecom}/callfiltering/CallFilteringResult.java",
        r"public Builder setShouldReject\(boolean shouldReject\)", "LOS reject setter")
    has(f"{telecom}/PhoneAccountRegistrar.java",
        r"public int getSubscriptionIdForPhoneAccount\(PhoneAccountHandle accountHandle\)",
        "LOS account subscription")

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

    # SMS blocking stays inside the source-verified phone-stack ingress path.
    sms = "frameworks_opt_telephony/src/java/com/android/internal/telephony"
    has("packages_services_Telephony/AndroidManifest.xml", r'package="com.android\.phone"',
        "LOS phone package identity")
    has(f"{sms}/InboundSmsHandler.java", r"protected final Context mContext;", "LOS inbound context")
    has(f"{sms}/InboundSmsHandler.java", r"private final ContentResolver mResolver;", "LOS inbound resolver")
    has(f"{sms}/InboundSmsHandler.java", r"protected static final Uri sRawUriPermanentDelete",
        "LOS permanent delete URI")
    has(f"{sms}/InboundSmsHandler.java", r"private boolean processMessagePart\(InboundSmsTracker tracker\)",
        "LOS SMS part entry")
    has(f"{sms}/InboundSmsHandler.java",
        r"private boolean filterSms\(byte\[\]\[\] pdus, int destPort,\s*InboundSmsTracker tracker,"
        r"\s*SmsBroadcastReceiver resultReceiver, boolean userUnlocked,\s*boolean block\)",
        "LOS SMS filter shape")
    has(f"{sms}/InboundSmsHandler.java",
        r"private void deleteFromRawTable\(String deleteWhere, String\[\] deleteWhereArgs,\s*int deleteType\)",
        "LOS SMS delete shape")
    has(f"{sms}/InboundSmsTracker.java", r"public String getDisplayAddress\(\)", "LOS tracker display address")
    has(f"{sms}/InboundSmsTracker.java", r"public String getDeleteWhere\(\)", "LOS tracker delete where")
    has(f"{sms}/BlockChecker.java", r"public static int getBlockStatus\(Context context, String phoneNumber, Bundle extras\)",
        "LOS block status")

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
          "(framework/window, SystemUI, Launcher3/iconloader, Telecom, SMS/telephony, wallpaper); "
          "static source only, no device behavior implied")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
