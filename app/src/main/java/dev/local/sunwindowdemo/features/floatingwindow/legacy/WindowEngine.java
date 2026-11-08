package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.app.ActivityOptions;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.util.AtomicFile;
import android.view.Choreographer;
import android.view.animation.PathInterpolator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.*;

/** One explicit task, one finite lease, original WM methods for entry/exit.
 * No global freeform override, input injection, or system configuration writes.
 */
final class WindowEngine {
    final FrameworkAccess a;
    private final Consumer<String> log;
    private final AtomicFile journal = new AtomicFile(new File("/data/system/sunwindow-demo5.json"));
    private Context context;
    private Object wms, root, lock, atm;
    private Handler handler;
    private Choreographer frames;
    private IBinder frameSink;
    private volatile int clientUid=-1;
    private volatile boolean hideRecents;
    private long frameSequence;
    static final String HISTORY=Wire.PKG+".HISTORY",HISTORY_QUERY=Wire.PKG+".HISTORY_QUERY";
    private final WindowHistory history=new WindowHistory();
    private final AtomicFile historyFile=new AtomicFile(new File("/data/system/sunwindow-hidden-tasks.json"));
    private final java.util.Map<Integer,Long> fullscreenSeen=new java.util.HashMap<>();
    private final PathInterpolator modeCurve=new PathInterpolator(.2f,0,0,1);
    private final Binder controlChannel=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code!=Wire.MESSAGE)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=clientUid||clientUid<0)return false;
            data.enforceInterface(Wire.CHANNEL);
            Intent i=Intent.CREATOR.createFromParcel(data);data.enforceNoDataAvail();
            String op=i.getStringExtra("op");
            if(!java.util.Set.of("RESIZE","END_RESIZE","MOVE","RELEASE","DOCK","MINI","NORMAL","FULL","CLOSE").contains(op==null?"":op))return false;
            handler.post(()->enqueueControl(i));return true;
        }
    };
    private void enqueueControl(Intent i){
        if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        Session s=active;
        if(s==null||s.restoring||!s.token.equals(i.getStringExtra("token")))return;
        String op=i.getStringExtra("op");
        command(i);
    }
    private volatile Session active;
    private Session pendingRecovery;
    private int recoveryWaits;
    private int endedTaskId=-1;
    private long openGeneration;
    private volatile boolean refreshQueued;
    private String last = "后端正在初始化";
    private boolean attached;

    private static final class Session {
        Object task, parent;
        int taskId, userId, mode, logicalShort, topState;
        String pkg, token, component, baseId;
        String frame="normal";
        TaskDecoration decoration; volatile boolean presented;
        Point crop; boolean closing; int animationRev; float defaultScale;
        long nextStatus;
        Rect bounds;
        boolean alwaysOnTop, landscape, forceTranslucent, restoreTranslucent;
        boolean backRequested,backComplete;
        boolean entering,entryStarted;
        Configuration originalConfig;
        String recoveryReason;
        long startedAt=SystemClock.elapsedRealtime();
        volatile boolean restoring;
        long deadline;
        IBinder lease;
        IBinder.DeathRecipient death;
        volatile Geometry.Placement placement;
        Geometry.Placement normalPlacement;
        volatile boolean transitioning,fullTransition,transitionHandle;
        volatile float radiusFactor=1,barAlpha=1,opacity=1;
        int displayW, displayH;
    }

    WindowEngine(FrameworkAccess access, Consumer<String> logger) { a = access; log = logger; }

    void attach(Object service) {
        if (attached) return;
        try {
            wms = service; context = (Context) a.field(wms, "mContext");
            root = a.field(wms, "mRoot"); lock = a.field(wms, "mGlobalLock"); atm = a.field(wms, "mAtmService");
            HandlerThread worker = new HandlerThread("SunWindowDemo5",android.os.Process.THREAD_PRIORITY_DISPLAY); worker.start();
            handler = new Handler(worker.getLooper());
            handler.post(()->{
                try{frames=(Choreographer)Choreographer.class.getDeclaredMethod("getSfInstance").invoke(null);}
                catch(Exception e){frames=Choreographer.getInstance();log.accept("SF_CLOCK_FALLBACK "+e.getClass().getSimpleName());}
            });
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!Wire.COMMAND.equals(i.getAction())) return;
                    // Permission is enforced by registerReceiver; validate the originating UID too.
                    try {
                        int expected = context.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid;
                        if (getSentFromUid() != expected || !UserHandle.getUserHandleForUid(expected).equals(android.os.Process.myUserHandle())) return;
                        clientUid=expected;
                        Bundle extras=i.getExtras();
                        if(extras!=null&&extras.getBinder("frameChannel")!=null)frameSink=extras.getBinder("frameChannel");
                    } catch (Exception e) { return; }
                    String op=i.getStringExtra("op");
                    if("RESIZE".equals(op)||"END_RESIZE".equals(op)||"MOVE".equals(op)||"RELEASE".equals(op)||"DOCK".equals(op))enqueueControl(i);
                    else command(i);
                }
            }, new IntentFilter(Wire.COMMAND), Wire.PERMISSION, handler, Context.RECEIVER_EXPORTED);
            context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                try{if(getSentFromUid()==context.getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid)pushHistory();}
                catch(Exception ignored){}
            }},new IntentFilter(HISTORY_QUERY),null,handler,Context.RECEIVER_EXPORTED);
            attached = true;
            handler.post(() -> {
                loadHistory();historyTick();pushHistory();
                note("READY: Android " + Build.VERSION.SDK_INT + "；仅系统框架；默认无窗口改动\n" + capabilities());
                if (journal.getBaseFile().exists()) recover("初始化恢复");
            });
        } catch (Throwable e) { log.accept("ATTACH_FAILED " + FrameworkAccess.reason(e)); }
    }

    private String capabilities() {
        try {
            synchronized (lock) {
                return "multiWindow=" + a.field(atm, "mSupportsMultiWindow")
                    + "\nfreeform=" + a.field(atm, "mSupportsFreeformWindowManagement")
                    + "\n恢复记录=" + journal.getBaseFile().exists();
            }
        } catch (Exception e) { return "能力读取失败：" + FrameworkAccess.reason(e); }
    }

    private void command(Intent i) {
        try {
            String op = i.getStringExtra("op");
            if(i.getIntExtra("protocol",0)==Wire.PROTOCOL&&i.hasExtra("hideRecents")){
                boolean next=i.getBooleanExtra("hideRecents",false);
                if(next!=hideRecents){hideRecents=next;saveHistory();notifyRecents();}
            }
            if ("STATUS".equals(op)) { pushHistory();report("STATUS"); return; }
            if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL) throw new IllegalStateException("请重启加载 Demo8.2 后端");
            if ("GESTURE".equals(op)) { gestureStart(new Intent(i).putExtra("generation",++openGeneration),0);return; }
            if ("OPEN".equals(op)) { open(new Intent(i).putExtra("generation",++openGeneration),0); return; }
            if ("CLOSE".equals(op)||"NORMAL".equals(op)||"MINI".equals(op)||"FULL".equals(op)||"RESIZE".equals(op)||"END_RESIZE".equals(op)||"RELEASE".equals(op)||"MOVE".equals(op)||"DOCK".equals(op)) {
                control(i); return;
            }
            if ("RESTORE".equals(op)) {
                openGeneration++;recoveryWaits=0;
                // An explicit recovery must also escape a stalled native close queue.
                Session s=active;if(s!=null){s.restoring=true;s.animationRev++;s.closing=false;}
                recover("手动恢复");return;
            }
            if ("CANVAS".equals(op)) {
                Session s = active;
                if (s == null || s.restoring || !s.token.equals(i.getStringExtra("token")))
                    throw new IllegalStateException("没有对应测试任务");
                s.landscape = i.getBooleanExtra("landscape", false);
                refresh(s); note("CANVAS：逻辑画布已切换"); return;
            }
            if (!"START".equals(op)) return;
            if (active != null || journal.getBaseFile().exists())
                throw new IllegalStateException("先恢复上一轮测试");
            if (Build.VERSION.SDK_INT != 37) throw new IllegalStateException("首版仅验证 Android 17 / API 37");
            KeyguardManager kg = context.getSystemService(KeyguardManager.class);
            if (kg == null || kg.isKeyguardLocked()) throw new IllegalStateException("先解锁屏幕");
            String pkg = i.getStringExtra("package"), token = i.getStringExtra("token");
            if (!Wire.allowedPackage(pkg) || !Wire.validToken(token)) throw new IllegalArgumentException("目标或标识不匹配");
            Bundle extras = i.getExtras();
            IBinder lease = extras == null ? null : extras.getBinder("lease");
            if (lease == null || !lease.isBinderAlive()) throw new IllegalStateException("控制进程已退出");
            Session s;
            synchronized (lock) { s = snapshot(i.getIntExtra("taskId", -1), pkg, token); }
            s.lease = lease; s.deadline = SystemClock.elapsedRealtime() + Wire.TIMEOUT_MS;
            s.death = () -> handler.post(() -> { if (active == s) recover("控制进程退出"); });
            lease.linkToDeath(s.death, 0);
            try {
                save(s); // Durable original state BEFORE the first task mutation.
                synchronized (lock) {
                    if (find(s.taskId) != s.task || !identity(s.task, s) || !lease.isBinderAlive())
                        throw new IllegalStateException("准备期间任务或控制进程已改变");
                    validate(s.task);
                    a.call(atm,"deferWindowLayout");
                    try {
                        prepareGeometry(s); // Bounds hook must know the canvas before switching mode.
                        s.decoration=new TaskDecoration(a);
                        s.entering=true;s.transitioning=true;s.opacity=0;s.barAlpha=0;
                        active = s;
                        a.opacity(s.task,0);
                        a.call(s.task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
                        if(!a.bool(s.task,"isForceTranslucent"))throw new IllegalStateException("系统未保留小窗下方的内容");
                        a.call(s.task, "setWindowingMode", new Class<?>[]{int.class}, 5);
                        if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("系统未进入 freeform");
                        a.call(s.task, "setAlwaysOnTop", new Class<?>[]{boolean.class}, true);
                        refresh(s);
                        a.call(s.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2");
                        a.call(root,"ensureActivitiesVisible");
                        a.call(root,"resumeFocusedTasksTopActivities");
                    } finally { a.call(atm,"continueWindowLayout"); }
                }
                history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();
                notifyRecents();
                note("ACTIVE：单任务小窗测试，3 分钟后退出悬浮");
                if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), 250);
            } catch (Throwable e) {
                if (active == null) { lease.unlinkToDeath(s.death, 0); }
                recover("启动未完成：" + FrameworkAccess.reason(e));
                throw e;
            }
        } catch (Throwable e) { note("BLOCKED：" + FrameworkAccess.reason(e) + "\n" + capabilities()); }
    }

    private void open(Intent request,int attempt) {
        String pkg=request.getStringExtra("package");
        try {
            if(request.getLongExtra("generation",-1)!=openGeneration)return;
            long began=request.getLongExtra("began",0);
            if(began==0){began=SystemClock.elapsedRealtime();request.putExtra("began",began);}
            if(SystemClock.elapsedRealtime()-began>12000)throw new IllegalStateException("等待应用/转场超时，phase="+request.getStringExtra("phase"));
            if(!Wire.allowedPackage(pkg)||Wire.PKG.equals(pkg))throw new IllegalArgumentException("请选择可启动应用");
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("请先解锁");
            if(active!=null){
                if(pkg.equals(active.pkg)&&!active.restoring&&!active.closing){
                    synchronized(lock){
                        a.call(active.task,"setFocusable",new Class<?>[]{boolean.class},true);
                        a.call(active.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2-reopen");
                        a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                    }
                    state(active,"normal");return;
                }
                openPhase(request,"wait-close",-1);if(!active.closing&&!active.restoring)close(active);
                handler.postDelayed(()->open(request,attempt+1),40);return;
            }
            if(journal.getBaseFile().exists())throw new IllegalStateException("请先完成待恢复记录");
            final Object[] candidate={null};final long[] newest={Long.MIN_VALUE};
            synchronized(lock){a.call(root,"forAllTasks",new Class<?>[]{Consumer.class},(Consumer<Object>)task->{
                try{ComponentName component=(ComponentName)a.field(task,"realActivity");
                    if(component!=null&&pkg.equals(component.getPackageName())&&(Integer)a.field(task,"mUserId")==0
                        &&a.bool(task,"isRootTask")&&a.bool(task,"isLeafTask")){
                        long moved=(Long)a.field(task,"mLastTimeMoved");if(moved>newest[0]){newest[0]=moved;candidate[0]=task;}
                    }
                }catch(Exception e){throw new IllegalStateException(e);}
            });}
            int id=candidate[0]==null?-1:(Integer)a.field(candidate[0],"mTaskId");
            if(!request.getBooleanExtra("launched",false)){
                Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null)throw new IllegalStateException("没有启动入口");
                ActivityOptions options=ActivityOptions.makeBasic();a.call(options,"setAvoidMoveToFront");
                if(id>=0)a.call(options,"setLaunchTaskId",new Class<?>[]{int.class},id);
                request.putExtra("launched",true);openPhase(request,"launch",id);
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle());
                handler.postDelayed(()->open(request,attempt+1),32);return;
            }
            if(candidate[0]==null){openPhase(request,"wait-task",-1);handler.postDelayed(()->open(request,attempt+1),32);return;}
            synchronized(lock){if(a.animating(candidate[0])||a.integer(candidate[0],"getRelativeDisplayRotation")!=0){
                openPhase(request,"wait-native",id);handler.postDelayed(()->open(request,attempt+1),32);return;}
            }
            openPhase(request,"start",id);command(new Intent(request).putExtra("op","START").putExtra("taskId",id));
        }catch(Throwable e){note("OPEN_FAILED package="+pkg+"："+FrameworkAccess.reason(e));}
    }
    private void openPhase(Intent request,String phase,int id){
        if(phase.equals(request.getStringExtra("phase")))return;request.putExtra("phase",phase);
        log.accept("OPEN_PHASE package="+request.getStringExtra("package")+" phase="+phase+" task="+id);
    }

    private void control(Intent i) throws Exception {
        Session s=active;
        if(s==null||s.restoring||!s.token.equals(i.getStringExtra("token")))throw new IllegalStateException("小窗已结束");
        String op=i.getStringExtra("op");
        if(s.closing)return;
        if(s.fullTransition&&!"CLOSE".equals(op))return;
        s.animationRev++;s.transitioning=false;s.radiusFactor=1;
        if("CLOSE".equals(op)){openGeneration++;close(s);return;}
        if("FULL".equals(op)){openGeneration++;fullscreen(s);return;}
        if("MINI".equals(op)||"NORMAL".equals(op)){state(s,"MINI".equals(op)?"mini":"normal");return;}
        Geometry.Placement p=s.placement;
        if(p==null)return;
        synchronized(lock){
            if("RESIZE".equals(op)||"END_RESIZE".equals(op)) {
                s.barAlpha=1;
                float scale=i.getFloatExtra("scale",p.scale());
                if(!Float.isFinite(scale))throw new IllegalArgumentException("scale");
                scale=GestureMath.clampScale(scale,s.defaultScale,s.landscape,s.displayW>s.displayH);
                float cx=p.x()+p.width()*p.scale()/2,cy=p.y()+p.height()*p.scale()/2;
                s.placement=new Geometry.Placement(p.width(),p.height(),scale,cx-p.width()*scale/2,cy-p.height()*scale/2);
                if("END_RESIZE".equals(op)){
                    String target=GestureMath.release(scale,s.defaultScale,s.landscape,s.displayW>s.displayH);
                    if("full".equals(target))fullscreen(s);else state(s,target);
                    return;
                }
            } else if("RELEASE".equals(op)) {
                String target=GestureMath.release(p.scale(),s.defaultScale,s.landscape,s.displayW>s.displayH);
                if("full".equals(target)){fullscreen(s);return;}
                state(s,target);return;
            } else if("MOVE".equals(op)||"DOCK".equals(op)) {
                if(!"mini".equals(s.frame))return;
                float x=i.getFloatExtra("x",p.x()),y=i.getFloatExtra("y",p.y());
                if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("position");
                Rect safe=safeArea(a.call(s.task,"getDisplayContent"));
                float margin=a.config(s.task,false).densityDpi/160f*12;
                if("DOCK".equals(op))x=x+p.width()*p.scale()/2<safe.centerX()?safe.left+margin:safe.right-margin-p.width()*p.scale();
                x=Math.max(safe.left,Math.min(x,safe.right-p.width()*p.scale()));
                y=Math.max(safe.top,Math.min(y,safe.bottom-p.height()*p.scale()));
                Geometry.Placement destination=new Geometry.Placement(p.width(),p.height(),p.scale(),x,y);
                if("DOCK".equals(op)){animate(s,p,destination,false,0,0);return;}
                s.placement=destination;
            }
            renderFrame(s);
        }
        if("RESIZE".equals(op)||"MOVE".equals(op))frameReport(op);else report(op);
    }

    boolean backToClose(Object task){
        Session s=active;if(s==null||s.task!=task||s.restoring||s.closing)return false;
        boolean back=false;
        for(StackTraceElement frame:Thread.currentThread().getStackTrace()){
            String n=frame.getMethodName();
            if(n.contains("onBackPressed")||n.contains("moveActivityTaskToBack")){back=true;break;}
        }
        if(!back)return false;
        log.accept("CLOSE_REQUEST source=application-back task="+s.taskId);
        try{a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);}
        catch(Exception e){log.accept("BACK_CLOSE_NO_ANIMATION_FAILED "+FrameworkAccess.reason(e));}
        handler.post(()->{if(active==s&&!s.restoring)close(s);});return true;
    }
    private void close(Session s){
        if(active!=s||s.restoring||s.closing)return;
        log.accept("CLOSE_REQUEST source=control-or-switch task="+s.taskId);
        s.closing=true;s.fullTransition=true;s.entering=false;
        visibility(s,false);
    }
    private void visibility(Session s,boolean show){
        int revision=++s.animationRev;s.transitioning=true;
        Geometry.Placement target=s.placement;
        float startAlpha=show?1:s.opacity,startBar=show?1:s.barAlpha;
        float durationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        final long duration=Math.max(0,Math.round(240_000_000L*durationScale));
        report(show?"APPEARING":"CLOSING");
        frames.postFrameCallback(new Choreographer.FrameCallback(){
            long began=-1,lastFrame=-1,maxGap;int callbacks;
            public void doFrame(long now){
                if(active!=s||s.restoring||revision!=s.animationRev)return;
                try{
                    synchronized(lock){
                        if(a.animating(s.task)){
                            if(began>=0&&lastFrame>=0)began+=now-lastFrame;
                            lastFrame=now;frames.postFrameCallback(this);return;
                        }
                        if(began<0)began=now;
                        if(lastFrame>=0)maxGap=Math.max(maxGap,now-lastFrame);lastFrame=now;
                        float t=duration==0?1:Math.min(1,(float)(now-began)/duration);
                        float progress=modeCurve.getInterpolation(show?t:1-t);
                        MotionStyle.Visibility sample=MotionStyle.visibility(target,progress);
                        s.placement=sample.placement();s.opacity=startAlpha*sample.alpha();s.barAlpha=startBar*sample.alpha();
                        renderFrame(s);callbacks++;
                        frameReport(show?"APPEARING":"CLOSING");
                        if(t<1){frames.postFrameCallback(this);return;}
                    }
                    log.accept("VISIBILITY_END show="+show+" callbacks="+callbacks+" maxCallbackGapMs="+(maxGap/1000000f));
                    if(show){s.entering=false;s.transitioning=false;s.placement=target;report("APPEARED");}
                    else recover("小窗退出动画完成");
                }catch(Throwable e){failure(s,e);}
            }
        });
    }
    private void state(Session s,String target) throws Exception {
        if(s.entering)return;
        float startBar="mini".equals(s.frame)?0:s.barAlpha;
        s.frame=target;s.animationRev++;s.fullTransition=false;s.radiusFactor=1;
        Geometry.Placement start=s.placement;
        if(start==null){refresh(s);report("STATE");return;}
        Rect safe=new Rect();
        synchronized(lock){safe.set(safeArea(a.call(s.task,"getDisplayContent")));}
        float margin=a.config(s.task,false).densityDpi/160f*12;
        float scale="mini".equals(target)?Math.min(.28f,s.defaultScale):s.defaultScale;
        Geometry.Placement normal=s.normalPlacement;
        float x=normal!=null?normal.x():safe.centerX()-start.width()*scale/2;
        float y=normal!=null?normal.y():safe.centerY()-start.height()*scale/2;
        if("mini".equals(target)) {
            Geometry.Placement mini=Geometry.miniTopRight(start,s.defaultScale,safe.left,safe.top,safe.right,safe.bottom,margin);
            x=mini.x();y=mini.y();scale=mini.scale();
        }
        Geometry.Placement end=new Geometry.Placement(start.width(),start.height(),scale,x,y);
        // Change focus after the surface morph; avoid starting a competing native transition first.
        animate(s,start,end,false,startBar,"mini".equals(target)?0:1);
    }
    private void fullscreen(Session s) throws Exception {
        Geometry.Placement start=s.placement;
        Rect bounds;
        synchronized(lock){bounds=new Rect((Rect)a.call(a.call(s.task,"getDisplayContent"),"getBounds"));
            a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},true);
            a.call(s.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2-full");}
        // Preserve aspect ratio while expanding. Real fullscreen configuration is restored at
        // the endpoint; Android owns the final app relayout (especially orientation changes).
        float scale=Math.min((float)bounds.width()/start.width(),(float)bounds.height()/start.height());
        Geometry.Placement end=new Geometry.Placement(start.width(),start.height(),scale,
            bounds.centerX()-start.width()*scale/2,bounds.centerY()-start.height()*scale/2);
        s.fullTransition=true;s.animationRev++;
        animate(s,start,end,true,"mini".equals(s.frame)?0:s.barAlpha,0);
    }
    private void animate(Session s,Geometry.Placement start,Geometry.Placement end,boolean full,float startBar,float endBar) {
        int revision=s.animationRev;s.transitioning=true;
        s.transitionHandle=startBar>0||endBar>0;
        float durationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        boolean modeChange=startBar!=endBar;
        final long duration=Math.max(0,Math.round((full||modeChange?320:336)*1_000_000L*durationScale));
        final android.animation.TimeInterpolator curve=full||modeChange?modeCurve:new android.view.animation.AccelerateDecelerateInterpolator();
        frames.postFrameCallback(new Choreographer.FrameCallback(){
            long began=-1,lastFrame=-1;
            long previousDraw=-1,maxGap,maxWork;int callbacks,nativeWaits;
            public void doFrame(long time){
                if(active!=s||s.restoring||s.animationRev!=revision)return;
                try{
                    synchronized(lock){
                        if(a.animating(s.task)){
                            nativeWaits++;
                            if(began>=0&&lastFrame>=0)began+=time-lastFrame;
                            lastFrame=time;frames.postFrameCallback(this);return;
                        }
                    }
                    if(began<0)began=time;
                    lastFrame=time;
                    float t=duration==0?1:Math.min(1,(float)(time-began)/duration);
                    float f=t==1?1:curve.getInterpolation(t);
                    long workStart=SystemClock.elapsedRealtimeNanos();
                    synchronized(lock){
                        s.placement=MotionStyle.interpolate(start,end,f);
                        s.radiusFactor=full?1-f:1;s.barAlpha=startBar+(endBar-startBar)*f;
                        renderFrame(s);
                    }
                    callbacks++;maxWork=Math.max(maxWork,SystemClock.elapsedRealtimeNanos()-workStart);
                    if(previousDraw>=0)maxGap=Math.max(maxGap,time-previousDraw);previousDraw=time;
                    frameReport("STATE");
                    if(t<1)frames.postFrameCallback(this);
                    else{
                        log.accept("ANIMATION_END frame="+s.frame+" full="+full+" callbacks="+callbacks
                            +" nativeWaits="+nativeWaits+" maxCallbackGapMs="+(maxGap/1000000f)+" maxWorkMs="+(maxWork/1000000f));
                        s.transitioning=false;
                        if(full)recover("小窗动画进入全屏");
                        else{
                            synchronized(lock){
                                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},!"mini".equals(s.frame));
                                a.call(root,"resumeFocusedTasksTopActivities");a.call(wms,"requestTraversal");
                            }
                            report("STATE_END");
                        }
                    }
                }catch(Throwable e){failure(s,e);}
            }
        });
    }

    private Object find(int taskId) throws Exception {
        // MATCH_TASK_IN_STACKS_ONLY=0: never resurrect a recent/background task.
        return a.lookup.invoke(root, taskId, 0);
    }

    private Session snapshot(int taskId, String pkg, String token) throws Exception {
        Object task = taskId < 0 ? a.focused.invoke(root) : find(taskId);
        if (task == null) throw new IllegalStateException("目标任务不存在");
        Session s = new Session(); s.task = task; s.taskId = (Integer) a.field(task, "mTaskId");
        s.userId = (Integer) a.field(task, "mUserId"); s.pkg = pkg; s.token = token;
        ComponentName component = (ComponentName) a.field(task, "realActivity");
        if (component == null || !pkg.equals(component.getPackageName())) throw new IllegalStateException("应用身份不匹配");
        s.component = component.flattenToString();
        Intent base=(Intent)a.call(task,"getBaseIntent");
        s.baseId=base==null?null:base.getIdentifier();
        if(Wire.PKG.equals(pkg)&&!token.equals(s.baseId)) throw new IllegalStateException("诊断任务标识不匹配");
        Point crop=(Point)a.field(task,"mLastSurfaceSize");s.crop=new Point(crop.x,crop.y);
        if(s.crop.x<0||s.crop.y<0) throw new IllegalStateException("原始裁剪不可用");
        validate(task);
        if (a.integer(task, "getWindowingMode") != 1 || a.bool(task, "isAlwaysOnTop")||!a.bool(task,"isFocusable"))
            throw new IllegalStateException("首版只接管普通全屏任务");
        Object requested = a.windowConfig(task, true);
        s.originalConfig=new Configuration(a.config(task,true));
        s.mode = a.integer(requested, "getWindowingMode");
        s.topState = (Integer) a.field(requested, "mAlwaysOnTop");
        s.bounds = new Rect((Rect) a.call(requested, "getBounds"));
        if ((s.mode != 0 && s.mode != 1) || !s.bounds.isEmpty() || (s.topState != 0 && s.topState != 2))
            throw new IllegalStateException("原请求配置不是标准全屏");
        s.alwaysOnTop = false; s.parent = a.call(task, "getParent");
        s.forceTranslucent=a.bool(task,"isForceTranslucent");s.restoreTranslucent=true;
        Rect display = (Rect) a.call(a.call(task, "getDisplayContent"), "getBounds");
        s.logicalShort = Math.min(display.width(), display.height());
        return s;
    }

    private void validate(Object task) throws Exception {
        if(a.bool(task,"isForceOpaque"))throw new IllegalStateException("系统已强制此任务遮挡底层，不接管");
        if (!(Boolean) a.field(atm, "mSupportsMultiWindow") || !(Boolean) a.field(atm, "mSupportsFreeformWindowManagement"))
            throw new IllegalStateException("ROM 未启用原生 freeform；此版保持预检，不全局强开");
        if (!a.bool(task, "isRootTask") || !a.bool(task, "isLeafTask") || !a.bool(task, "isAttached")
                || !a.tda.isInstance(a.call(task, "getParent"))) throw new IllegalStateException("任务层级不受首版支持");
        Object display = a.call(task, "getDisplayContent");
        if (display == null || (Integer) a.field(display, "mDisplayId") != 0 || (Integer) a.field(task, "mUserId") != 0)
            throw new IllegalStateException("首版只测试主用户、内置屏幕");
        if (a.integer(task, "getActivityType") != 1 || a.animating(task)
                || a.integer(task, "getRelativeDisplayRotation") != 0) throw new IllegalStateException("等待当前任务转场结束后再试");
        Object area = a.call(task, "getDisplayArea");
        if (!(Boolean) a.call(task, "supportsFreeformInDisplayArea", new Class<?>[]{a.tda}, area)
            || !(Boolean) a.call(area, "isValidWindowingMode", new Class<?>[]{int.class, a.activity, a.task}, 5, null, task)
            || !a.bool(task, "isOverrideBoundsAllowed") || !a.bool(task, "isOverrideWindowingModeAllowed"))
            throw new IllegalStateException("当前任务不支持可调整的 freeform");
    }

    private boolean identity(Object task, Session s) throws Exception {
        if (task == null || (Integer) a.field(task, "mTaskId") != s.taskId || (Integer) a.field(task, "mUserId") != s.userId) return false;
        Intent base = (Intent) a.call(task, "getBaseIntent");
        ComponentName real = (ComponentName) a.field(task, "realActivity");
        return base != null && java.util.Objects.equals(s.baseId,base.getIdentifier()) && real != null
            && s.component.equals(real.flattenToString()) && s.pkg.equals(real.getPackageName());
    }

    private Rect safeArea(Object display) throws Exception {
        Rect bounds=(Rect)a.call(display,"getBounds"),safe=new Rect(bounds);
        boolean horizontal=bounds.width()>bounds.height();
        if(!horizontal)a.call(display,"getStableRect",new Class<?>[]{Rect.class},safe);
        Object state=a.call(a.call(display,"getInsetsStateController"),"getRawInsetsState");
        int types=android.view.WindowInsets.Type.displayCutout();
        if(!horizontal)types|=android.view.WindowInsets.Type.systemBars();
        Insets insets=(Insets)a.call(state,"calculateInsets",new Class<?>[]{Rect.class,Rect.class,int.class,boolean.class},bounds,bounds,types,true);
        safe.left=Math.max(safe.left,bounds.left+insets.left);safe.top=Math.max(safe.top,bounds.top+insets.top);
        safe.right=Math.min(safe.right,bounds.right-insets.right);safe.bottom=Math.min(safe.bottom,bounds.bottom-insets.bottom);
        return safe;
    }
    private void prepareGeometry(Session s) throws Exception {
            Object display = a.call(s.task, "getDisplayContent");
            Rect displayBounds = (Rect) a.call(display, "getBounds");
            Rect safe = safeArea(display);
            int margin = Math.round(a.config(display, false).densityDpi / 160f * (displayBounds.width()>displayBounds.height()?4:12));
            Geometry.Placement p = Geometry.place(s.logicalShort, s.landscape,
                safe.left, safe.top, safe.right, safe.bottom, margin);
            s.defaultScale=p.scale();s.normalPlacement=p;
            if("mini".equals(s.frame)) p=Geometry.miniTopRight(p,p.scale(),safe.left,safe.top,safe.right,safe.bottom,margin);
            s.placement = p; s.displayW = displayBounds.width(); s.displayH = displayBounds.height();
    }
    private void refresh(Session s) throws Exception {
        synchronized (lock) {
            if (active != s || s.restoring) return;
            if (find(s.taskId) != s.task || !identity(s.task, s) || a.call(s.task, "getParent") != s.parent)
                throw new IllegalStateException("任务身份或父层发生变化");
            if (a.animating(s.task)) return;
            if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("任务已退出 freeform");
            Geometry.Placement previous=s.placement;
            int oldW=s.displayW,oldH=s.displayH;
            prepareGeometry(s);
            if(previous!=null&&oldW==s.displayW&&oldH==s.displayH
                &&previous.width()==s.placement.width()&&previous.height()==s.placement.height())s.placement=previous;
            else{
                s.animationRev++;s.transitioning=false;s.fullTransition=false;s.radiusFactor=1;s.barAlpha="mini".equals(s.frame)?0:1;
                if(s.entering){s.entryStarted=false;s.presented=false;s.transitioning=true;s.opacity=0;s.barAlpha=0;}
            }
            Geometry.Placement p=s.placement;
            Rect logical = new Rect(0, 0, p.width(), p.height());
            if (!logical.equals(a.call(s.task, "getBounds")))
                a.call(s.task, "setBounds", new Class<?>[]{Rect.class}, logical);
            // Check actual post-configuration geometry, not only the requested rectangle.
            Rect actual = (Rect) a.call(s.task, "getBounds");
            if (actual.width() != p.width() || actual.height() != p.height())
                throw new IllegalStateException("逻辑画布被系统重写：" + actual);
            apply(s); a.call(root,"ensureActivitiesVisible");a.call(wms, "requestTraversal");
        }
    }

    private boolean matches(Object task,WindowHistory.Entry e) throws Exception {
        if(task==null||e==null||(Integer)a.field(task,"mTaskId")!=e.taskId()||(Integer)a.field(task,"mUserId")!=e.userId())return false;
        ComponentName c=(ComponentName)a.field(task,"realActivity");Intent intent=(Intent)a.call(task,"getBaseIntent");
        return c!=null&&e.component().equals(c.flattenToString())&&intent!=null&&java.util.Objects.equals(e.baseId(),intent.getIdentifier());
    }
    private void loadHistory(){
        if(!historyFile.getBaseFile().exists())return;
        try{byte[] bytes=historyFile.readFully();if(bytes.length>262144)throw new IOException("history size");
            JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));hideRecents=doc.optBoolean("enabled",false);
            JSONArray entries=doc.getJSONArray("tasks");if(entries.length()>1024)throw new IOException("history count");
            for(int n=0;n<entries.length();n++){JSONObject o=entries.getJSONObject(n);
                if(o.getInt("id")<0||o.getInt("user")!=0||!Wire.allowedPackage(o.getString("pkg")))continue;
                history.windowed(new WindowHistory.Entry(o.getInt("id"),o.getInt("user"),o.getString("pkg"),o.getString("component"),o.isNull("base")?null:o.getString("base")));
            }
        }catch(Exception e){log.accept("HISTORY_LOAD_FAILED "+FrameworkAccess.reason(e));}
    }
    private void saveHistory(){
        FileOutputStream stream=null;
        try{JSONArray entries=new JSONArray();for(WindowHistory.Entry e:history.entries())entries.put(new JSONObject()
                .put("id",e.taskId()).put("user",e.userId()).put("pkg",e.pkg()).put("component",e.component()).put("base",e.baseId()==null?JSONObject.NULL:e.baseId()));
            byte[] bytes=new JSONObject().put("version",1).put("enabled",hideRecents).put("tasks",entries).toString().getBytes(StandardCharsets.UTF_8);
            stream=historyFile.startWrite();stream.write(bytes);historyFile.finishWrite(stream);
        }catch(Exception e){if(stream!=null)historyFile.failWrite(stream);log.accept("HISTORY_SAVE_FAILED "+FrameworkAccess.reason(e));}
    }
    private void pushHistory(){
        if(context==null)return;
        Intent state=new Intent(HISTORY).setPackage(LauncherGuard.PACKAGE).putExtra("protocol",Wire.PROTOCOL)
            .putExtra("hiddenTaskIds",hideRecents?history.ids():new int[0]);
        try{context.sendBroadcast(state,null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
        catch(Exception e){log.accept("HISTORY_PUBLISH_FAILED "+FrameworkAccess.reason(e));}
    }
    private void historyTick(){
        boolean changed=false;
        for(WindowHistory.Entry e:history.entries())try{synchronized(lock){
            Object task=find(e.taskId());
            if(task==null){Object recent=a.lookup.invoke(root,e.taskId(),1);if(recent==null){history.removed(e.taskId());changed=true;}continue;}
            if(!matches(task,e)){history.removed(e.taskId());changed=true;continue;}
            Session owned=active;boolean ours=owned!=null&&owned.task==task;
            Object win=a.call(task,"getTopVisibleAppMainWindow");
            boolean focused=a.focused.invoke(root)==task;
            boolean ready=!ours&&focused&&a.integer(task,"getWindowingMode")==1&&a.bool(task,"isVisibleRequested")&&!a.animating(task)
                &&win!=null&&a.bool(win,"isDrawn")&&a.bool(win,"isVisible");
            if(ready){
                Configuration reported=(Configuration)a.call(a.field(win,"mLastReportedConfiguration"),"getMergedConfiguration");
                Rect expected=(Rect)a.call(task,"getBounds");Rect actual=(Rect)a.call(a.field(reported,"windowConfiguration"),"getBounds");
                Rect frame=(Rect)a.call(win,"getFrame");
                // Letterboxed fullscreen apps may have a smaller app frame. Require the
                // client to report fullscreen mode and its frame to fit the actual task.
                Object reportedWindow=a.field(reported,"windowConfiguration");
                ready=a.integer(reportedWindow,"getWindowingMode")==1&&!frame.isEmpty()
                    &&expected.contains(frame)&&!actual.isEmpty();
            }
            if(!ready){fullscreenSeen.remove(e.taskId());continue;}
            long now=SystemClock.elapsedRealtime();Long seen=fullscreenSeen.putIfAbsent(e.taskId(),now);
            if(seen==null||now-seen<120)continue;
            try{a.call(a.field(wms,"mTaskSnapshotController"),"recordSnapshot",new Class<?>[]{a.task},task);}
            catch(Exception ex){log.accept("SNAPSHOT_REFRESH_FAILED task="+e.taskId()+" "+FrameworkAccess.reason(ex));}
            if(history.fullscreenReady(e.taskId(),false,true,true,true,false)){
                fullscreenSeen.remove(e.taskId());changed=true;log.accept("HISTORY_FULLSCREEN_READY task="+e.taskId());
            }
        }}catch(Exception ex){log.accept("HISTORY_CHECK_FAILED task="+e.taskId()+" "+FrameworkAccess.reason(ex));}
        if(changed){saveHistory();notifyRecents();}
        handler.postDelayed(this::historyTick,400);
    }
    private void notifyRecents(){
        pushHistory();
        try{synchronized(lock){a.call(a.field(atm,"mTaskChangeNotificationController"),"notifyTaskStackChanged");}}
        catch(Exception e){log.accept("RECENTS_REFRESH_FAILED "+FrameworkAccess.reason(e));}
    }
    boolean hideRecent(Object task){
        if(!hideRecents)return false;
        try{return matches(task,history.get((Integer)a.field(task,"mTaskId")));}catch(Exception e){return false;}
    }
    boolean preserveImmersive(Object policy,Object focused){
        Session s=active;if(s==null||s.restoring||focused==null)return false;
        try{
            Object display=a.field(policy,"mDisplayContent");
            if(display!=a.call(s.task,"getDisplayContent"))return false;
            Object dp=a.call(display,"getDisplayPolicy");
            Object background=a.field(dp,"mTopFullscreenOpaqueWindowState");
            if(background==null||a.call(background,"getTask")==s.task)return false;
            android.view.WindowManager.LayoutParams attrs=(android.view.WindowManager.LayoutParams)a.field(focused,"mAttrs");
            boolean screenshot="com.android.systemui".equals(attrs.packageName)&&attrs.getTitle()!=null&&attrs.getTitle().toString().contains("Screenshot");
            if(focused!=background&&!owns(a.call(focused,"getTask"))&&!screenshot)return false;
            // Respect all other native multi-window demands; suppress only our freeform force.
            for(Object child:(java.util.List<?>)a.field(s.parent,"mChildren")){
                if(child==s.task||!a.task.isInstance(child)||!a.bool(child,"isVisible"))continue;
                int mode=a.integer(child,"getWindowingMode");if(mode!=1&&mode!=2)return false;
            }
            return true;
        }catch(Exception e){return false;}
    }
    private void gestureStart(Intent request,int attempt){
        try{
            if(request.getLongExtra("generation",-1)!=openGeneration)return;
            if(attempt>20)throw new IllegalStateException("桌面转场尚未结束，请重试");
            if(active!=null||journal.getBaseFile().exists())throw new IllegalStateException("先关闭当前小窗");
            synchronized(lock){
                Object task=find(request.getIntExtra("taskId",-1));
                if(task==null)throw new IllegalStateException("手势任务已结束");
                ComponentName component=(ComponentName)a.field(task,"realActivity");
                if(component==null||!component.getPackageName().equals(request.getStringExtra("package")))throw new IllegalStateException("手势任务已变化");
                if(a.animating(task)){handler.postDelayed(()->gestureStart(request,attempt+1),32);return;}
            }
            command(new Intent(request).putExtra("op","START"));
        }catch(Exception e){note("GESTURE_FAILED："+FrameworkAccess.reason(e));}
    }
    boolean owns(Object task) { Session s = active; return s != null && s.task == task && !s.restoring; }
    boolean blockContentTouch(Object window){
        Session s=active;if(s==null||s.restoring||(!s.closing&&!"mini".equals(s.frame)))return false;
        try{return a.call(window,"getTask")==s.task;}catch(Exception e){return false;}
    }
    void collected(Object transition,Object container){
        Session s=active;if(s==null||s.task!=container||s.fullTransition&&!s.closing)return;
        try{a.call(transition,"setNoAnimation",new Class<?>[]{a.wc},container);}
        catch(Exception e){log.accept("NO_ANIMATION_FAILED "+FrameworkAccess.reason(e));}
    }
    void beforeBackInner(Object task,Object transition){
        Session s=active;if(s==null||s.task!=task||!s.closing||!s.backRequested)return;
        if(transition!=null){collected(transition,task);requesting(transition,task);}
    }
    void requesting(Object transition,Object task){
        Session s=active;if(s==null||s.task!=task||!s.closing||!s.backRequested||transition==null)return;
            try{
                // Task.moveTaskToBack creates TRANSIT_TO_BACK=4 for this request.
                // The task is already transparent. Shell can finish this invisible
                // transition without constructing a second closing animation.
                // Do not disable a shared opening transition from another task.
                if((Integer)a.field(transition,"mType")==4&&(a.integer(transition,"getFlags")&a.invisibleFlag)==0){
                    a.call(transition,"addFlag",new Class<?>[]{int.class},a.invisibleFlag);
                    log.accept("CLOSE_TRANSITION_INVISIBLE task="+s.taskId+" flag="+a.invisibleFlag);
                }
            }catch(Exception e){log.accept("CLOSE_TRANSITION_FLAG_FAILED "+FrameworkAccess.reason(e));}
    }
    void afterBackInner(Object task){
        Session s=active;if(s==null||s.task!=task||!s.closing||!s.backRequested)return;
        s.backComplete=true;
        log.accept("CLOSE_BACK_COMPLETE task="+s.taskId);
    }
    void bounds(Object task, Rect bounds) {
        Session s = active;
        if (s != null && s.task == task && !s.restoring && s.placement != null)
            bounds.set(0, 0, s.placement.width(), s.placement.height());
    }
    void prepared(Object task) {
        Session s = active;
        if (s == null || s.task != task || s.restoring) return;
        try { synchronized (lock) { apply(s); } }
        catch (Throwable e) { failure(s, e); }
    }
    // Caller holds the WM lock. Native transitions keep their own leash until finished.
    private void renderFrame(Session s) throws Exception {
        a.beginFrame();boolean success=false;
        try{apply(s);success=true;}finally{a.endFrame(success);}
    }
    private void apply(Session s) throws Exception {
        if (active != s || s.restoring || s.placement == null) return;
        if(a.animating(s.task)){
            if(s.entering||s.closing)a.opacity(s.task,0);
            if(s.decoration!=null)s.decoration.hide(s.task);return;
        }
        Rect parentBounds = (Rect) a.call(s.parent, "getBounds");
        Geometry.Placement p = s.placement;
        // Only the owned task's scale/position; crop, radius and child input metadata stay with WM.
        a.transform(s.task, p.scale(), p.x() - parentBounds.left, p.y() - parentBounds.top);
        float visualRadius=Math.min(a.config(s.task,false).densityDpi/160f*MotionStyle.radius(p.scale(),s.defaultScale)*s.radiusFactor,Math.min(p.width(),p.height())*p.scale()/2f);
        a.style(s.task,p.width(),p.height(),visualRadius/p.scale());
        a.opacity(s.task,s.opacity);
        if(s.decoration!=null){
            boolean wasPresented=s.presented;
            s.presented=s.decoration.update(s.task,s.parent,p,a.config(s.task,false).densityDpi/160f,s.displayW>s.displayH,s.barAlpha,!a.inFrame()&&!s.backRequested);
            if(s.presented&&!wasPresented)handler.post(()->{
                if(active!=s||s.restoring||s.closing)return;
                log.accept("PRESENTED task="+s.taskId+" elapsedMs="+(SystemClock.elapsedRealtime()-s.startedAt));
                if(s.entering&&!s.entryStarted){s.entryStarted=true;visibility(s,true);}
                else report("PRESENTED");
            });
        }
    }
    void configurationChanged(Object task) {
        if (!owns(task) || handler == null || refreshQueued) return;
        refreshQueued = true;
        handler.post(() -> {
            refreshQueued = false; Session s = active;
            if (s != null && !s.restoring) try { refresh(s); } catch (Throwable e) { failure(s, e); }
        });
    }
    private void failure(Session s, Throwable e) {
        // Disarm transform hooks before posting recovery; never throw into a WM caller.
        s.restoring = true;
        if (handler != null) handler.post(() -> { if (active == s) recover("适配异常：" + FrameworkAccess.reason(e)); });
    }
    private void tick(Session s) {
        if (active != s || s.restoring) return;
        try {
            if (!s.lease.isBinderAlive() || SystemClock.elapsedRealtime() >= s.deadline) { s.closing=true; recover("超时或控制进程退出"); return; }
            if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) { recover("屏幕已锁定"); return; }
            synchronized (lock) {
                Object display = a.call(s.task, "getDisplayContent");
                if (display == null) throw new IllegalStateException("任务已离开显示器");
                Rect b = (Rect) a.call(display, "getBounds");
                if (s.displayW != b.width() || s.displayH != b.height()) { refresh(s); }
                else if (!s.transitioning && !a.animating(s.task)) { apply(s); a.call(wms, "requestTraversal"); }
            }
            if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), 250);
        } catch (Throwable e) { failure(s, e); }
    }

    private void save(Session s) throws Exception {
        JSONObject o = new JSONObject().put("version", 4).put("taskId", s.taskId).put("userId", s.userId)
            .put("package", s.pkg).put("token", s.token).put("component", s.component)
            .put("baseId",s.baseId==null?JSONObject.NULL:s.baseId).put("cropX",s.crop.x).put("cropY",s.crop.y).put("mode", s.mode).put("bounds", s.bounds.flattenToString()).put("alwaysOnTop", s.alwaysOnTop).put("topState", s.topState).put("forceTranslucent",s.forceTranslucent);
        if(s.originalConfig!=null){Parcel parcel=Parcel.obtain();try{s.originalConfig.writeToParcel(parcel,0);o.put("originalConfig",android.util.Base64.encodeToString(parcel.marshall(),android.util.Base64.NO_WRAP));}finally{parcel.recycle();}}
        byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = null;
        try { stream = journal.startWrite(); stream.write(bytes); journal.finishWrite(stream); }
        catch (Exception e) { if (stream != null) journal.failWrite(stream); throw e; }
    }
    private Session read() throws Exception {
        byte[] bytes = journal.readFully();
        if (bytes.length > 65536) throw new IOException("恢复记录过大");
        JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Session s = new Session();
        s.taskId = o.getInt("taskId"); s.userId = o.getInt("userId");
        s.pkg = o.getString("package"); s.token = o.getString("token"); s.component = o.getString("component");
        s.baseId=o.getInt("version")==1?s.token:o.isNull("baseId")?null:o.getString("baseId");
        s.crop=o.getInt("version")==1?null:new Point(o.getInt("cropX"),o.getInt("cropY"));
        if(o.has("originalConfig")){byte[] data=android.util.Base64.decode(o.getString("originalConfig"),android.util.Base64.NO_WRAP);Parcel parcel=Parcel.obtain();try{parcel.unmarshall(data,0,data.length);parcel.setDataPosition(0);s.originalConfig=Configuration.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}}
        s.mode = o.getInt("mode"); s.bounds = Rect.unflattenFromString(o.getString("bounds"));
        s.topState = o.getInt("topState");
        s.alwaysOnTop = o.getBoolean("alwaysOnTop");
        s.forceTranslucent=o.getInt("version")>=3&&o.getBoolean("forceTranslucent");
        s.restoreTranslucent=o.getInt("version")>=3;
        if ((o.getInt("version") < 1 || o.getInt("version") > 4) || !Wire.allowedPackage(s.pkg) || !Wire.validToken(s.token)
            || s.userId != 0 || s.taskId < 0 || (s.mode != 0 && s.mode != 1)
            || s.bounds == null || !s.bounds.isEmpty() || s.alwaysOnTop || (s.topState != 0 && s.topState != 2))
            throw new IOException("恢复记录不匹配");
        return s;
    }
    private void recover(String why) {
        try {
            Session live = active;
            if(live!=null&&live.closing&&!live.restoring){
                live.recoveryReason=why;
                synchronized(lock){
                    if(find(live.taskId)==null){finishRecovery(live);note("REMOVED：关闭的任务已移除");return;}
                    if(!live.backRequested){
                        live.backRequested=true;live.opacity=0;live.barAlpha=0;
                        renderFrame(live);
                        a.call(live.task,"setAlwaysOnTop",new Class<?>[]{boolean.class},live.alwaysOnTop);
                        if(!(Boolean)a.call(live.task,"moveTaskToBack",new Class<?>[]{a.task},live.task))throw new IllegalStateException("系统未允许退到后台");
                    }
                    if(!live.backComplete||a.animating(live.task)||a.bool(live.task,"isVisibleRequested")){
                        if(++recoveryWaits>150){note("RECOVERY_PENDING：等待退到后台完成，请手动恢复");return;}
                        handler.postDelayed(()->{if(active==live)recover(why);},40);return;
                    }
                }
            }
            if (live != null) live.restoring = true;
            if (live == null && !journal.getBaseFile().exists()) { note("IDLE：无需恢复\n" + capabilities()); return; }
            Session s = live == null ? read() : live;
            if(live==null&&s.baseId==null&&!"手动恢复".equals(why)) {
                pendingRecovery=s;note("RECOVERY_PENDING：无稳定启动标识，需手动确认恢复");return;
            }
            pendingRecovery = s;
            synchronized (lock) {
                Object task = find(s.taskId);
                if (task == null) {
                    // Mode 1 only looks in recents; unlike mode 2, it does NOT restore a task.
                    Object recent = a.lookup.invoke(root, s.taskId, 1);
                    if (recent == null && live != null) {
                        finishRecovery(live); note("REMOVED：测试任务已从窗口栈和最近任务移除，无需恢复"); return;
                    }
                    if (recent == null) {
                        // Boot-time recents loading can be lazy. Absence is not proof of deletion.
                        note("RECOVERY_PENDING：任务尚未载入，保留记录；先打开待恢复任务再恢复"); return;
                    }
                    if (!identity(recent, s)) throw new IllegalStateException("最近任务身份不匹配，保留记录");
                    note("RECOVERY_PENDING：测试任务仅在最近任务中；点“打开待恢复任务”后再恢复"); return;
                }
                if (!identity(task, s)||(live!=null&&task!=s.task)) throw new IllegalStateException("恢复身份不匹配；未操作其他任务");
                Object display = a.call(task, "getDisplayContent");
                if (display == null || (Integer) a.field(display, "mDisplayId") != 0
                    || !a.bool(task, "isRootTask") || !a.bool(task, "isLeafTask")
                    || !a.tda.isInstance(a.call(task, "getParent"))) throw new IllegalStateException("恢复任务层级已改变");
                if (a.animating(task) || a.integer(task, "getRelativeDisplayRotation") != 0) {
                    if (++recoveryWaits > 20) { note("RECOVERY_PENDING：转场仍未结束，请稍后手动恢复"); return; }
                    handler.postDelayed(() -> {
                        if (active == s || (active == null && pendingRecovery == s)) recover(why);
                    }, 500);
                    note("RECOVERY_WAIT：等待系统转场结束"); return;
                }
                a.call(atm,"deferWindowLayout");
                try{
                if(s.closing)a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);
                if(s.decoration!=null)s.decoration.remove(task);
                a.call(task,"setFocusable",new Class<?>[]{boolean.class},true);
                if(s.restoreTranslucent) {
                    a.call(task,"setForceTranslucent",new Class<?>[]{boolean.class},s.forceTranslucent);
                    if(a.bool(task,"isForceTranslucent")!=s.forceTranslucent)throw new IllegalStateException("原任务遮挡属性尚未恢复");
                }
                a.call(task, "setAlwaysOnTop", new Class<?>[]{boolean.class}, s.alwaysOnTop);
                a.call(task, "setWindowingMode", new Class<?>[]{int.class}, s.mode);
                a.call(task, "setBounds", new Class<?>[]{Rect.class}, new Rect(s.bounds));
                Configuration restored = new Configuration(s.originalConfig!=null?s.originalConfig:a.config(task, true));
                a.call(a.field(restored, "windowConfiguration"), "setAlwaysOnTop", new Class<?>[]{int.class}, s.topState);
                a.call(task, "onRequestedOverrideConfigurationChanged", new Class<?>[]{Configuration.class}, restored);
                if (a.integer(task, "getWindowingMode") != 1) throw new IllegalStateException("原全屏模式尚未恢复");
                Object requested = a.windowConfig(task, true);
                if (a.integer(requested, "getWindowingMode") != s.mode || !s.bounds.equals(a.call(requested, "getBounds"))
                    || (Integer) a.field(requested, "mAlwaysOnTop") != s.topState)
                    throw new IllegalStateException("原请求配置尚未完全恢复");
                Point pos = new Point(); a.call(task, "getRelativePosition", new Class<?>[]{Point.class}, pos);
                a.transform(task, 1f, pos.x, pos.y);
                a.opacity(task,1f);
                // Reset the native size cache; do not reapply a stale freeform/background crop.
                Point cache=(Point)a.field(task,"mLastSurfaceSize");cache.set(-1,-1);
                a.call(task,"updateSurfaceSize",new Class<?>[]{android.view.SurfaceControl.Transaction.class},a.call(task,"getPendingTransaction"));
                // Organized tasks delegate native cropping to Shell. Clear our crop there;
                // leaving the invalidated cache would reject the next open of this task.
                if(cache.x<0||cache.y<0){a.style(task,0,0,0);cache.set(0,0);}
                // Native updateSurfaceSize owns the crop, including its zero-crop policy.
                a.call(a.call(task,"getPendingTransaction"),"setCornerRadius",new Class<?>[]{android.view.SurfaceControl.class,float.class},a.field(task,"mSurfaceControl"),0f);
                if(s.closing)a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);
                new TaskDecoration(a).restoreLayer(task);
                a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                a.call(wms, "requestTraversal");
                }finally{a.call(atm,"continueWindowLayout");}
            }
            finishRecovery(live);
            note((s.closing?"CLOSED：":"RESTORED：") + why + "；已请求恢复原全屏、边界、置顶及任务显示变换");
        } catch (Throwable e) { note("RECOVERY_FAILED：" + FrameworkAccess.reason(e) + "；保留恢复记录，稍后重试"); }
    }

    private void finishRecovery(Session live) {
        if(live!=null&&live.decoration!=null)live.decoration.dispose();
        Session ended=live!=null?live:pendingRecovery;if(ended!=null)endedTaskId=ended.taskId;
        if (live != null && live.lease != null && live.death != null)
            try { live.lease.unlinkToDeath(live.death, 0); } catch (RuntimeException ignored) { }
        active = null; pendingRecovery = null; recoveryWaits = 0; journal.delete();notifyRecents();
    }

    private String describe(Session s) {
        Geometry.Placement p = s.placement;
        return "taskId=" + s.taskId + " package=" + s.pkg + "\n"
            + (p == null ? "等待转场结束" : "逻辑=" + p.width() + "×" + p.height()
                + "；显示=" + Math.round(p.width() * p.scale()) + "×" + Math.round(p.height() * p.scale())
                + "；scale=" + p.scale())
            + "\n剩余=" + Math.max(0, (s.deadline - SystemClock.elapsedRealtime()) / 1000) + " 秒";
    }
    private void note(String value) { last = value; log.accept(value); report(value.split("：", 2)[0]); }
    private boolean backgroundImmersive(Session s){
        if(s==null||s.restoring)return false;
        try{synchronized(lock){Object display=a.call(s.task,"getDisplayContent");Object policy=a.call(display,"getDisplayPolicy");
            Object background=a.field(policy,"mTopFullscreenOpaqueWindowState");
            return background!=null&&a.call(background,"getTask")!=s.task
                &&(a.integer(background,"getRequestedVisibleTypes")&android.view.WindowInsets.Type.systemBars())==0;
        }}catch(Exception e){return false;}
    }
    private void fillFrame(Intent result,Session s){
        if(s==null||s.restoring||s.placement==null)return;
        Geometry.Placement p=s.placement;
        result.putExtra("token",s.token).putExtra("package",s.pkg).putExtra("taskId",s.taskId)
            .putExtra("frame",s.frame).putExtra("x",p.x()).putExtra("y",p.y())
            .putExtra("width",p.width()*p.scale()).putExtra("height",p.height()*p.scale())
            .putExtra("scale",p.scale()).putExtra("normalScale",s.defaultScale).putExtra("appLandscape",s.landscape).putExtra("logicalW",p.width()).putExtra("logicalH",p.height())
            .putExtra("transitioning",s.transitioning).putExtra("fullTransition",s.fullTransition)
            .putExtra("transitionHandle",s.transitionHandle).putExtra("presented",s.presented).putExtra("nativeHandle",true)
            .putExtra("barAlpha",s.barAlpha).putExtra("remaining",Math.max(0,(s.deadline-SystemClock.elapsedRealtime())/1000));
    }
    private void frameReport(String event){
        Intent i=new Intent(Wire.RESULT).putExtra("protocol",Wire.PROTOCOL).putExtra("event",event)
            .putExtra("state","active").putExtra("sequence",++frameSequence);
        fillFrame(i,active);
        if(!Wire.transmit(frameSink,i))frameSink=null;
    }
    private void report(String event) {
        if (context == null) return;
        Session s = active;
        String state = s == null ? journal.getBaseFile().exists() ? "recovery" : "idle" : s.restoring ? "recovery" : "active";
        String report = last + "\n\n" + capabilities() + (s == null ? "" : "\n" + describe(s));
        Session pending = pendingRecovery;
        Intent result = new Intent(Wire.RESULT).setPackage(Wire.PKG)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY).putExtra("event", event)
            .putExtra("state", state).putExtra("report", report).putExtra("protocol",Wire.PROTOCOL)
            .putExtra("hasJournal",journal.getBaseFile().exists()).putExtra("endedTaskId",endedTaskId);
        result.putExtra("sequence",++frameSequence);
        Bundle transport=new Bundle();transport.putBinder("controlChannel",controlChannel);result.putExtras(transport);
        fillFrame(result,s);
        result.putExtra("immersive",backgroundImmersive(s));
        if ("recovery".equals(state) && pending != null)
            result.putExtra("recoverPackage", pending.pkg).putExtra("recoverToken", pending.token)
                .putExtra("recoverComponent", pending.component);
        handler.post(() -> {
            try { context.sendBroadcast(result, Wire.PERMISSION,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()); }
            catch (Throwable e) { log.accept("RESULT_SEND_FAILED " + FrameworkAccess.reason(e)); }
        });
    }
}
