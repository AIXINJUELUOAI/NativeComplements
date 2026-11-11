"""Check newly used private members against the existing target API37 DEX inventory.
This is static signature evidence, not a live phone/runtime test.
"""
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
data=json.loads((root/'tests/target/new-members.json').read_text())
classes=data['classes']
checks={
'Landroid/view/SurfaceControl$Transaction;':{'methods':[(name,'(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;') for name in ['hide','show']]},
'Lcom/android/server/wm/RootWindowContainer;':{'methods':[('getDefaultDisplay','()Lcom/android/server/wm/DisplayContent;')]},
'Lcom/android/server/wm/DisplayContent;':{'methods':[('getImeWindow','()Lcom/android/server/wm/WindowState;')],'fields':[('mCurrentFocus','Lcom/android/server/wm/WindowState;')]},
'Lcom/android/server/wm/WindowContainer;':{'methods':[('assignChildLayers','(Landroid/view/SurfaceControl$Transaction;)V'),('forAllActivities','(Ljava/util/function/Consumer;)V')]},
'Lcom/android/server/wm/TaskFragment;':{'methods':[('topRunningActivity','()Lcom/android/server/wm/ActivityRecord;'),('isForceTranslucent','()Z'),('setForceTranslucent','(Z)Z')]},
'Lcom/android/server/wm/ActivityRecord;':{'methods':[('getRequestedOrientation','()I'),('getUid','()I')],'fields':[('packageName','Ljava/lang/String;')]},
'Lcom/android/server/wm/WindowState;':{'methods':[('finishDrawing','(Landroid/view/SurfaceControl$Transaction;I)Z')]},
'Landroid/app/Activity;':{'methods':[('onResume','()V'),('isInMultiWindowMode','()Z'),('dispatchMultiWindowModeChanged','(ZLandroid/content/res/Configuration;)V')]},
'Landroid/view/ViewRootImpl$ViewPostImeInputStage;':{'methods':[('processPointerEvent','(Landroid/view/ViewRootImpl$QueuedInputEvent;)I')],'fields':[('this$0','Landroid/view/ViewRootImpl;')]},
'Landroid/view/ViewRootImpl$QueuedInputEvent;':{'fields':[('mEvent','Landroid/view/InputEvent;')]},
'Landroid/view/ViewRootImpl;':{'fields':[('mView','Landroid/view/View;')]},
'Landroid/view/MotionEvent;':{'methods':[(name,sig) for name in ['getRawX','getRawY'] for sig in ['()F','(I)F']]+[('getDisplayId','()I')]},
'Landroid/view/WindowManager$LayoutParams;':{'methods':[('setTrustedOverlay','()V')]},
}
total=0
for cls,sections in checks.items():
 for kind,members in sections.items():
  for name,descriptor in members:
   assert any(x['name']==name and x.get('descriptor' if kind=='methods' else 'type')==descriptor for x in classes[cls][kind]),(cls,name,descriptor)
   total+=1
print(f'PASS: {total} exact API37 members from archived target artifacts; current-device behavior remains unverified')

game=json.loads((root/'tests/target/game-members.json').read_text())
assert game['sha256']=='a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972'
for cls,name,sig in [
 ('Lcom/android/systemui/qs/tileimpl/QSFactoryImpl;','createTile','(Ljava/lang/String;)Lcom/android/systemui/plugins/qs/QSTile;'),
 ('Lcom/android/systemui/qs/tileimpl/QSTileImpl;','click','(Lcom/android/systemui/animation/Expandable;)V'),
 ('Lcom/android/systemui/qs/tileimpl/QSTileImpl;','setTileSpec','(Ljava/lang/String;)V')]:
 assert any(m['name']==name and m['descriptor']==sig for m in game['classes'][cls]['methods']),(cls,name,sig)
print('PASS: exact native recording-tile adapter signatures from device SystemUI archive')

# Native notification creators may launch another package; correlate the original sender binder.
requests=json.loads((root/'tests/target/window-request-members.json').read_text())['classes']
assert any(m['name']=='getTarget' and m['descriptor']=='()Landroid/content/IIntentSender;'
           for m in requests['Landroid/app/PendingIntent;']['methods'])
assert any(f['name']=='originatingPendingIntent' and f['type']=='Lcom/android/server/am/PendingIntentRecord;'
           for f in requests['Lcom/android/server/wm/ActivityStarter$Request;']['fields'])
assert requests['Lcom/android/server/am/PendingIntentRecord;']['superclass']=='Landroid/content/IIntentSender$Stub;'
print('PASS: native PendingIntent sender and originating launch request signatures')

