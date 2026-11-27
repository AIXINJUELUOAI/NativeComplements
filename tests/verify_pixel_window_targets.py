"""Read both supplied hosts, not cached inventories; signature evidence is not device acceptance.

Run with Python only. Missing archives are SKIP (exit 2), never PASS.
SensorService is native: its UID shell-command semantics need device/native-source evidence.
"""
from pathlib import Path
import json
import hashlib
import re
import sys
import zipfile
import verify_test4_target as dex

if not __debug__:
    raise RuntimeError("Run without -O: target checks require assertions")

ROOT = Path(__file__).resolve().parents[1]
WM = "Lcom/android/server/wm/"
SYS = "Lcom/android/systemui/"
CHECKS = []


def method(owner, name, signature):
    CHECKS.append((owner, "methods", name, signature))


def field(owner, name, signature):
    CHECKS.append((owner, "fields", name, signature))


SOURCE = ROOT / "app/src/main/java/dev/local/sunwindowdemo/features"
source_names = set()
for folder in ("floatingwindow/legacy", "suggestions", "barrage", "motion", "edgetouch", "desktop"):
    for source in (SOURCE / folder).rglob("*"):
        if source.suffix in (".java", ".kt"):
            source_names.update(re.findall(r'"([A-Za-z_$][A-Za-z0-9_$]*)"', source.read_text(encoding="utf-8")))

# Reuse only source-referenced members, not entire incidental class inventories.
for inventory in ("new-members", "rotation-members", "viewport-members", "handoff-members",
                  "launch-input-members", "window-request-members", "size-compat-members", "mini-focus-members", "audio-gain-members"):
    snapshot = json.loads((ROOT / f"tests/target/{inventory}.json").read_text(encoding="utf-8"))
    for owner, body in snapshot["classes"].items():
        for kind in ("methods", "fields"):
            for member in body.get(kind, []):
                if member["name"] not in source_names or member["name"] == "set":
                    continue
                if owner == WM + "WindowManagerService;" and member["name"] == "registerPointerEventListener":
                    continue
                if owner == WM + "Task;" and member["name"] == "isResizeable":
                    continue
                # Both ActivityStarter/input callback ABI variants are checked by test4.verify.
                if (owner == WM + "ActivityStarter;" and member["name"] in ("startActivityUnchecked", "handleStartResult")
                    or member["name"] in ("onInputEvent", "addWindow")):
                    continue
                CHECKS.append((owner, kind, member["name"], member.get("descriptor", member.get("type"))))

# Actual preflight and execution members absent from the older inventories.
for name, signature in (
    ("prepareSurfaces", "()V"), ("onConfigurationChanged", "(Landroid/content/res/Configuration;)V"),
    ("computeFreeformBounds", "(Landroid/graphics/Rect;Landroid/content/res/Configuration;)V"),
    ("setWindowingMode", "(I)V"), ("setBounds", "(Landroid/graphics/Rect;)I"),
    ("setAlwaysOnTop", "(Z)V"), ("setFocusable", "(Z)Z"), ("isFocusable", "()Z"),
    ("moveTaskToBack", "(" + WM + "Task;)Z"),
    ("moveTaskToBackInner", "(" + WM + "Task;" + WM + "Transition;)V"),
    ("updateSurfaceSize", "(Landroid/view/SurfaceControl$Transaction;)V"),
):
    method(WM + "Task;", name, signature)
for name, signature in (
    ("setState", "(" + WM + "ActivityRecord$State;Ljava/lang/String;)V"),
    ("getUid", "()I"), ("getPid", "()I"), ("isFocusedActivityOnDisplay", "()Z"),
    ("shouldBeResumed", "(" + WM + "ActivityRecord;)Z"),
    ("shouldPauseActivity", "(" + WM + "ActivityRecord;)Z"),
    ("shouldMakeActive", "(" + WM + "ActivityRecord;)Z"), ("canResumeByCompat", "()Z"),
    ("makeActiveIfNeeded", "(" + WM + "ActivityRecord;)Z"),
    ("shouldResumeActivity", "(" + WM + "ActivityRecord;)Z"),
    ("relaunchActivityLocked", "(ZI)V"),
):
    method(WM + "ActivityRecord;", name, signature)
