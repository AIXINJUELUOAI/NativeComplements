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
'Landroid/app/Activity;':{'methods':[('onResume','()V'),('isInMultiWindowMode','()Z'),('dispatchMultiWindowModeChanged','(ZLandroid/content/res/Configuration;)V')]},
'Landroid/view/ViewRootImpl$ViewPostImeInputStage;':{'methods':[('processPointerEvent','(Landroid/view/ViewRootImpl$QueuedInputEvent;)I')],'fields':[('this$0','Landroid/view/ViewRootImpl;')]},
'Landroid/view/ViewRootImpl$QueuedInputEvent;':{'fields':[('mEvent','Landroid/view/InputEvent;')]},
'Landroid/view/ViewRootImpl;':{'fields':[('mView','Landroid/view/View;')]},
'Landroid/view/MotionEvent;':{'methods':[(name,sig) for name in ['getRawX','getRawY'] for sig in ['()F','(I)F']]},
'Landroid/view/WindowManager$LayoutParams;':{'methods':[('setTrustedOverlay','()V')]},
}
total=0
for cls,sections in checks.items():
 for kind,members in sections.items():
  for name,descriptor in members:
   assert any(x['name']==name and x.get('descriptor' if kind=='methods' else 'type')==descriptor for x in classes[cls][kind]),(cls,name,descriptor)
   total+=1
print(f'PASS: {total} exact API37 members from archived target artifacts; current-device behavior remains unverified')