compat=json.loads((root/'tests/target/size-compat-members.json').read_text())
assert compat['sha256']=='3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9'
for cls,kind,name,sig in [
 ('AppCompatController','methods','getSizeCompatModePolicy','()Lcom/android/server/wm/AppCompatSizeCompatModePolicy;'),
 ('AppCompatSizeCompatModePolicy','methods','getAppCompatDisplayInsets','()Lcom/android/server/wm/AppCompatDisplayInsets;'),
 ('AppCompatSizeCompatModePolicy','methods','clearSizeCompatMode','()V'),
 ('AppCompatDisplayInsets','fields','mIsFloating','Z')]:
 assert any(m['name']==name and m['descriptor' if kind=='methods' else 'type']==sig
            for m in compat['classes']['Lcom/android/server/wm/'+cls+';'][kind]),(cls,name,sig)
assert any(f['name']=='mAppCompatController' and f['type']=='Lcom/android/server/wm/AppCompatController;'
           for f in classes['Lcom/android/server/wm/ActivityRecord;']['fields'])
print('PASS: native floating size-compat cache release members; on-device restored layout remains unverified')
import runpy
runpy.run_path(str(root/'tests/verify_icon_target.py'),run_name='__main__')

# Additional target signatures are preserved separately to retain original inventories.
rotation=json.loads((root/'tests/target/rotation-members.json').read_text())
for cls,name,sig in [
 ('Lcom/android/server/wm/Transition;','collect','(Lcom/android/server/wm/WindowContainer;)V'),
 ('Lcom/android/server/wm/Transition;','isCollecting','()Z'),
 ('Lcom/android/server/wm/Transition;','addTransitionEndedListener','(Ljava/lang/Runnable;)V'),
 ('Lcom/android/server/wm/TransitionController;','inTransition','(Lcom/android/server/wm/WindowContainer;)Z'),
 ('Lcom/android/server/wm/DisplayContent;','onRequestedOverrideConfigurationChanged','(Landroid/content/res/Configuration;)V'),
 ('Lcom/android/server/wm/DisplayContent;','getRotation','()I'),
 ('Lcom/android/server/wm/DisplayContent;','isRotationChanging','()Z')]:
 assert any(x['name']==name and x['descriptor']==sig for x in rotation['classes'][cls]['methods']),(cls,name,sig)
edge=json.loads((root/'tests/target/edge-input-members.json').read_text())
assert edge['apk_sha256']=='a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972'
edge_classes={x['descriptor']:x for x in edge['classes']}
for cls,kind,name,sig in [
 ('Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler$$ExternalSyntheticLambda20;','fields','f$0','Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler;'),
 ('Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler$$ExternalSyntheticLambda20;','methods','invoke','(Ljava/lang/Object;)Ljava/lang/Object;'),
 ('Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler;','fields','mDisplayBackGestureHandlers','Ljava/util/Map;'),
 ('Lcom/android/systemui/navigationbar/gestural/EdgeBackGestureHandler;','methods','cancelGesture','(Landroid/view/MotionEvent;)V'),
 ('Lcom/android/systemui/shared/system/InputChannelCompat$InputEventReceiver$1;','methods','onInputEvent','(Landroid/view/InputEvent;)V'),
 ('Lkotlin/Unit;','fields','INSTANCE','Lkotlin/Unit;')]:
 assert any(x['name']==name and x['descriptor']==sig for x in edge_classes[cls][kind]),(cls,name,sig)
print('PASS: archived rotation and SystemUI input admission signatures; live behavior still needs device validation')

# API37 outgoing IME boundaries: deep-copy sources before transforming coordinates.
viewport=json.loads((root/'tests/target/viewport-members.json').read_text())['classes']
for cls,name,sig in [
 ('Landroid/view/InsetsState;','<init>','(Landroid/view/InsetsState;Z)V'),
 ('Landroid/view/InsetsState;','set','(Landroid/view/InsetsState;Z)V'),
 ('Landroid/view/InsetsState;','sourceSize','()I'),
 ('Landroid/view/InsetsState;','sourceAt','(I)Landroid/view/InsetsSource;'),
 ('Landroid/view/InsetsSource;','getType','()I'),
 ('Landroid/view/InsetsSource;','getFrame','()Landroid/graphics/Rect;'),
 ('Landroid/view/InsetsSource;','setFrame','(Landroid/graphics/Rect;)Landroid/view/InsetsSource;'),
 ('Landroid/view/InsetsSource;','getVisibleFrame','()Landroid/graphics/Rect;'),
 ('Landroid/view/InsetsSource;','setVisibleFrame','(Landroid/graphics/Rect;)Landroid/view/InsetsSource;'),
 ('Landroid/view/IWindow$Stub$Proxy;','insetsControlChanged','(Landroid/view/InsetsState;Landroid/view/InsetsSourceControl$Array;)V')]:
 assert any(m['name']==name and m['descriptor']==sig for m in viewport[cls]['methods']),(cls,name,sig)