method("Landroid/view/Choreographer;", "postVsyncCallback", "(Landroid/view/Choreographer$VsyncCallback;)V")
method("Landroid/view/Choreographer$FrameData;", "getFrameTimeNanos", "()J")
method("Landroid/view/Choreographer$FrameData;", "getPreferredFrameTimeline", "()Landroid/view/Choreographer$FrameTimeline;")
method("Landroid/view/Choreographer$FrameTimeline;", "getVsyncId", "()J")
method(WM + "WindowContainer;", "getLastLayer", "()I")
method(WM + "WindowContainer;", "getLastRelativeLayer", "()Landroid/view/SurfaceControl;")
method(WM + "WindowContainer;", "forAllTasks", "(Ljava/util/function/Consumer;)V")
method(WM + "WindowContainer;", "forAllWindows", "(Ljava/util/function/Consumer;Z)V")
method(WM + "RootWindowContainer;", "anyTaskForId", "(II)" + WM + "Task;")
method(WM + "RootWindowContainer;", "getTopDisplayFocusedLeafTask", "()" + WM + "Task;")
method(WM + "InsetsPolicy;", "updateSystemBars", "(" + WM + "WindowState;IIZ)V")
method(WM + "RecentTasks;", "isVisibleRecentTask", "(" + WM + "Task;)Z")
method(WM + "WindowState;", "canReceiveTouchInput", "()Z")
method(WM + "WindowState;", "canReceiveKeys", "(Z)Z")
method(WM + "WindowState;", "canAffectSystemUiFlags", "()Z")
method(WM + "TaskFragment;", "resumeTopActivity", "(" + WM + "ActivityRecord;Landroid/app/ActivityOptions;Z)Z")
method(WM + "ActivityRecordInputSink;", "getInputWindowHandleWrapper", "(Z)" + WM + "InputWindowHandleWrapper;")
field(WM + "ActivityRecordInputSink;", "mActivityRecord", WM + "ActivityRecord;")
method(WM + "InputWindowHandleWrapper;", "setInputConfigMasked", "(II)Z")
method("Landroid/hardware/input/InputManager;", "injectInputEvent", "(Landroid/view/InputEvent;II)Z")
method("Landroid/hardware/input/InputManager;", "injectInputEvent", "(Landroid/view/InputEvent;I)Z")
method("Landroid/os/ServiceManager;", "getService", "(Ljava/lang/String;)Landroid/os/IBinder;")
method("Landroid/os/IBinder;", "shellCommand", "(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;[Ljava/lang/String;Landroid/os/ShellCallback;Landroid/os/ResultReceiver;)V")
# Launcher owns a separate copy: validate it separately, never use the SystemUI copy.
ROTATION = SYS + "shared/rotation/RotationButtonController;"
for name, signature in (("setRotateSuggestionButtonState", "(ZZ)V"),):
    method(ROTATION, name, signature)
for name, signature in (("mPendingRotationSuggestion", "Z"), ("mUiHandler", "Landroid/os/Handler;"),
                        ("mCancelPendingRotationProposal", ROTATION[:-1] + "$$ExternalSyntheticLambda0;"),
                        ("mLastRotationSuggestion", "I"), ("mContext", "Landroid/content/Context;")):
    field(ROTATION, name, signature)
method("Lcom/android/quickstep/TouchInteractionHandler;", "onInputEvent", "(Landroid/view/InputEvent;)V")
for name in ("onCreate", "onDestroy"):
    method("Lcom/android/quickstep/TouchInteractionService;", name, "()V")
method("Lcom/android/quickstep/views/RecentsView;", "applyLoadPlan", "(Ljava/util/List;I)V")
method("Lcom/android/quickstep/views/RecentsView;", "reloadIfNeeded", "()V")
field("Lcom/android/quickstep/views/RecentsView;", "mAppliedTaskListChangeId", "I")
method("Lcom/android/quickstep/util/GroupTask;", "containsTask", "(I)Z")
method("Lcom/android/quickstep/util/GroupTask;", "getTasks", "()Ljava/util/List;")
method("Lcom/android/quickstep/LauncherBackAnimationController;", "applyTransform", "(Landroid/graphics/RectF;F)V")
method("Lcom/android/quickstep/LauncherBackAnimationController;", "onCancelFinished", "()V")
# Desktop blank-cell double tap: both actual Launcher archives, without long-press field ABI assumptions.
LAUNCHER = "Lcom/android/launcher3/"
method(LAUNCHER + "touch/WorkspaceTouchListener;", "onTouch", "(Landroid/view/View;Landroid/view/MotionEvent;)Z")
field(LAUNCHER + "touch/WorkspaceTouchListener;", "mLauncher", LAUNCHER + "Launcher;")
field(LAUNCHER + "touch/WorkspaceTouchListener;", "mWorkspace", LAUNCHER + "Workspace;")
field(LAUNCHER + "LauncherState;", "NORMAL", LAUNCHER + "LauncherState$3;")
method(LAUNCHER + "statemanager/StatefulContainer;", "isInState", "(" + LAUNCHER + "statemanager/BaseState;)Z")
method(LAUNCHER + "AbstractFloatingView;", "getTopOpenView", "(" + LAUNCHER + "views/ActivityContext;)" + LAUNCHER + "AbstractFloatingView;")
for name in ("mIsSwitchingState", "mOverlayShown"):
    field(LAUNCHER + "Workspace;", name, "Z")
for name in ("isPageInTransition", "isHandlingTouch"):
    method(LAUNCHER + "PagedView;", name, "()Z")
