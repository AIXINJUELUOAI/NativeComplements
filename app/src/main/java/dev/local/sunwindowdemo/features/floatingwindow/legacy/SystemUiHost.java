package dev.local.sunwindowdemo;


import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.*;

import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public final class SystemUiHost extends ContextWrapper implements ComponentCallbacks {
    static final int OVERLAY_TYPE=2017;
    // Called only inside hooked SystemUI (INTERNAL_SYSTEM_WINDOW), with exact API37 preflight.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static void trust(WindowManager.LayoutParams p){
        try{WindowManager.LayoutParams.class.getDeclaredMethod("setTrustedOverlay").invoke(p);}
        catch(Exception e){throw new IllegalStateException("trusted overlay preflight",e);}
    }
    static volatile SystemUiHost live;
    private boolean settingsReady;
    boolean ready(){return settingsReady&&!cleaned;}
    private final java.util.concurrent.ExecutorService settingsWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private SystemUiHost(Context c){super(c);}
    // The target framework defines TYPE_STATUS_BAR_SUB_PANEL (2017); the public SDK IntDef omits it.
    @android.annotation.SuppressLint("WrongConstant")
    private static Context overlayContext(Context display){return display.createWindowContext(OVERLAY_TYPE,null);}
    static void start(Context c){
        if(live!=null)return;
        trust(new WindowManager.LayoutParams());
        Context display=c.createDisplayContext(c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0));
        Context window=overlayContext(display);
        SystemUiHost host=new SystemUiHost(new android.view.ContextThemeWrapper(window,android.R.style.Theme_DeviceDefault_DayNight));
        live=host;
        try{host.initialize();}catch(Throwable failure){host.cleanup();android.util.Log.e("NativeComplements","HOST_START_FAILED",failure);}
    }
    private final BroadcastReceiver settingsReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        try{if(getSentFromUid()==getPackageManager().getApplicationInfo(Wire.PKG,0).uid)loadSettings();}catch(Exception ignored){}
    }};
    private final BroadcastReceiver queryReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        try{int uid=getSentFromUid();if(uid==getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid||uid==getApplicationInfo().uid)publish(true);}catch(Exception ignored){}
    }};
    private void loadSettings(){settingsWorker.execute(()->{
        try{android.os.Bundle b=getContentResolver().call(android.net.Uri.parse("content://"+ConfigProvider.AUTHORITY),"read",null,null);
            if(b==null)throw new IllegalStateException("empty settings");
            ConfigProvider.copy(b,config.prefs);
            main.post(()->{settingsReady=true;reloadSettings();});
        }catch(Exception e){android.util.Log.e("NativeComplements","SETTINGS_READ_FAILED",e);main.postDelayed(()->{if(!cleaned)loadSettings();},3000);}
    });}
    private final Runnable poll=new Runnable(){public void run(){if(cleaned)return;Wire.send(SystemUiHost.this,"STATUS");main.postDelayed(this,750);}};
    private void reloadSettings(){
        if(cleaned)return;showSwipeHint(0);hideFan();hidePanel();removeHot();
        if(!config.enabled()&&controls!=null){
            if("active".equals(backendState)||"recovery".equals(backendState))Wire.send(this,"RESTORE");
            controls.hide();
        }
        refresh();Wire.send(this,"STATUS");
    }
    private final Runnable reload=this::reloadSettings;
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
                    Wire.send(SystemUiHost.this,"GESTURE",pkg,taskId,Wire.token(),false);
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
        if(i.hasExtra("foreground")){foreground=i.getStringExtra("foreground");keyboard=i.getBooleanExtra("imeVisible",false);systemFocus=i.getBooleanExtra("systemOverlay",false);
            updateOverlayPriority();controls.imeTop(i.getIntExtra("imeTop",Integer.MAX_VALUE));refresh();}
        if(i.hasExtra("immersive"))immersive=i.getBooleanExtra("immersive",false);
        activeTask="active".equals(backendState)?i.getIntExtra("taskId",-1):-1;
        publish(false);
        try{if(!config.enabled()||!getSystemService(android.os.PowerManager.class).isInteractive()||getSystemService(KeyguardManager.class).isKeyguardLocked())controls.hide();else controls.update(i);}catch(RuntimeException e){controls.hide();android.util.Log.e("NativeComplements","overlay controls failed",e);}
        if(i.getStringExtra("event")!=null&&(i.getStringExtra("event").endsWith("FAILED")||"BLOCKED".equals(i.getStringExtra("event"))))Toast.makeText(this,i.getStringExtra("report"),Toast.LENGTH_LONG).show();
    }
    private List<AppCatalog.App> all=List.of();
    private String foreground="";
    private boolean keyboard,cleaned,receiverRegistered;
    private int lastSelection=-1;
    private long lastOpen;
    private int touchLogs;
    static volatile String lastTouch="尚未收到角落触摸";
    private void touchLog(String message){lastTouch=message;if(touchLogs++<50)android.util.Log.i("CornerLauncher",message);}
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(p,k)->{main.removeCallbacks(reload);main.post(reload);};
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){hideFan();hidePanel();removeHot();if(controls!=null)controls.hide();refresh();Wire.send(SystemUiHost.this,"STATUS");}
    };
    private final BroadcastReceiver packageReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){loadApps();}};

    private void initialize() {
        cleaned=false;connected=true;live=this;
        Config.hostPreferences=getSharedPreferences("nativecomplements_corner",Context.MODE_PRIVATE);
        config=new Config(this);wm=getSystemService(WindowManager.class);
        controls=new WindowControls(this);
        Wire.listen(this::receiveFrame);
        registerReceiver(backendReceiver,new IntentFilter(Wire.RESULT),null,main,Context.RECEIVER_EXPORTED);
        Wire.send(this,"STATUS");
        config.prefs.registerOnSharedPreferenceChangeListener(prefListener);
        IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);screen.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver,screen,Context.RECEIVER_NOT_EXPORTED);
        IntentFilter pkgs=new IntentFilter(Intent.ACTION_PACKAGE_ADDED);pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);pkgs.addDataScheme("package");
        registerReceiver(packageReceiver,pkgs,Context.RECEIVER_NOT_EXPORTED);receiverRegistered=true;
        registerReceiver(settingsReceiver,new IntentFilter(ConfigProvider.CHANGED),null,main,Context.RECEIVER_EXPORTED);
        registerReceiver(queryReceiver,new IntentFilter(LauncherGuard.QUERY),null,main,Context.RECEIVER_EXPORTED);
        getApplicationContext().registerComponentCallbacks(this);
        loadApps();loadSettings();main.post(poll);
    }
    private void loadApps() {
        new Thread(()->{
            List<AppCatalog.App> apps=AppCatalog.load(this);
            main.post(()->{if(!connected||cleaned)return;all=apps;refresh();});
        },"corner-app-catalog").start();
    }
    private void updateOverlayPriority(){if(controls!=null)controls.systemOverlay(screenshotVisible||systemFocus);}
    private boolean allowed() {
        if(config==null||!settingsReady||!config.enabled()||!backendReady||cleaned||screenshotVisible||systemFocus||!getSystemService(android.os.PowerManager.class).isInteractive())return false;
        KeyguardManager kg=getSystemService(KeyguardManager.class);
        return !Rules.blocked(kg==null||kg.isKeyguardLocked(),
            getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,config.landscapeOff(),
            keyboard,config.keyboardOff(),config.blocked().contains(foreground));
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int bottom(){return wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;}
    private WindowManager.LayoutParams params(int width,int height,int flags) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,OVERLAY_TYPE,flags,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.setTitle("NativeComplements-corner");trust(p);return p;
    }
    private void refresh() {
        if(!allowed()){hideFan();if(panel!=null)hidePanel();removeHot();return;}
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
        sendBroadcast(state,null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        sendBroadcast(new Intent(state).setPackage(BackBridge.PACKAGE),null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private void showPanel() {
        if(panel!=null)return;
        if(all.isEmpty()){Toast.makeText(this,"应用列表正在加载，请稍后重试",Toast.LENGTH_SHORT).show();return;}
        LinearLayout box=new LinearLayout(this){
            @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)hidePanel();return true;}return super.dispatchKeyEvent(e);}
        };
        box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(0xff20252c);
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
        try{wm.addView(outside,p);publish(false);}catch(RuntimeException failure){panel=null;Toast.makeText(this,"应用面板打开失败",Toast.LENGTH_SHORT).show();}
    }
    private void hidePanel(){if(panel!=null){InputMethodManager imm=getSystemService(InputMethodManager.class);if(imm!=null)imm.hideSoftInputFromWindow(panel.getWindowToken(),0);remove(panel);panel=null;publish(false);}}
    private void launch(String pkg) {
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastOpen<500)return;lastOpen=now;
        if(!Rules.validPackage(pkg))return;
        main.postDelayed(()->{
            if(cleaned)return;
            if(!backendReady){Wire.send(this,"STATUS");Toast.makeText(this,"后端尚未就绪，请先在模块内预检",Toast.LENGTH_LONG).show();return;}
            Wire.send(this,"OPEN",pkg,-1,Wire.token(),false);
        },180);
    }
    @Override public void onConfigurationChanged(Configuration c){hideFan();hidePanel();removeHot();refresh();Wire.send(this,"STATUS");}
    @Override public void onLowMemory(){}
    private void cleanup(){if(cleaned)return;settingsWorker.shutdownNow();try{getApplicationContext().unregisterComponentCallbacks(this);unregisterReceiver(settingsReceiver);unregisterReceiver(queryReceiver);}catch(RuntimeException ignored){}if(controls!=null){controls.close();}try{unregisterReceiver(backendReceiver);}catch(RuntimeException ignored){}Wire.listen(null);cleaned=true;connected=false;if(live==this)live=null;main.removeCallbacksAndMessages(null);hideFan();hidePanel();removeHot();publish(true);if(config!=null)config.prefs.unregisterOnSharedPreferenceChangeListener(prefListener);if(receiverRegistered){unregisterReceiver(screenReceiver);unregisterReceiver(packageReceiver);receiverRegistered=false;}}
}
