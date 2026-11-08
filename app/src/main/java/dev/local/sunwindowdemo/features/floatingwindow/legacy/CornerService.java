package dev.local.sunwindowdemo;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public final class CornerService extends AccessibilityService {
    static volatile CornerService live;
    private final android.os.Binder lease=new android.os.Binder();
    private int[] published,publishedHandles;
    private boolean screenshotVisible,systemFocus,immersive;
    private android.window.OnBackInvokedDispatcher panelDispatcher;
    private final android.window.OnBackInvokedCallback panelBack=()->hidePanel();
    void controlsChanged(){main.post(()->publish(false));}
    private int publishedWidth,publishedHeight;
    private boolean publishedEnabled;
    private boolean publishedDrop,publishedImmersive;
    private int activeTask=-1,publishedHidden=-2;
    private final java.util.concurrent.atomic.AtomicBoolean backPending=new java.util.concurrent.atomic.AtomicBoolean();
    private final android.os.Binder dropChannel=new android.os.Binder(){
        @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags) throws android.os.RemoteException {
            if(code<android.os.IBinder.FIRST_CALL_TRANSACTION||code>android.os.IBinder.FIRST_CALL_TRANSACTION+3)return super.onTransact(code,data,reply,flags);
            try{if(android.os.Binder.getCallingUid()!=getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)return false;}
            catch(Exception e){return false;}
            data.enforceInterface(BackBridge.CHANNEL);
            if(code==android.os.IBinder.FIRST_CALL_TRANSACTION+3){
                boolean shown=data.readBoolean();data.enforceNoDataAvail();
                main.post(()->{screenshotVisible=shown;updateOverlayPriority();if(shown){hideFan();hidePanel();removeHot();}else refresh();});return true;
            }
            if(code==android.os.IBinder.FIRST_CALL_TRANSACTION+1){
                int stage=data.readInt();data.enforceNoDataAvail();
                if(stage<0||stage>2)return false;
                main.post(()->showSwipeHint(stage));return true;
            }
            int taskId=data.readInt();String pkg=data.readString();data.enforceNoDataAvail();
            if(taskId<0||!Wire.allowedPackage(pkg)||Wire.PKG.equals(pkg)||LauncherGuard.PACKAGE.equals(pkg))return false;
            boolean probe=code==android.os.IBinder.FIRST_CALL_TRANSACTION+2;
            boolean accepted=!cleaned&&allowed()&&backendReady&&"idle".equals(backendState)
                &&!config.blocked().contains(pkg)&&(probe?!backPending.get():backPending.compareAndSet(false,true));
            if(probe){if(reply!=null){reply.writeNoException();reply.writeBoolean(accepted);}return true;}
            if(accepted)main.postDelayed(()->{
                try{if(!cleaned&&allowed()&&backendReady&&"idle".equals(backendState))
                    Wire.send(CornerService.this,"GESTURE",pkg,taskId,Wire.token(),false);
                }finally{backPending.set(false);}
            },0);
            if(reply!=null){reply.writeNoException();reply.writeBoolean(accepted);}return true;
        }
    };
    static volatile boolean connected;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Config config;
    private WindowManager wm;
    private View left,right;
    private FanView fan;
    private View panel;
    private TextView swipeHint;
    private int hintStage;
    private int hintRevision;
    private void showSwipeHint(int stage){
        if(stage==0||cleaned||!allowed()||!backendReady||!"idle".equals(backendState)){
            if(swipeHint!=null)remove(swipeHint);swipeHint=null;hintStage=0;hintRevision++;return;
        }
        if(stage==2&&hintStage!=2){
            View feedback=swipeHint!=null?swipeHint:left!=null?left:right;
            if(feedback!=null)feedback.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        hintStage=stage;int revision=++hintRevision;
        main.postDelayed(()->{if(hintRevision==revision)showSwipeHint(0);},10000);
        if(config.hideTopHint()){
            if(swipeHint!=null)remove(swipeHint);swipeHint=null;return;
        }
        if(swipeHint==null){
            swipeHint=new TextView(this);swipeHint.setTextColor(Color.WHITE);swipeHint.setTextSize(15);swipeHint.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams p=params(dp(230),dp(58),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(48);p.windowAnimations=0;
            try{wm.addView(swipeHint,p);}catch(RuntimeException e){swipeHint=null;return;}
        }
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(stage==2?0xf04f6358:0xe530343b);bg.setCornerRadius(dp(24));swipeHint.setBackground(bg);
        swipeHint.setText(stage==2?"松手进入小窗":"继续长滑进入小窗");

    }
    private WindowControls controls;
    private volatile boolean backendReady;
    private volatile String backendState="";
    private final BroadcastReceiver backendReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(getSentFromUid()!=1000||i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        Wire.acceptBackend(i);receiveFrame(i);
    }};
    private void receiveFrame(Intent i){
        if(cleaned||controls==null)return;
        backendReady=true;backendState=i.getStringExtra("state");
        if(i.hasExtra("immersive"))immersive=i.getBooleanExtra("immersive",false);
        activeTask="active".equals(backendState)?i.getIntExtra("taskId",-1):-1;
        publish(false);
        try{if(!getSystemService(android.os.PowerManager.class).isInteractive()||getSystemService(KeyguardManager.class).isKeyguardLocked())controls.hide();else controls.update(i);}catch(RuntimeException e){controls.hide();android.util.Log.e("SunWindowDemo8.2","overlay controls failed",e);}
        if(i.getStringExtra("event")!=null&&(i.getStringExtra("event").endsWith("FAILED")||"BLOCKED".equals(i.getStringExtra("event"))))Toast.makeText(this,i.getStringExtra("report"),1).show();
    }
    private List<AppCatalog.App> all=List.of();
    private String foreground="";
    private boolean keyboard,cleaned,receiverRegistered;
    private int lastSelection=-1;
    private long lastOpen;
    private int touchLogs;
    static volatile String lastTouch="尚未收到角落触摸";
    private void touchLog(String message){lastTouch=message;if(touchLogs++<50)android.util.Log.i("CornerLauncher",message);}
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(p,k)->main.post(()->{showSwipeHint(0);hideFan();hidePanel();removeHot();refresh();Wire.send(this,"STATUS");});
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){hideFan();hidePanel();removeHot();if(controls!=null)controls.hide();refresh();Wire.send(CornerService.this,"STATUS");}
    };
    private final BroadcastReceiver packageReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){loadApps();}};

    @Override public void onServiceConnected() {
        cleaned=false;connected=true;live=this;config=new Config(this);wm=getSystemService(WindowManager.class);
        controls=new WindowControls(this);
        Wire.listen(this::receiveFrame);
        registerReceiver(backendReceiver,new IntentFilter(Wire.RESULT),Wire.PERMISSION,main,Context.RECEIVER_EXPORTED);
        Wire.send(this,"STATUS");
        config.prefs.registerOnSharedPreferenceChangeListener(prefListener);
        IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);screen.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver,screen,Context.RECEIVER_NOT_EXPORTED);
        IntentFilter pkgs=new IntentFilter(Intent.ACTION_PACKAGE_ADDED);pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);pkgs.addDataScheme("package");
        registerReceiver(packageReceiver,pkgs,Context.RECEIVER_NOT_EXPORTED);receiverRegistered=true;
        loadApps();updateState(null);refresh();
    }
    private void loadApps() {
        new Thread(()->{
            List<AppCatalog.App> apps=AppCatalog.load(this);
            main.post(()->{if(!connected||cleaned)return;all=apps;refresh();});
        },"corner-app-catalog").start();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(config==null||cleaned)return;
        updateState(event);refresh();
    }
    private void updateState(AccessibilityEvent event) {
        try {
            boolean ime=false,foundApplication=false,focusedSystem=false;int imeTop=Integer.MAX_VALUE;
            for(AccessibilityWindowInfo w:getWindows()) {
                if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){ime=true;android.graphics.Rect r=new android.graphics.Rect();w.getBoundsInScreen(r);if(!r.isEmpty())imeTop=Math.min(imeTop,r.top);}
                if(w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM&&w.isFocused()){
                    AccessibilityNodeInfo root=w.getRoot();if(root!=null){focusedSystem="com.android.systemui".contentEquals(root.getPackageName()==null?"":root.getPackageName());root.recycle();}
                }
                if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&(w.isActive()||w.isFocused())) {
                    foundApplication=true;
                    AccessibilityNodeInfo root=w.getRoot();
                    if(root!=null) {
                        CharSequence pkg=root.getPackageName();
                        if(pkg!=null&&!pkg.toString().equals("com.android.systemui"))foreground=pkg.toString();
                        root.recycle();
                    }
                }
            }
            keyboard=ime;systemFocus=focusedSystem;updateOverlayPriority();
            if(controls!=null)controls.imeTop(imeTop);
            if(!foundApplication&&event!=null&&event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getPackageName()!=null) {
                String pkg=event.getPackageName().toString();
                if(!pkg.equals("com.android.systemui")&&!ime)foreground=pkg;
            }
        } catch(RuntimeException failure){android.util.Log.w("CornerLauncher","window-state-unavailable");keyboard=config.keyboardOff();}
    }
    private void updateOverlayPriority(){if(controls!=null)controls.systemOverlay(screenshotVisible||systemFocus);}
    private boolean allowed() {
        if(config==null||cleaned||screenshotVisible||!getSystemService(android.os.PowerManager.class).isInteractive())return false;
        KeyguardManager kg=getSystemService(KeyguardManager.class);
        return !Rules.blocked(kg==null||kg.isKeyguardLocked(),
            getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,config.landscapeOff(),
            keyboard,config.keyboardOff(),config.blocked().contains(foreground));
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int bottom(){return wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;}
    private WindowManager.LayoutParams params(int width,int height,int flags) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.setTitle("CornerLauncher");return p;
    }
    private void refresh() {
        if(!allowed()){hideFan();removeHot();return;}
        if(left==null)addHot(false);if(right==null)addHot(true);
        publish(false);
    }
    private void addHot(boolean isRight) {
        HotspotView hot=new HotspotView(this,isRight,config.showHeat());
        hot.setContentDescription(isRight?"右侧角落启动热区":"左侧角落启动热区");
        hot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        WindowManager.LayoutParams p=params(dp(config.heat()),dp(config.heat()),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        // Cover the actual display corner, not a square above the navigation bar.
        p.gravity=Gravity.BOTTOM|(isRight?Gravity.RIGHT:Gravity.LEFT);p.y=0;
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        hot.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            v.setSystemGestureExclusionRects(List.of(new android.graphics.Rect(0,0,r-l,b-t)));publish(false);
        });
        hot.setOnTouchListener(new View.OnTouchListener(){
            float startX,startY;boolean opened;
            public boolean onTouch(View v,MotionEvent e) {
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){opened=false;hot.mark(1);startX=e.getRawX();startY=e.getRawY();v.requestUnbufferedDispatch(e);touchLog((isRight?"右":"左")+"热区收到按下");return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                    if(!allowed()){hideFan();return true;}
                    if(fan==null&&!opened&&Rules.triggered(e.getRawX()-startX,e.getRawY()-startY,isRight,dp(config.distance()))){opened=true;touchLog("斜向滑动达标");showFan(isRight,hot);}
                    if(fan!=null){int hit=fan.pointer(e.getRawX(),e.getRawY());if(hit!=lastSelection){lastSelection=hit;if(hit>=0)v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}}
                    return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP) {
                    if(fan!=null)fan.pointer(e.getRawX(),e.getRawY());
                    hot.mark(0);String target=fan==null?null:fan.picked();touchLog(target==null?"松手取消（未选中）":"松手已选中菜单项");hideFan();
                    if(target!=null&&allowed()){if(target.isEmpty())showPanel();else launch(target);}
                    return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){hot.mark(2);touchLog("触摸被系统取消");hideFan();}
                return true;
            }
        });
        try {wm.addView(hot,p);if(isRight)right=hot;else left=hot;}
        catch(RuntimeException failure){android.util.Log.w("CornerLauncher","hot-zone-add-failed");}
    }
    private void showFan(boolean isRight,View hot) {
        if(panel!=null)return;
        List<AppCatalog.App> shortcuts=new ArrayList<>();
        for(String pkg:config.apps())for(AppCatalog.App a:all)if(a.pkg.equals(pkg)){shortcuts.add(a);break;}
        fan=new FanView(this,shortcuts,isRight,dp(config.icon()),dp(config.radius()),bottom(),config.selectionDistance());lastSelection=-1;
        WindowManager.LayoutParams p=params(-1,-1,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.windowAnimations=0;
        try{wm.addView(fan,p);hot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
        catch(RuntimeException failure){fan=null;touchLog("菜单窗口创建失败："+failure.getClass().getSimpleName());}
    }
    private void hideFan(){if(fan!=null){remove(fan);fan=null;}lastSelection=-1;}
    private void remove(View v){try{if(wm!=null)wm.removeViewImmediate(v);}catch(RuntimeException ignored){}}
    private void removeHot(){if(left!=null){remove(left);left=null;}if(right!=null){remove(right);right=null;}publish(false);}
    void publish(boolean force){
        int[] rects=new int[8];int count=0;
        boolean enabled=allowed()&&panel==null;
        boolean dropEnabled=enabled&&backendReady&&"idle".equals(backendState);
        if(!dropEnabled)showSwipeHint(0);
        if(enabled)for(View v:new View[]{left,right})if(v!=null&&v.isAttachedToWindow()&&v.isLaidOut()){
            int[] location=new int[2];v.getLocationOnScreen(location);
            rects[count++]=location[0];rects[count++]=location[1];rects[count++]=location[0]+v.getWidth();rects[count++]=location[1]+v.getHeight();
        }
        int[] handles=controls==null?new int[0]:controls.protectedRects();
        rects=java.util.Arrays.copyOf(rects,count);enabled=(enabled&&count>0)||handles.length>0;
        android.graphics.Rect bounds=wm==null?new android.graphics.Rect():wm.getCurrentWindowMetrics().getBounds();
        int hidden=config!=null&&config.hideRecents()?activeTask:-1;
        if(!force&&publishedImmersive==immersive&&publishedHidden==hidden&&publishedDrop==dropEnabled&&publishedEnabled==enabled&&publishedWidth==bounds.width()&&publishedHeight==bounds.height()&&java.util.Arrays.equals(published,rects)&&java.util.Arrays.equals(publishedHandles,handles))return;
        publishedDrop=dropEnabled;publishedHidden=hidden;publishedImmersive=immersive;
        publishedEnabled=enabled;publishedWidth=bounds.width();publishedHeight=bounds.height();published=rects.clone();publishedHandles=handles.clone();
        Intent state=new Intent(LauncherGuard.STATE).setPackage(LauncherGuard.PACKAGE).putExtra("enabled",enabled)
            .putExtra("width",bounds.width()).putExtra("height",bounds.height()).putExtra("rects",rects);
        state.putExtra("handleRects",handles).putExtra("immersive",immersive).putExtra("hiddenTask",hidden).putExtra("dropEnabled",dropEnabled).putExtra("density",getResources().getDisplayMetrics().density);
        android.os.Bundle extras=new android.os.Bundle();extras.putBinder("lease",lease);extras.putBinder("dropChannel",dropChannel);state.putExtras(extras);
        sendBroadcast(state);
        sendBroadcast(new Intent(state).setPackage(BackBridge.PACKAGE));
    }
    private void showPanel() {
        if(panel!=null)return;
        if(all.isEmpty()){Toast.makeText(this,"应用列表正在加载，请稍后重试",0).show();return;}
        LinearLayout box=new LinearLayout(this){
            @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)hidePanel();return true;}return super.dispatchKeyEvent(e);}
        };
        box.setOrientation(1);box.setBackgroundColor(0xff20252c);
        int top=wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        box.setPadding(dp(20),dp(16),dp(20),dp(16));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xff24212a);bg.setCornerRadius(dp(28));box.setBackground(bg);
        FrameLayout outside=new FrameLayout(this){
            @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)hidePanel();return true;}return super.dispatchKeyEvent(e);}
        };outside.setBackgroundColor(0x44000000);outside.setOnClickListener(v->hidePanel());box.setOnClickListener(v->{});
        android.graphics.Rect display=wm.getCurrentWindowMetrics().getBounds();
        FrameLayout.LayoutParams card=new FrameLayout.LayoutParams(Math.min(display.width()-dp(32),dp(380)),Math.min(display.height()-dp(80),dp(640)),Gravity.CENTER);outside.addView(box,card);
        Button close=new Button(this);close.setText("应用列表 · 关闭");close.setOnClickListener(v->hidePanel());box.addView(close);
        AppList.fill(this,box,all,a->{String pkg=a.pkg;hidePanel();launch(pkg);});
        WindowManager.LayoutParams p=params(-1,-1,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;panel=outside;
        outside.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){panelDispatcher=v.findOnBackInvokedDispatcher();if(panelDispatcher!=null)panelDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,panelBack);}
            public void onViewDetachedFromWindow(View v){if(panelDispatcher!=null){panelDispatcher.unregisterOnBackInvokedCallback(panelBack);panelDispatcher=null;}}
        });
        try{wm.addView(outside,p);publish(false);}catch(RuntimeException failure){panel=null;Toast.makeText(this,"应用面板打开失败",0).show();}
    }
    private void hidePanel(){if(panel!=null){InputMethodManager imm=getSystemService(InputMethodManager.class);if(imm!=null)imm.hideSoftInputFromWindow(panel.getWindowToken(),0);remove(panel);panel=null;publish(false);}}
    private void launch(String pkg) {
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastOpen<500)return;lastOpen=now;
        if(!Rules.validPackage(pkg))return;
        main.postDelayed(()->{
            if(cleaned)return;
            if(!backendReady){Wire.send(this,"STATUS");Toast.makeText(this,"后端尚未就绪，请先在模块内预检",1).show();return;}
            Wire.send(this,"OPEN",pkg,-1,Wire.token(),false);
        },180);
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);hideFan();hidePanel();removeHot();refresh();Wire.send(this,"STATUS");}
    @Override public void onInterrupt(){hideFan();hidePanel();removeHot();}
    @Override public boolean onUnbind(Intent intent){cleanup();return super.onUnbind(intent);}
    @Override public void onDestroy(){cleanup();super.onDestroy();}
    private void cleanup(){if(cleaned)return;if(controls!=null){controls.close();}try{unregisterReceiver(backendReceiver);}catch(RuntimeException ignored){}Wire.listen(null);cleaned=true;connected=false;if(live==this)live=null;main.removeCallbacksAndMessages(null);hideFan();hidePanel();removeHot();publish(true);if(config!=null)config.prefs.unregisterOnSharedPreferenceChangeListener(prefListener);if(receiverRegistered){unregisterReceiver(screenReceiver);unregisterReceiver(packageReceiver);receiverRegistered=false;}}
}