method(LAUNCHER + "PagedView;", "getCurrentPage", "()I")
method(LAUNCHER + "PagedView;", "getPageAt", "(I)Landroid/view/View;")
method(LAUNCHER + "Launcher;", "getDragController", "()" + LAUNCHER + "dragndrop/DragController;")
method(LAUNCHER + "dragndrop/DragController;", "isDragging", "()Z")
method(LAUNCHER + "CellLayout;", "pointToCellExact", "(II[I)V")
method(LAUNCHER + "CellLayout;", "isOccupied", "(II)Z")
method(LAUNCHER + "CellLayout;", "cellToRect", "(IIIILandroid/graphics/Rect;)V")
for name in ("mCountX", "mCountY"):
    field(LAUNCHER + "CellLayout;", name, "I")
method("Landroid/app/ActivityManager;", "getRunningTasks", "(I)Ljava/util/List;")
field("Landroid/app/TaskInfo;", "displayId", "I")
field("Landroid/app/TaskInfo;", "userId", "I")
field("Landroid/app/TaskInfo;", "topActivity", "Landroid/content/ComponentName;")
method("Landroid/app/KeyguardManager;", "isKeyguardLocked", "()Z")
# PowerManager is absent from the newer framework archive: runtime preflight is required;
# its goToSleep(JII)V use is evidenced separately by the newer SystemUI's actual call sites.

# Game-corner input uses the existing filter; live WMS focus is checked at each new pointer.
field(WM + "WindowManagerService;", "mGlobalLock", WM + "WindowManagerGlobalLock;")
field(WM + "WindowManagerService;", "mRoot", WM + "RootWindowContainer;")
method(WM + "RootWindowContainer;", "getDisplayContent", "(I)" + WM + "DisplayContent;")
field(WM + "DisplayContent;", "mCurrentFocus", WM + "WindowState;")
method(WM + "DisplayContent;", "getImeWindow", "()" + WM + "WindowState;")
method(WM + "WindowState;", "isVisible", "()Z")
field(WM + "WindowState;", "mAttrs", "Landroid/view/WindowManager$LayoutParams;")
method(WM + "WindowState;", "getOwningUid", "()I")
method(WM + "WindowState;", "getTask", "()" + WM + "Task;")
# getWindowingMode is inherited from ConfigurationContainer, not declared on Task.
method(WM + "ConfigurationContainer;", "getWindowingMode", "()I")
field("Landroid/view/WindowManager$LayoutParams;", "packageName", "Ljava/lang/String;")
method("Landroid/view/MotionEvent;", "setDownTime", "(J)V")

# Swipe-up mini handoff (SwipeMini.resolve): declared-member lookups on both launchers.
QS = "Lcom/android/quickstep/"
END_TARGET = QS + "GestureState$GestureEndTarget;"
method(QS + "SwipeUpAnimationLogic;", "updateDisplacement", "(F)V")
field(QS + "SwipeUpAnimationLogic;", "mGestureState", QS + "GestureState;")
field(QS + "SwipeUpAnimationLogic;", "mContext", "Landroid/content/Context;")
method(QS + "AbsSwipeUpHandler;", "performHapticFeedback", "()V")
method(QS + "AbsSwipeUpHandler;", "invalidateHandler", "()V")
method(QS + "AbsSwipeUpHandler;", "invalidateHandlerWithLauncher", "()V")
method(QS + "LauncherSwipeHandlerV2;", "findWorkspaceView", "(Ljava/util/List;" + QS + "views/TaskView;)Landroid/view/View;")
field(QS + "AbsSwipeUpHandler;", "mRecentsView", QS + "views/RecentsView;")
method(QS + "GestureState;", "getRunningTaskIds", "(Z)[I")
method(QS + "GestureState;", "getEndTarget", "()" + END_TARGET)
# Passive ownership/settlement callbacks for an already-open normal floating window.
for name, signature in (
    ("onGestureStarted", "(Z)V"),
    ("onGestureCancelled", "()V"),
    ("onRecentsAnimationCanceled", "(Ljava/util/HashMap;)V"),
    ("onSettledOnEndTarget", "()V"),
):
    method(QS + "AbsSwipeUpHandler;", name, signature)
# Default-window navigation guard: inspect the native app and desktop admissions separately.
OTHER_ACTIVITY = QS + "inputconsumers/OtherActivityInputConsumer;"
MOTION = "(Landroid/view/MotionEvent;)"
for owner, name, signature in (
    (OTHER_ACTIVITY, "onMotionEvent", MOTION + "V"),
    (OTHER_ACTIVITY, "forceCancelGesture", MOTION + "V"),
    (OTHER_ACTIVITY, "startTouchTrackingForWindowAnimation", "(J)V"),
    (QS + "GestureState;", "isInExtendedSlopRegion", "()Z"),
    (QS + "RecentsAnimationDeviceState;", "isFullyGesturalNavMode", "()Z"),
    (QS + "inputconsumers/LauncherWithoutFocusInputConsumer;", "onMotionEvent", MOTION + "V"),
    ("Lcom/android/launcher3/uioverrides/touchcontrollers/NoButtonNavbarToOverviewTouchController;", "canInterceptTouch", MOTION + "Z"),
    ("Lcom/android/launcher3/uioverrides/touchcontrollers/NavBarToHomeTouchController;", "onControllerInterceptTouchEvent", MOTION + "Z"),
):
    method(owner, name, signature)