assert any(f['name']=='insetsState' and f['type']=='Landroid/view/InsetsState;' for f in viewport['Landroid/view/WindowRelayoutResult;']['fields'])
print('PASS: archived API37 IME source, relayout and direct notification boundaries')

# Notification clicks: exact current-device SystemUI declared callback/field shapes.
notifications=json.loads((root/'tests/target/notification-members.json').read_text())
assert notifications['artifacts'][0]['sha256']=='a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972'
prefix='Lcom/android/systemui/statusbar/phone/StatusBarNotificationActivityStarter$$ExternalSyntheticLambda'
entry='Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;'
for cls,kind,name,descriptor in [
 (prefix+'7;','methods','run','()V'),
 (prefix+'10;','methods','startPendingIntent','(Landroid/view/RemoteAnimationAdapter;)I'),
 (prefix+'7;','fields','f$1',entry),(prefix+'7;','fields','f$3','Landroid/app/PendingIntent;'),(prefix+'7;','fields','f$5','Z'),
 (prefix+'10;','fields','f$5',entry),(prefix+'10;','fields','f$3','Landroid/app/PendingIntent;'),
 (prefix+'10;','fields','f$4','Landroid/content/Intent;'),(prefix+'10;','fields','f$2','I'),
 (entry,'fields','mSbn','Landroid/service/notification/StatusBarNotification;')]:
 assert any(m['name']==name and m['descriptor' if kind=='methods' else 'type']==descriptor for m in notifications['classes'][cls][kind]),(cls,name,descriptor)
print('PASS: archived SystemUI notification click and original PendingIntent launch signatures')

handoff=json.loads((root/'tests/target/handoff-members.json').read_text())['classes']
for cls,name,sig in [
 ('Lcom/android/server/wm/ActivityStarter;','startActivityUnchecked','(Lcom/android/server/wm/ActivityRecord;Lcom/android/server/wm/ActivityRecord;Landroid/service/voice/IVoiceInteractionSession;Lcom/android/internal/app/IVoiceInteractor;ILandroid/app/ActivityOptions;Lcom/android/server/wm/Task;Lcom/android/server/wm/TaskFragment;Lcom/android/server/wm/BackgroundActivityStartController$BalVerdict;Lcom/android/server/uri/NeededUriGrants;ILcom/android/server/wm/Transition;ZI)I'),
 ('Lcom/android/server/wm/ActivityRecord;','isResolverOrDelegateActivity','()Z'),
 ('Landroid/app/ActivityOptions;','fromBundle','(Landroid/os/Bundle;)Landroid/app/ActivityOptions;'),
 ('Landroid/app/ActivityOptions;','setLaunchWindowingMode','(I)V'),
 ('Landroid/app/ActivityOptions;','getLaunchWindowingMode','()I'),
 ('Landroid/app/ActivityOptions;','setLaunchTaskId','(I)V'),
 ('Landroid/app/ActivityOptions;','setLaunchRootTask','(Landroid/window/WindowContainerToken;)Landroid/app/ActivityOptions;'),
 ('Landroid/app/ActivityOptions;','setLaunchTaskFragmentToken','(Landroid/os/IBinder;)Landroid/app/ActivityOptions;'),
 ('Landroid/view/InsetsState;','setDisplayFrame','(Landroid/graphics/Rect;)V'),
 ('Landroid/view/InsetsState;','setDisplayCutout','(Landroid/view/DisplayCutout;)V'),
 ('Landroid/view/InsetsSource;','setVisible','(Z)Landroid/view/InsetsSource;')]:
 assert any(m['name']==name and m['descriptor']==sig for m in handoff[cls]['methods']),(cls,name,sig)
assert any(f['name']=='NO_CUTOUT' and f['type']=='Landroid/view/DisplayCutout;' for f in handoff['Landroid/view/DisplayCutout;']['fields'])
print('PASS: archived early share launch and local viewport Insets signatures')

