"""Check newly used private members against the existing target API37 DEX inventory.
This is static signature evidence, not a live phone/runtime test.
"""
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
data=json.loads((root/'tests/target/new-members.json').read_text())
classes=data['classes']
checks={
'Lcom/android/server/wm/RootWindowContainer;':{'methods':[('getDefaultDisplay','()Lcom/android/server/wm/DisplayContent;')]},
'Lcom/android/server/wm/DisplayContent;':{'methods':[('getImeWindow','()Lcom/android/server/wm/WindowState;')],'fields':[('mCurrentFocus','Lcom/android/server/wm/WindowState;')]},
'Lcom/android/server/wm/WindowContainer;':{'methods':[('assignChildLayers','(Landroid/view/SurfaceControl$Transaction;)V'),('forAllActivities','(Ljava/util/function/Consumer;)V')]},
'Lcom/android/server/wm/TaskFragment;':{'methods':[('topRunningActivity','()Lcom/android/server/wm/ActivityRecord;')]},
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