for name, descriptor in (("mDeviceState", QS + "RecentsAnimationDeviceState;"), ("mGestureState", QS + "GestureState;"),
                         ("mDownPos", "Landroid/graphics/PointF;"), ("mActivePointerId", "I"),
                         ("mSquaredTouchSlop", "F"), ("mPassedPilferInputSlop", "Z")):
    field(OTHER_ACTIVITY, name, descriptor)
for name in ("HOME", "RECENTS"):
    field(END_TARGET, name, END_TARGET)
field(QS + "SwipeUpAnimationLogic;", "mRemoteTargetHandles", "[" + QS + "RemoteTargetGluer$RemoteTargetHandle;")
method(QS + "RemoteTargetGluer$RemoteTargetHandle;", "getTaskViewSimulator", "()" + QS + "util/TaskViewSimulator;")
method(QS + "util/TaskViewSimulator;", "getCurrentCropRect", "()Landroid/graphics/RectF;")
method(QS + "util/TaskViewSimulator;", "getCurrentMatrix", "()Landroid/graphics/Matrix;")
method(QS + "util/TaskViewSimulator;", "getCurrentCornerRadius", "()F")
field(QS + "AbsSwipeUpHandler;", "mDeviceState", QS + "RecentsAnimationDeviceState;")
method(QS + "RecentsAnimationDeviceState;", "getNavBarPosition", "()" + QS + "util/NavBarPosition;")
method(QS + "util/NavBarPosition;", "isLeftEdge", "()Z")
method(QS + "util/NavBarPosition;", "isRightEdge", "()Z")
method("Landroid/view/Display;", "getDisplayInfo", "(Landroid/view/DisplayInfo;)Z")
method("Landroid/view/DisplayInfo;", "<init>", "()V")
field("Landroid/view/DisplayInfo;", "logicalWidth", "I")
field("Landroid/view/DisplayInfo;", "logicalHeight", "I")
method(QS + "RemoteTargetGluer$RemoteTargetHandle;", "getTransformParams", "()" + QS + "util/TransformParams;")
method(QS + "util/TransformParams;", "setTargetAlpha", "(F)" + QS + "util/TransformParams;")
method(WM + "Task;", "isVisibleRequested", "()Z")
field(SYS + "screenshot/ScreenshotController;", "window", SYS + "screenshot/ScreenshotWindow;")
method(SYS + "screenshot/ScreenshotWindow;", "removeWindow", "()V")
field(SYS + "shared/system/InputMonitorCompat;", "mInputMonitor", "Landroid/view/InputMonitor;")
field(SYS + "navigationbar/gestural/DisplayBackGestureHandlerImpl;", "inputMonitorCompat", SYS + "shared/system/InputMonitorCompat;")
method("Landroid/view/InputMonitor;", "pilferPointers", "()V")
# Barrage/shortcut suggestions receive notifications through the native listener.
# Only the separate opt-in OTP hide setting still cancels matched SMS notifications.
method("Landroid/service/notification/NotificationListenerService;", "cancelNotification", "(Ljava/lang/String;)V")
method("Landroid/service/notification/NotificationListenerService;", "onNotificationPosted", "(Landroid/service/notification/StatusBarNotification;)V")
method("Landroid/service/notification/NotificationListenerService;", "onNotificationRemoved", "(Landroid/service/notification/StatusBarNotification;)V")
method("Landroid/app/Instrumentation;", "callApplicationOnCreate", "(Landroid/app/Application;)V")
method(SYS + "statusbar/NotificationListener;", "onNotificationPosted", "(Landroid/service/notification/StatusBarNotification;Landroid/service/notification/NotificationListenerService$RankingMap;)V")
method(SYS + "statusbar/NotificationListener;", "onNotificationRemoved", "(Landroid/service/notification/StatusBarNotification;Landroid/service/notification/NotificationListenerService$RankingMap;I)V")
# Barrage replaces only the HUN decision, never cancels the native notification.
BARRAGE_PROVIDER = SYS + "statusbar/notification/interruption/VisualInterruptionDecisionProviderImpl;"
BARRAGE_DECISION = BARRAGE_PROVIDER[:-1] + "$DecisionImpl;"
BARRAGE_ENTRY = SYS + "statusbar/notification/collection/NotificationEntry;"
for name in ("makeAndLogHeadsUpDecision", "makeUnloggedHeadsUpDecision"):
    method(BARRAGE_PROVIDER, name, "(" + BARRAGE_ENTRY + ")" + BARRAGE_DECISION)