launch_input=json.loads((root/'tests/target/launch-input-members.json').read_text())['classes']
for cls,kind,name,sig in [
 ('Landroid/view/InputMonitor;','methods','pilferPointers','()V'),
 ('Landroid/content/pm/PackageManager;','methods','getPermissionControllerPackageName','()Ljava/lang/String;'),
 ('Lcom/android/server/wm/ActivityStarter;','methods','resolveReusableTask','(Z)Lcom/android/server/wm/Task;'),
 ('Lcom/android/systemui/navigationbar/gestural/DisplayBackGestureHandlerImpl;','fields','inputMonitorCompat','Lcom/android/systemui/shared/system/InputMonitorCompat;'),
 ('Lcom/android/systemui/shared/system/InputMonitorCompat;','fields','mInputMonitor','Landroid/view/InputMonitor;'),
 ('Lcom/android/server/wm/ActivityRecord;','fields','info','Landroid/content/pm/ActivityInfo;'),
 ('Lcom/android/server/wm/ActivityRecord;','methods','setRequestedOrientation','(I)V'),
 ('Lcom/android/server/wm/ActivityStarter;','fields','mStartActivity','Lcom/android/server/wm/ActivityRecord;'),
 ('Lcom/android/server/wm/ActivityStarter;','methods','computeTargetTask','()Lcom/android/server/wm/Task;'),
 ('Landroid/app/PendingIntent;','methods','sendAndReturnResult','(Landroid/content/Context;ILandroid/content/Intent;Landroid/app/PendingIntent$OnFinished;Landroid/os/Handler;Ljava/lang/String;Landroid/os/Bundle;)I')]:
 assert any(m['name']==name and m['descriptor' if kind=='methods' else 'type']==sig for m in launch_input[cls][kind]),(cls,name,sig)
print('PASS: archived threshold-only pointer pilfer, original shortcut verdict and result-preserving native task selection')

# 0.5.1: optional edge continuation and native SensorService command gateway.
for cls,name,sig in [
 ('Lcom/android/server/wm/TaskFragment;','shouldSleepActivities','()Z'),
 ('Lcom/android/server/wm/TaskFragment;','sleepIfPossible','(Z)Z'),
 ('Lcom/android/server/wm/ActivityRecord;','shouldBeVisibleUnchecked','()Z'),
 ('Lcom/android/server/wm/ActivityRecord;','updateAndCheckVisibility','(ZZ)Z'),
 ('Lcom/android/server/wm/ActivityRecord;','setState','(Lcom/android/server/wm/ActivityRecord$State;Ljava/lang/String;)V'),
 ('Lcom/android/server/wm/ActivityRecord;','getPid','()I'),
 ('Lcom/android/server/wm/ActivityRecord;','getUid','()I'),
 ('Lcom/android/server/wm/ActivityRecord;','isFocusedActivityOnDisplay','()Z')]:
 assert any(m['name']==name and m['descriptor']==sig for m in classes[cls]['methods']),(cls,name,sig)
assert any(f['name']=='mShuttingDown' and f['type']=='Z' for f in classes['Lcom/android/server/wm/ActivityTaskManagerService;']['fields'])
continuation=json.loads((root/'tests/target/continuation-members.json').read_text())
assert continuation['sha256']=='89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f'
assert 'addToTemporaryAllowList(Ljava/lang/String;ILjava/lang/String;J)V' in continuation['classes']['android.os.PowerExemptionManager']
assert 'forceStopPackageAsUser(Ljava/lang/String;I)V' in continuation['classes']['android.app.ActivityManager']
assert 'shellCommand(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;[Ljava/lang/String;Landroid/os/ShellCallback;Landroid/os/ResultReceiver;)V' in continuation['classes']['android.os.IBinder']
print('PASS: archived optional continuation, foreground observation, power and sensor-command signatures')

# 0.5.3: patch the native Shell finish already sent before predictive preparation.
back_finish=json.loads((root/'tests/target/back-finish-members.json').read_text())
assert back_finish['sha256']==edge['apk_sha256']
back_classes=back_finish['classes']
assert any(m['signature']=='onFinish(IBinder, WindowContainerTransaction): void'
           for m in back_classes['com.android.wm.shell.transition.Transitions']['methods'])
for cls,name,kind in [
 ('Transitions','mKnownTransitions','ArrayMap'),('Transitions','mMixpatchAnimations','ArrayList'),
 ('Transitions.ActiveTransition','mStartT','SurfaceControl$Transaction'),
 ('Transitions.ActiveTransition','mFinishT','SurfaceControl$Transaction'),
 ('Transitions.ActiveTransition','mMerged','ArrayList'),('Transitions.ActiveTransition','mInfo','TransitionInfo')]:
 assert any(f['name']==name and f['type']==kind for f in back_classes['com.android.wm.shell.transition.'+cls]['fields']),(cls,name,kind)
print('PASS: archived Shell cached/merged finish transaction boundary; live flash-frame result remains unverified')