method(BARRAGE_DECISION, "<init>", "(Ljava/lang/String;Z)V")
field(BARRAGE_DECISION, "shouldInterrupt", "Z")
field(BARRAGE_ENTRY, "mSbn", "Landroid/service/notification/StatusBarNotification;")
BARRAGE_CALLER = SYS + "statusbar/notification/collection/coordinator/HeadsUpCoordinator$mNotifCollectionListener$1;"
method(BARRAGE_CALLER, "onEntryAdded", "(" + BARRAGE_ENTRY + ")V")
method(BARRAGE_CALLER, "onEntryUpdated", "(" + BARRAGE_ENTRY + SYS + "statusbar/notification/collection/notifcollection/UpdateSource;)V")
method(BARRAGE_CALLER, "onRankingApplied", "()V")
method("Lcom/android/server/power/PowerManagerService;", "userActivityNoUpdateLocked", "(Lcom/android/server/power/PowerGroup;JIII)Z")
field("Lcom/android/server/power/PowerManagerService;", "mContext", "Landroid/content/Context;")
for name, signature in (("getGroupId", "()I"), ("getWakefulnessLocked", "()I"), ("isPolicyDimLocked", "()Z")):
    method("Lcom/android/server/power/PowerGroup;", name, signature)
method("Landroid/os/PowerExemptionManager;", "addToTemporaryAllowList", "(Ljava/lang/String;ILjava/lang/String;J)V")
field(WM + "ActivityTaskManagerService;", "mShuttingDown", "Z")
for owner in (WM + "Task;", WM + "TaskFragment;"):
    method(owner, "shouldSleepActivities", "()Z")
    method(owner, "sleepIfPossible", "(Z)Z")
method(WM + "ActivityRecord;", "shouldBeVisibleUnchecked", "()Z")
method(WM + "ActivityRecord;", "updateAndCheckVisibility", "(ZZ)Z")
method(WM + "ActivityStarter;", "computeLaunchParams", "(" + WM + "ActivityRecord;" + WM + "ActivityRecord;" + WM + "Task;)V")
method(WM + "ActivityStarter;", "computeTargetTask", "()" + WM + "Task;")
method(WM + "ActivityStarter;", "resolveReusableTask", "(Z)" + WM + "Task;")
field(WM + "ActivityStarter;", "mStartActivity", WM + "ActivityRecord;")
for owner, name, signature in (
    ("WindowManagerService", "main", "(Landroid/content/Context;Lcom/android/server/input/InputManagerService;ZLcom/android/server/policy/WindowManagerPolicy;" + WM + "ActivityTaskManagerService;)" + WM + "WindowManagerService;"),
    ("WindowManagerService", "systemReady", "()V"),
    ("Task", "supportsFreeformInDisplayArea", "(" + WM + "TaskDisplayArea;)Z"),
    ("TaskDisplayArea", "isValidWindowingMode", "(I" + WM + "ActivityRecord;" + WM + "Task;)Z"),
    ("WindowContainer", "getRelativePosition", "(Landroid/graphics/Point;)V"),
    ("WindowContainer", "getPendingTransaction", "()Landroid/view/SurfaceControl$Transaction;"),
    ("WindowContainer", "getRelativeDisplayRotation", "()I"),
    ("WindowContainer", "isAttached", "()Z"),
    ("WindowContainer", "getDisplayContent", "()" + WM + "DisplayContent;"),
    ("DisplayContent", "getStableRect", "(Landroid/graphics/Rect;)V"),
    ("DisplayContent", "getInsetsStateController", "()" + WM + "InsetsStateController;"),
    ("InsetsStateController", "getRawInsetsState", "()Landroid/view/InsetsState;"),
    ("Task", "moveToFront", "(Ljava/lang/String;)V"),
    ("Task", "getRootActivity", "()" + WM + "ActivityRecord;"),
    ("ActivityRecord", "attachedToProcess", "()Z"),
    ("ActivityRecord", "isRelaunching", "()Z"),
    ("ActivityRecord", "getState", "()" + WM + "ActivityRecord$State;"),
    ("ActivityRecord", "moveFocusableActivityToTop", "(Ljava/lang/String;)Z"),
    ("DisplayContent", "setFocusedApp", "(" + WM + "ActivityRecord;)Z"),
    ("WindowManagerService", "updateFocusedWindowLocked", "(IZ)Z"),
    ("TaskFragment", "canBeResumed", "(" + WM + "ActivityRecord;)Z"),
    ("TaskFragment", "getVisibility", "(" + WM + "ActivityRecord;)I"),
    ("TaskFragment", "shouldSleepOrShutDownActivities", "()Z"),
    ("Transition", "assignLayersForFinishTransaction", "(" + WM + "WindowContainer;Landroid/view/SurfaceControl$Transaction;)V"),
    ("Transition", "setNoAnimation", "(" + WM + "WindowContainer;)V"),
    ("Transition", "addFlag", "(I)V"),
    ("Transition", "getFlags", "()I"),
    ("Transition", "onTransactionReady", "(ILandroid/view/SurfaceControl$Transaction;)V"),
    ("Transition", "buildFinishTransaction", "(Landroid/view/SurfaceControl$Transaction;Landroid/window/TransitionInfo;[" + WM + "DisplayContent;)V"),
    ("TransitionController", "requestStartTransition", "(" + WM + "Transition;" + WM + "Task;Landroid/window/RemoteTransition;Ljava/util/List;)" + WM + "Transition;"),
    ("TransitionController", "setNoAnimation", "(" + WM + "WindowContainer;)V"),
    ("TaskSnapshotController", "recordSnapshot", "(" + WM + "Task;)V"),
    ("RootWindowContainer", "resumeFocusedTasksTopActivities", "()Z"),
    ("RootWindowContainer", "ensureActivitiesVisible", "()V"),
    ("ActivityTaskManagerService", "deferWindowLayout", "()V"),
    ("ActivityTaskManagerService", "continueWindowLayout", "()V"),
    ("TaskChangeNotificationController", "notifyTaskStackChanged", "()V"),
):
    method(WM + owner + ";", name, signature)
method("Landroid/view/InsetsState;", "calculateInsets", "(Landroid/graphics/Rect;Landroid/graphics/Rect;IZ)Landroid/graphics/Insets;")
method("Landroid/view/InsetsState;", "set", "(Landroid/view/InsetsState;Z)V")
method("Lcom/android/server/SystemServer;", "startOtherServices", "(Lcom/android/server/utils/TimingsTraceAndSlog;)V")
field(SYS + "statusbar/notification/collection/NotificationEntry;", "mSbn", "Landroid/service/notification/StatusBarNotification;")
method("Landroid/app/PendingIntent;", "sendAndReturnResult", "(Landroid/content/Context;ILandroid/content/Intent;Landroid/app/PendingIntent$OnFinished;Landroid/os/Handler;Ljava/lang/String;Landroid/os/Bundle;)I")
# Suggestions read native facts and inject explicit OTP without constructing the task engine.
# Its local Access, like FrameworkAccess, resolves exact signatures through superclasses.
SUGGESTION_ENVIRONMENT_CHECKS = [
    (WM + "WindowManagerService;", "fields", "mContext", "Landroid/content/Context;"),
    (WM + "WindowManagerService;", "fields", "mRoot", WM + "RootWindowContainer;"),
    (WM + "WindowManagerService;", "fields", "mGlobalLock", WM + "WindowManagerGlobalLock;"),
    (WM + "WindowManagerService;", "fields", "mCurrentUserId", "I"),
    (WM + "WindowManagerService;", "fields", "mHidingNonSystemOverlayWindows", "Ljava/util/ArrayList;"),
    (WM + "RootWindowContainer;", "methods", "getTopDisplayFocusedLeafTask", "()" + WM + "Task;"),
    (WM + "DisplayContent;", "fields", "mDisplayId", "I"),
    (WM + "DisplayContent;", "methods", "forAllTasks", "(Ljava/util/function/Consumer;)V"),
    (WM + "WindowState;", "methods", "getOwningUid", "()I"),
    (WM + "WindowState;", "methods", "isVisible", "()Z"),
    (WM + "WindowState;", "methods", "getDisplayContent", "()" + WM + "DisplayContent;"),
    (WM + "WindowState;", "fields", "mAttrs", "Landroid/view/WindowManager$LayoutParams;"),
    (WM + "Task;", "fields", "mUserId", "I"),
    (WM + "Task;", "methods", "topRunningActivity", "()" + WM + "ActivityRecord;"),
    (WM + "Task;", "methods", "getDisplayContent", "()" + WM + "DisplayContent;"),
    (WM + "Task;", "methods", "getWindowingMode", "()I"),
    (WM + "Task;", "methods", "getActivityType", "()I"),
    (WM + "Task;", "methods", "isLeafTask", "()Z"),
    (WM + "Task;", "methods", "isVisibleRequested", "()Z"),
]
CHECKS.extend(SUGGESTION_ENVIRONMENT_CHECKS)
# Both addWindow ABIs are deliberately asserted below, not as alternative guesses.
ADD = "Landroid/view/AddWindowResult;"



def members(classes, owner, kind):
    """FrameworkAccess searches superclasses; getDeclaredMethod callers don't use this."""
    seen = set()
    while owner in classes and owner not in seen:
        seen.add(owner)
        yield from classes[owner][kind]
        owner = classes[owner]["superclass"]


# These source lookups use FrameworkAccess or suggestion-local Access superclass walks.
# Everything else is required to be declared on the named owner, including all
# direct getDeclaredMethod/getDeclaredField preflights (Motion/Launcher/SystemUI).
INHERITED = {
    (WM + "Task;", "methods", "isFocusable", "()Z"),
    (WM + "Task;", "methods", "setFocusable", "(Z)Z"),
    (WM + "Task;", "methods", "shouldSleepActivities", "()Z"),
    (WM + "Task;", "methods", "sleepIfPossible", "(Z)Z"),
    (WM + "Task;", "methods", "isVisibleRequested", "()Z"),
}
INHERITED.update(SUGGESTION_ENVIRONMENT_CHECKS)


# These optional lookups fail closed on the archived PixelUI host. The launcher
# adapter rejects installation during ABI preflight; the fullscreen guard throws
# to its existing native fallback. Do not treat this as support for either path.
PIXELUI_UNAVAILABLE = {
    ("Landroid/app/ActivityOptions;", "fields", "mLaunchNextToBubble", "Z"),
    ("Landroid/app/ActivityOptions;", "methods", "getLaunchNextToBubble", "()Z"),
    (WM + "Task;", "fields", "mLaunchNextToBubble", "Z"),
}


def verify(classes, unavailable=()):
    failures = []
    for owner, kind, name, signature in sorted(set(CHECKS)):
        key = "descriptor" if kind == "methods" else "type"
        rows = members(classes, owner, kind) if (owner, kind, name, signature) in INHERITED else classes.get(owner, {}).get(kind, [])
        present = any(m["name"] == name and m[key] == signature for m in rows)
        if (owner, kind, name, signature) in unavailable:
            assert not present, ("Update optional ABI expectation", owner, name)
        elif not present:
            failures.append((owner, name, signature))
    return failures


class TargetSet(set):
    # Include superclass definitions so inherited FrameworkAccess lookups are real.
    def __contains__(self, owner):
        return super().__contains__(owner) or owner.startswith((WM, "Landroid/", "Lcom/android/quickstep/", SYS + "shared/rotation/"))


def verify_add_callers(path, expected):
    """Direct DEX call evidence, not proof of ART hook installation on a device."""
    seen = set()
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            raw = archive.read(name)
            header = 0
            while header < len(raw):
                view = bytearray(raw)
                if header:
                    view[:120] = raw[header:header+120]
                parsed = dex.dex.Dex(view)
                for owner, name, signature in parsed.code:
                    if owner != WM + "Session;" or name not in ("addToDisplay", "addToDisplayAsUser"):
                        continue
                    calls = [i["method"] for i in parsed.instructions(owner, name, signature) if "method" in i]
                    assert any(m["owner"] == WM + "WindowManagerService;" and m["name"] == "addWindow"
                               and m["descriptor"] == expected for m in calls), (path, name)
                    seen.add(name)
                if raw[header+4:header+7] != b"041":
                    break
                header += int.from_bytes(raw[header+32:header+36], "little")
    assert seen == {"addToDisplay", "addToDisplayAsUser"}, (path, seen)


def main():
    viewport = (SOURCE / "floatingwindow/legacy/ServerViewport.java").read_text(encoding="utf-8")
    # Regression: both native return shapes reach the same owned-client mapper;
    # negative/null results must not be dereferenced or fabricate initial config.
    for invariant in (
        'method.getReturnType()==int.class&&types.length==9',
        'method.getReturnType().getName().equals("android.view.AddWindowResult")&&types.length==7',
        'returnedRelayout.getType()!=resultType||returnedCode.getType()!=int.class',
        'addCode!=null&&(value==null||addCode.getInt(value)<0)',
        'addRelayout==null?chain.getArg(resultIndex):addRelayout.get(value)',
        'owner=resultObject==null?null:engine.clientOwner(chain.getArg(clientIndex))',
        'if(!initial)',
        'mapInsets(a,resultInsets.get(resultObject),insets->engine.ownerInsets(owner,insets))',
        'if(installed!=1||added!=1)',
    ):
        assert invariant in viewport, ("ServerViewport ABI/ownership regression", invariant)
    hosts = {
        "PixelOS(dada)": ["verification/navbar-system/framework.jar", "verification/navbar-system/services.jar",
                          "verification/split-shade-device/SystemUI.apk", "verification/navbar-system/NexusLauncher.apk"],
        "PixelUI": ["verification/pixel-qpr2-20261113/framework.jar", "verification/pixel-qpr2-20261113/services.jar",
                    "verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk", "verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk"],
    }
    missing = [name for files in hosts.values() for name in files if not (ROOT / name).is_file()]
    if missing:
        print("SKIP missing archives:", *missing, sep="\n")
        return 2
    hashes = [
        "89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f",
        "3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9",
        "a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972",
        "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56",
        "466ba9aa9934aa5bc113f9ee738e3fd66cf92b0bb3d02a144d0669b59381015f",
        "382ed5d6b0e9ca464878ac6c87b655ab7f6009fd8463949aa43c23b738dbb7db",
        "458b481b241552d67d33963f3cb449a9e5e53d4cfcd28e8bb1d919b814fd982f",
        "109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c",
    ]
    for name, digest in zip((name for files in hosts.values() for name in files), hashes):
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, ("archive changed", name)
    dex.TARGETS = TargetSet(dex.TARGETS | {c[0] for c in CHECKS} | {ADD})
    failed = False
    for label, files in hosts.items():
        classes = {}
        separate = []
        for name in files:
            current = dex.read_archive(ROOT / name)
            separate.append(current)
            for owner, body in current.items():
                # Boot classes come only from framework/services, Launcher from its APK.
                if name.endswith(".apk") and owner.startswith(("Landroid/", WM)):
                    continue
                if "SystemUI" in name and (owner.startswith("Lcom/android/quickstep/") or owner.startswith(dex.BAR)):
                    continue
                classes.setdefault(owner, body)
        unavailable = PIXELUI_UNAVAILABLE if label == "PixelUI" else set()
        assert unavailable <= set(CHECKS), "Optional target inventory changed"
        failures = verify(classes, unavailable)
        if unavailable:
            print("SKIP", label, "3 absent bubble members: optional launcher/fullscreen paths retain native fallback; those features are unverified")
        add_args = "(" + WM + "Session;Landroid/view/IWindow;Landroid/view/WindowManager$LayoutParams;IIII"
        expected_add = add_args + (")" + ADD if label == "PixelUI" else "Landroid/view/InputChannel;Landroid/view/WindowRelayoutResult;)I")
        assert any(m["name"] == "addWindow" and m["descriptor"] == expected_add for m in classes[WM + "WindowManagerService;"]["methods"])
        verify_add_callers(ROOT / files[1], expected_add)
        if label == "PixelUI":
            assert any(f["name"] == "relayoutResult" and f["type"] == "Landroid/view/WindowRelayoutResult;" for f in classes[ADD]["fields"])
            assert any(f["name"] == "returnCode" and f["type"] == "I" for f in classes[ADD]["fields"])
        for owner, member, signature in failures:
            print("FAIL", label, owner, member, signature, "actual:",
                  [m for kind in ("methods", "fields") for m in members(classes, owner, kind) if m["name"] == member])
        # ServerViewport selects real outgoing constructors, not arbitrary same-name factories.
        for name in ("LaunchActivityItem", "ActivityRelaunchItem", "ActivityConfigurationChangeItem", "MoveToDisplayItem", "WindowStateResizeItem"):
            first = "Landroid/view/IWindow;" if name == "WindowStateResizeItem" else "Landroid/os/IBinder;"
            assert any(m["name"] == "<init>" and m["descriptor"].startswith("(" + first)
                       for m in classes["Landroid/app/servertransaction/" + name + ";"]["methods"]), (label, name)
        config_item = classes["Landroid/app/servertransaction/ConfigurationChangeItem;"]
        assert any(m["name"] == "<init>" and m["descriptor"] == "(Landroid/content/res/Configuration;I)V" for m in config_item["methods"])
        # Existing ABI adapters must retain their exact per-host shapes.
        dex.verify(classes, label == "PixelUI")
        for host_name, current in zip(("SystemUI", "Launcher"), separate[2:]):
            button = next(f["type"] for f in current[ROTATION]["fields"] if f["name"] == "mRotationButton")
            if host_name == "SystemUI":
                assert any(f["name"] == "mKeyButtonView" and f["type"] == "Lcom/android/systemui/shared/rotation/FloatingRotationButtonView;"
                           for f in current[button]["fields"]), (label, button)
                screenshot = current[SYS + "screenshot/ScreenshotWindow;"]
                assert (any(f["name"] == "window" and f["type"] in ("Landroid/view/Window;", "Lcom/android/internal/policy/PhoneWindow;") for f in screenshot["fields"])
                        or any(m["name"] == "getWindow" and m["descriptor"] in ("()Landroid/view/Window;", "()Lcom/android/internal/policy/PhoneWindow;") for m in screenshot["methods"])), (label, screenshot)
            if host_name == "Launcher":
                defer = "shouldDeferDown" if label == "PixelUI" else "needDeferDown"
                assert any(m["name"] == defer and m["descriptor"] == "()Z" for m in current[OTHER_ACTIVITY]["methods"]), (label, defer)
                for name, signature in (("getCurrentView", "()Landroid/view/View;"), ("hide", "()Z")):
                    assert any(m["name"] == name and m["descriptor"] == signature for m in current[button]["methods"]), (label, button, name)
                assert any(m["name"] == "onDestroy" and m["descriptor"] == "()V" for m in current[ROTATION]["methods"])
                # SwipeMini accepts exactly one calculateEndTarget; its parameters differ per host.
                ends = [m for m in current[QS + "AbsSwipeUpHandler;"]["methods"]
                        if m["name"] == "calculateEndTarget" and m["descriptor"].endswith(")" + END_TARGET)]
                assert len(ends) == 1, (label, "calculateEndTarget", ends)
            for owner, kind, member, signature in CHECKS:
                if owner == ROTATION:
                    key = "descriptor" if kind == "methods" else "type"
                    expected = "Ljava/lang/Runnable;" if host_name == "Launcher" and member == "mCancelPendingRotationProposal" else signature
                    if not any(m["name"] == member and m[key] == expected for m in current.get(owner, {}).get(kind, [])):
                        print("FAIL", label, host_name, owner, member, signature)
                        failures.append((owner, member, signature))
        failed |= bool(failures)
        if not failures:
            print("PASS", label, len(set(CHECKS) - unavailable), "available window/task/input/notification/suggestion/Motion/edge members + test4 ABI variants")
    print("LIMIT: no device effects, ART installation, native SensorService command semantics or visual/recovery acceptance asserted")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
