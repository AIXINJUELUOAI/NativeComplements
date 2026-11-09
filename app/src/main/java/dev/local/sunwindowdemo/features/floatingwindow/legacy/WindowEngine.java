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

/** Related freeform tasks share one presentation and a live host lease.
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
    private WindowAudio audio;
    private boolean visibleResumeReady;
    void visibleResumeReady(){visibleResumeReady=true;}
    private IBinder frameSink;
    private volatile int clientUid=-1,uiUid=-1;
    private IBinder uiLease;
    private long uiSeen;
    private boolean uiReady;
    private String uiTouch="尚未收到角落触摸";
    private boolean controlUid(int uid){return uid>=0&&(uid==clientUid||uid==uiUid);}
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
            if(!controlUid(Binder.getCallingUid()))return false;
            data.enforceInterface(Wire.CHANNEL);
            Intent i=Intent.CREATOR.createFromParcel(data);data.enforceNoDataAvail();
            String op=i.getStringExtra("op");
            if(!java.util.Set.of("RESIZE","END_RESIZE","MOVE","RELEASE","DOCK","MINI","NORMAL","FULL","CLOSE","MINI_SIZE","MUTE","RESTORE_MINI","MINI_DRAG","MINI_DRAG_CANCEL","EDGE_DRAG","EDGE_DOCK","EDGE_BAR_DRAG","EDGE_BAR_DOCK","EDGE_DRAG_CANCEL").contains(op==null?"":op))return false;
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
    private volatile java.util.List<Session> sessions=java.util.List.of();
    // A task is not owned yet during preflight. Keep that permission on this
    // thread only, then use the ordinary session lifetime after acquisition.
    private final ThreadLocal<Object> resizeCandidate=new ThreadLocal<>();
    private final java.util.Map<Object,Session> coverTasks=new java.util.IdentityHashMap<>();
    private volatile SwitchStart pendingStart;
    private volatile boolean recovering;
    final EntryTrace trace;
    private Session pendingRecovery;
    private int recoveryWaits;
    private int endedTaskId=-1;
    private volatile long openGeneration;
    private final java.util.concurrent.atomic.AtomicReference<ShortcutLaunch> pendingShortcutLaunch=
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final class ShortcutLaunch {
        final long generation;
        final String pkg;
        final int userId;
        volatile Object task;
        volatile int taskId=-1;
        volatile boolean dispatched;
        ShortcutLaunch(long generation,String pkg,int userId){
            this.generation=generation;this.pkg=pkg;this.userId=userId;
        }
    }
    private volatile boolean refreshQueued;
    private String last = "后端正在初始化";
    private boolean attached;

    private static final class Session {
        Session returnTo;
        boolean parked,restoreForeground;
        TaskVisibilityGuard startMask,rotationMask;
        TaskCover cover;boolean coverDrawn,coverFullscreen;
        final RotationGate rotation=new RotationGate();
        final java.util.Set<Object> rotationTransitions=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean rotationProbeQueued;
        Object task, parent;
        int taskId, userId, mode, logicalShort, topState;
        int uid=-1;
        boolean miniLarge,edgeHidden,geometryDirty,refreshingGeometry,resizing;
        int edgeSide;
        float placementMargin;
        Rect safeBounds;
        Geometry.Placement miniPlacement;
        String lastRuntimeState;
        Rect lastSurfaceCrop;
        String pkg, token, component, baseId;
        String frame="normal";
        TaskDecoration decoration; volatile boolean presented;
        Point crop; boolean closing; int animationRev; float defaultScale;
        long nextStatus;
        Rect bounds;
        boolean alwaysOnTop, landscape, forceTranslucent, restoreTranslucent;
        boolean backRequested,backComplete,returning;
        boolean entering,entryStarted,entryFade;
        Configuration originalConfig;
        String recoveryReason;
        long startedAt=SystemClock.elapsedRealtime();
        volatile boolean restoring;
        volatile boolean screenSuspended;
        IBinder lease;
        IBinder.DeathRecipient death;
        volatile Geometry.Placement placement;
        Geometry.Placement normalPlacement;
        volatile boolean transitioning,fullTransition,transitionHandle;
        volatile float radiusFactor=1,barAlpha=1,opacity=1;
        int displayW, displayH,displayRotation=-1;
        Geometry.Placement miniDragBase;float miniDragOpacity;
        Geometry.Placement edgeDragBase,edgeDragMini;int edgeDragSide;
        boolean edgeBarDragging;float edgeBarCenterX,edgeBarCenterY;
    }

    // Task objects and originals stay alive across switches. Only presentation changes.
    private static final int MAX_LINKED_TASKS=8;
    private record SwitchStart(Session from,Session target,boolean created,long generation) {}

    WindowEngine(FrameworkAccess access, Consumer<String> logger) {
        a=access;log=logger;trace=new EntryTrace(a,logger);
    }

    void attach(Object service) {
        if (attached) return;
        try {
            wms = service; context = (Context) a.field(wms, "mContext");
            root = a.field(wms, "mRoot"); lock = a.field(wms, "mGlobalLock"); atm = a.field(wms, "mAtmService");
            trace.attach(lock);
            HandlerThread worker = new HandlerThread("SunWindowDemo5",android.os.Process.THREAD_PRIORITY_DISPLAY); worker.start();
            handler = new Handler(worker.getLooper());
            handler.post(()->{
                audio=WindowAudio.create(context,handler,log);
                try{frames=(Choreographer)Choreographer.class.getDeclaredMethod("getSfInstance").invoke(null);}
                catch(Exception e){frames=Choreographer.getInstance();log.accept("SF_CLOCK_FALLBACK "+e.getClass().getSimpleName());}
            });
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!Wire.COMMAND.equals(i.getAction())) return;
                    // Permission is enforced by registerReceiver; validate the originating UID too.
                    try {
                        int expected = context.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid;
                        clientUid=expected;uiUid=context.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid;
                        if(!controlUid(getSentFromUid()))return;
                        Bundle extras=i.getExtras();
                        if(getSentFromUid()==uiUid&&i.getIntExtra("protocol",0)==Wire.PROTOCOL&&extras!=null){
                            uiLease=extras.getBinder("lease");uiSeen=SystemClock.elapsedRealtime();
                            uiReady=i.getBooleanExtra("hostReady",false);
                            uiTouch=i.getStringExtra("lastTouch");
                            if(extras.getBinder("frameChannel")!=null)frameSink=extras.getBinder("frameChannel");
                        }
                    } catch (Exception e) { return; }
                    String op=i.getStringExtra("op");
                    if("RESIZE".equals(op)||"END_RESIZE".equals(op)||"MOVE".equals(op)||"RELEASE".equals(op)||"DOCK".equals(op))enqueueControl(i);
                    else command(i);
                }
            }, new IntentFilter(Wire.COMMAND), null, handler, Context.RECEIVER_EXPORTED);
            context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                try{if(getSentFromUid()==context.getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid)pushHistory();}
                catch(Exception ignored){}
            }},new IntentFilter(HISTORY_QUERY),null,handler,Context.RECEIVER_EXPORTED);
            attached = true;
            IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);
            screen.addAction(Intent.ACTION_SCREEN_ON);screen.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent i){
                    Session s=active;if(s==null||s.restoring)return;
                    try{synchronized(lock){updateScreenState(s);}}catch(Exception e){failure(s,e);}
                }
            },screen,null,handler,Context.RECEIVER_NOT_EXPORTED);
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
            if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL) throw new IllegalStateException("请重启加载 0.2.0 后端");
            if ("GESTURE".equals(op)) { cancelStart("gesture");trace.stop();gestureStart(new Intent(i).putExtra("generation",++openGeneration),0);return; }
            if ("OPEN".equals(op)) { cancelStart("open");trace.stop();open(new Intent(i).putExtra("generation",++openGeneration),0); return; }
            if ("CLOSE".equals(op)||"NORMAL".equals(op)||"MINI".equals(op)||"FULL".equals(op)||"RESIZE".equals(op)||"END_RESIZE".equals(op)||"RELEASE".equals(op)||"MOVE".equals(op)||"DOCK".equals(op)||"MINI_SIZE".equals(op)||"MUTE".equals(op)||"RESTORE_MINI".equals(op)||"MINI_DRAG".equals(op)||"MINI_DRAG_CANCEL".equals(op)||"EDGE_DRAG".equals(op)||"EDGE_DOCK".equals(op)||"EDGE_BAR_DRAG".equals(op)||"EDGE_BAR_DOCK".equals(op)||"EDGE_DRAG_CANCEL".equals(op)) {
                control(i); return;
            }
            if ("RESTORE".equals(op)) {
                openGeneration++;cancelStart("manual-restore");trace.stop();recoveryWaits=0;
                // An explicit recovery must also escape a stalled native close queue.
                Session s=active;if(s!=null){s.restoring=true;s.animationRev++;s.closing=false;}
                // An already pending queue must receive the explicit recovery intent
                // too; otherwise its boot-time reason keeps missing tasks blocked.
                synchronized(lock){if(recovering)for(Session pending:sessions)pending.recoveryReason="手动恢复";}
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
            IBinder lease = uiLease;
            if (lease == null || !lease.isBinderAlive()) throw new IllegalStateException("控制进程已退出");
            Session s;
            synchronized (lock) { s = snapshot(i.getIntExtra("taskId", -1), pkg, token); }
            trace.arm(pkg,openGeneration);
            s.entryFade=i.getBooleanExtra("fadeEntry",false);
            s.lease = lease;
            s.death = () -> handler.post(() -> { if (sessions.contains(s)) recover("控制进程退出"); });
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
                        beginCover(s,s.entryFade?s.placement:new Geometry.Placement(s.displayW,s.displayH,1,0,0),s.placement,false);
                        a.opacity(s.task,0);
                        a.call(s.task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
                        if(!a.bool(s.task,"isForceTranslucent"))throw new IllegalStateException("系统未保留小窗下方的内容");
                        a.call(s.task, "setWindowingMode", new Class<?>[]{int.class}, 5);
                        if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("系统未进入 freeform");
                        a.call(s.task, "setAlwaysOnTop", new Class<?>[]{boolean.class}, true);
                        refresh(s);
                        // Some launchers cache fullscreen top insets when they attach.
                        // A native relaunch rebuilds that cache under the actual freeform
                        // configuration without forging fullscreen or cropping app pixels.
                        refreshEntryLayout(s);
                        a.call(s.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2");
                        a.call(root,"ensureActivitiesVisible");
                        a.call(root,"resumeFocusedTasksTopActivities");
                    } finally { a.call(atm,"continueWindowLayout"); }
                }
                history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();
                notifyRecents();
                note("ACTIVE：小窗已开启，无定时退出");
                if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), 250);
            } catch (Throwable e) {
                if (!sessions.contains(s)) { lease.unlinkToDeath(s.death, 0); }
                recover("启动未完成：" + FrameworkAccess.reason(e));
                throw e;
            }
        } catch (Throwable e) { note("BLOCKED：" + FrameworkAccess.reason(e) + "\n" + capabilities()); }
    }

    private void open(Intent request,int attempt) {
        String pkg=request.getStringExtra("package");
        final long generation=request.getLongExtra("generation",-1);
        try {
            if(generation!=openGeneration){clearShortcutLaunch(generation);return;}
            long began=request.getLongExtra("began",0);
            if(began==0){began=SystemClock.elapsedRealtime();request.putExtra("began",began);}
            if(SystemClock.elapsedRealtime()-began>12000)throw new IllegalStateException("等待应用/转场超时，phase="+request.getStringExtra("phase"));
            if(!Wire.allowedPackage(pkg)||Wire.PKG.equals(pkg))throw new IllegalArgumentException("请选择可启动应用");
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("请先解锁");
            if(recovering||active!=null&&active.restoring){
                recoveryWaits=0;recover("重新检查待恢复任务");
                if(recovering||!sessions.isEmpty())throw new IllegalStateException("旧任务仍待恢复："+last);
            }
            boolean shortcut=request.hasExtra("shortcutLaunch");
            android.app.PendingIntent shortcutLaunch=request.getParcelableExtra("shortcutLaunch",android.app.PendingIntent.class);
            if(shortcut&&(shortcutLaunch==null||!shortcutLaunch.isActivity()||!android.os.Process.myUserHandle().equals(request.getParcelableExtra("shortcutUser",UserHandle.class))))
                throw new IllegalArgumentException("shortcut identity");
            final int shortcutUser=shortcut?(Integer)a.call(request.getParcelableExtra("shortcutUser",UserHandle.class),"getIdentifier"):-1;
            if(active!=null){
                if(!shortcut){
                // Sessions retain creation order: reopen the app's main task before its children.
                for(Session retained:sessions)if(!retained.restoring&&pkg.equals(retained.pkg)){
                    synchronized(lock){
                        if(!live(retained))continue;
                        if(retained==active)break;
                        activate(retained,active.placement);
                        a.call(retained.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-reopen");
                        a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                        focusExpanded(retained);renderFrame(retained);
                    }
                    report("REOPEN");return;
                }
                if(pkg.equals(active.pkg)&&!active.restoring&&!active.closing){
                    synchronized(lock){
                        a.call(active.task,"setFocusable",new Class<?>[]{boolean.class},true);
                        a.call(active.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2-reopen");
                        a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                    }
                    state(active,"normal");return;
                }
                }
                openPhase(request,"wait-close",-1);if(!active.closing&&!active.restoring)close(active);
                handler.postDelayed(()->open(request,attempt+1),40);return;
            }
            if(journal.getBaseFile().exists())throw new IllegalStateException("请先完成待恢复记录");
            final Object[] candidate={null};final long[] newest={Long.MIN_VALUE};
            if(shortcut){
                if(request.getBooleanExtra("launched",false)){
                    ShortcutLaunch ticket=pendingShortcutLaunch.get();
                    if(ticket==null||ticket.generation!=generation||!pkg.equals(ticket.pkg)||shortcutUser!=ticket.userId)
                        throw new IllegalStateException("快捷功能启动请求已失效");
                    if(ticket.dispatched&&ticket.taskId>=0)synchronized(lock){
                        Object task=find(ticket.taskId);
                        ComponentName component=task==null?null:(ComponentName)a.field(task,"realActivity");
                        if(task==null||task!=ticket.task||(Integer)a.field(task,"mUserId")!=ticket.userId
                            ||component==null||!pkg.equals(component.getPackageName())
                            ||!a.bool(task,"isRootTask")||!a.bool(task,"isLeafTask"))
                            throw new IllegalStateException("快捷功能目标任务已改变");
                        candidate[0]=task;
                    }
                }
            }else synchronized(lock){a.call(root,"forAllTasks",new Class<?>[]{Consumer.class},(Consumer<Object>)task->{
                try{ComponentName component=(ComponentName)a.field(task,"realActivity");
                    if(component!=null&&pkg.equals(component.getPackageName())&&(Integer)a.field(task,"mUserId")==0
                        &&a.bool(task,"isRootTask")&&a.bool(task,"isLeafTask")){
                        long moved=(Long)a.field(task,"mLastTimeMoved");if(moved>newest[0]){newest[0]=moved;candidate[0]=task;}
                    }
                }catch(Exception e){throw new IllegalStateException(e);}
            });}
            int id=candidate[0]==null?-1:(Integer)a.field(candidate[0],"mTaskId");
            if(!request.getBooleanExtra("launched",false)){
                ActivityOptions options=ActivityOptions.makeBasic();a.call(options,"setAvoidMoveToFront");
                request.putExtra("launched",true);openPhase(request,"launch",id);trace.arm(pkg,openGeneration);
                if(shortcut){
                    // The system-issued shortcut PendingIntent preserves its real intent stack.
                    // Do not replace it with the launcher activity or force it into the old task.
                    ShortcutLaunch ticket=new ShortcutLaunch(generation,pkg,shortcutUser);
                    pendingShortcutLaunch.set(ticket);
                    shortcutLaunch.send(context,0,null,(pi,intent,code,data,extras)->{
                        synchronized(ticket){
                            if(pendingShortcutLaunch.get()==ticket&&ticket.generation==openGeneration)ticket.dispatched=true;
                        }
                    },handler,null,options.toBundle());
                }else{
                    Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if(launch==null)throw new IllegalStateException("没有启动入口");
                    if(id>=0)a.call(options,"setLaunchTaskId",new Class<?>[]{int.class},id);
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle());
                }
                handler.postDelayed(()->open(request,attempt+1),32);return;
            }
            if(shortcut){
                ShortcutLaunch ticket=pendingShortcutLaunch.get();
                if(ticket==null||ticket.generation!=generation)throw new IllegalStateException("快捷功能启动请求已失效");
                if(!ticket.dispatched){openPhase(request,"wait-shortcut",-1);handler.postDelayed(()->open(request,attempt+1),32);return;}
            }
            if(candidate[0]==null){openPhase(request,"wait-task",-1);handler.postDelayed(()->open(request,attempt+1),32);return;}
            synchronized(lock){if(a.animating(candidate[0])||a.integer(candidate[0],"getRelativeDisplayRotation")!=0){
                openPhase(request,"wait-native",id);handler.postDelayed(()->open(request,attempt+1),32);return;}
            }
            clearShortcutLaunch(generation);
            openPhase(request,"start",id);command(new Intent(request).putExtra("op","START").putExtra("taskId",id).putExtra("fadeEntry",true));
        }catch(Throwable e){clearShortcutLaunch(generation);note("OPEN_FAILED package="+pkg+"："+FrameworkAccess.reason(e));}
    }
    private void clearShortcutLaunch(long generation){
        ShortcutLaunch ticket=pendingShortcutLaunch.get();
        if(ticket!=null&&ticket.generation==generation)pendingShortcutLaunch.compareAndSet(ticket,null);
    }
    Object shortcutLaunchTicket(){
        ShortcutLaunch ticket=pendingShortcutLaunch.get();
        if(ticket!=null&&ticket.generation!=openGeneration){pendingShortcutLaunch.compareAndSet(ticket,null);return null;}
        return ticket;
    }
    /** The existing start-result hook calls this only after a successful native start.
     * Keep the last matching activity in a shortcut's real intent stack, including
     * singleTask/onNewIntent reuse; never guess from the package's recent tasks. */
    void shortcutActivityStarted(Object value,Object started){
        if(!(value instanceof ShortcutLaunch ticket)||started==null||lock==null)return;
        try{synchronized(lock){
            if(pendingShortcutLaunch.get()!=ticket||ticket.generation!=openGeneration)return;
            Object task=a.call(started,"getTask");
            if(task==null||!ticket.pkg.equals(a.field(started,"packageName"))
                ||(Integer)a.field(task,"mUserId")!=ticket.userId)return;
            ComponentName component=(ComponentName)a.field(task,"realActivity");
            if(component==null||!ticket.pkg.equals(component.getPackageName()))return;
            synchronized(ticket){
                if(pendingShortcutLaunch.get()!=ticket||ticket.generation!=openGeneration||ticket.dispatched)return;
                ticket.task=task;ticket.taskId=(Integer)a.field(task,"mTaskId");
            }
            log.accept("SHORTCUT_TASK_BOUND generation="+ticket.generation+" package="+ticket.pkg+" task="+ticket.taskId);
        }}catch(Exception e){log.accept("SHORTCUT_TASK_BIND_FAILED "+FrameworkAccess.reason(e));}
    }
    private void openPhase(Intent request,String phase,int id){
        if(phase.equals(request.getStringExtra("phase")))return;request.putExtra("phase",phase);
        log.accept("OPEN_PHASE package="+request.getStringExtra("package")+" phase="+phase+" task="+id);
    }

    private void refreshEntryLayout(Session s){
        try{
            Object top=a.call(s.task,"topRunningActivity"),rootActivity=a.call(s.task,"getRootActivity");
            if(top==null||top!=rootActivity)return;
            // A new client already receives our freeform launch configuration.
            // Relaunch only an existing drawn window that could cache old insets;
            // do not enqueue a second creation while the first one is in flight.
            Object window=a.call(top,"findMainWindow",new Class<?>[]{boolean.class},false);
            if(window==null||!a.bool(window,"isDrawn")){
                log.accept("ENTRY_LAYOUT_REFRESH_SKIPPED task="+s.taskId+" reason=first-window-not-drawn");return;
            }
            Intent intent=(Intent)a.field(top,"intent");
            boolean launcher=intent!=null&&Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER);
            String lifecycle=String.valueOf(a.call(top,"getState"));
            Object display=a.call(s.task,"getDisplayContent"),ime=a.call(display,"getImeWindow");
            boolean editing=ime!=null&&a.bool(ime,"isVisible");
            if(!WindowPolicy.refreshRootLayout(launcher,a.bool(top,"attachedToProcess"),a.bool(top,"isRelaunching"),
                (Boolean)a.field(top,"finishing"),editing,lifecycle)||a.field(top,"resultTo")!=null)return;
            // These are pending callbacks, not persistent app data. Let Android deliver
            // them normally before a future explicit open, rather than adding a relaunch.
            if(nonEmpty(a.field(top,"results"))||nonEmpty(a.field(top,"newIntents")))return;
            trace.state("RELAUNCH_BEFORE",top);
            a.call(top,"relaunchActivityLocked",new Class<?>[]{boolean.class,int.class},false,0);
            trace.state("RELAUNCH_RETURNED",top);
            log.accept("ENTRY_LAYOUT_RELAUNCH task="+s.taskId+" mode=freeform reason=initial-top-insets");
        }catch(Exception e){log.accept("ENTRY_LAYOUT_REFRESH_SKIPPED "+FrameworkAccess.reason(e));}
    }
    private static boolean nonEmpty(Object value){return value instanceof java.util.Collection<?> c&&!c.isEmpty();}

    /** Runs before handleStartResult requests Shell playback. Convert the related
     * task now; never restore the source to fullscreen to acquire its child. */
    Object activityStarting(Object source,Object requestActivity,Object started,int flags,int realCallingUid){
        if(requestActivity==null||started==null||handler==null||lock==null||(flags&Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0)return null;
        Session created=null;
        try{synchronized(lock){
            Session s=active;
            if(s==null||recovering||s.restoring||s.closing||s.fullTransition||s.screenSuspended
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                ||pendingStart!=null||!"normal".equals(s.frame))return null;
            Object sourceTask=source==null?null:a.call(source,"getTask"),target=a.call(started,"getTask");
            if(target==null||target==s.task)return null;
            if(source!=null){if(sourceTask!=s.task)return null;}
            else{
                Object caller=a.call(s.task,"topRunningActivity");
                if(caller==null||!a.bool(s.task,"isVisibleRequested"))return null;
                if(!WindowPolicy.sameWindowCaller((Integer)a.field(requestActivity,"launchedFromUid"),realCallingUid,
                    a.integer(caller,"getUid"),(String)a.field(requestActivity,"launchedFromPackage"),(String)a.field(caller,"packageName")))return null;
            }
            if((Integer)a.field(target,"mUserId")!=s.userId||a.call(target,"getDisplayContent")!=a.call(s.task,"getDisplayContent"))return null;
            ComponentName component=(ComponentName)a.field(target,"realActivity");
            if(component==null||!Wire.allowedPackage(component.getPackageName())||Wire.PKG.equals(component.getPackageName()))return null;
            Session next=sessionFor(target);
            boolean fresh=next==null;
            if(fresh){
                if(sessions.size()>=MAX_LINKED_TASKS)throw new IllegalStateException("关联小窗数量已达上限");
                next=snapshot((Integer)a.field(target,"mTaskId"),component.getPackageName(),s.token,true);
                next.returnTo=s;next.lease=s.lease;
                final Session child=next;
                next.death=()->handler.post(()->{if(sessions.contains(child))recover("控制进程退出");});
                next.lease.linkToDeath(next.death,0);
                try{save(next);}catch(Exception e){next.lease.unlinkToDeath(next.death,0);throw e;}
                created=next;
            }else if(next.restoring)return null;
            SwitchStart ticket=new SwitchStart(s,next,fresh,openGeneration);
            pendingStart=ticket;
            next.startMask=new TaskVisibilityGuard(a,target);next.startMask.hide();
            activate(next,s.placement);
            a.call(a.field(target,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},target);
            handler.postDelayed(()->{
                if(pendingStart==ticket){cancelStart("start-timeout");recover("关联任务启动超时");}
            },10000);
            log.accept("LINKED_START from="+s.taskId+" to="+next.taskId+" retained="+sessions.size()+" created="+fresh);
            return ticket;
        }}catch(Exception e){
            SwitchStart failed=pendingStart;cancelStart("start-failed");
            if(created!=null||failed!=null)handler.post(()->recover("关联任务接管失败"));
            log.accept("LINKED_START_FAILED "+FrameworkAccess.reason(e));return null;
        }
    }
    void activityStartResult(Object value,boolean success){
        if(!(value instanceof SwitchStart ticket))return;
        handler.post(()->{
            try{synchronized(lock){
                if(pendingStart!=ticket)return;
                pendingStart=null;
                Session next=ticket.target;
                if(!success||ticket.generation!=openGeneration||!next.lease.isBinderAlive()){
                    activate(ticket.from,next.placement);
                    if(ticket.created)retire(next);
                    return;
                }
                if(next.startMask!=null)next.startMask.transfer();
                a.call(next.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-linked");
                a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                focusExpanded(next);renderFrame(next);
                if(ticket.created){
                    history.windowed(new WindowHistory.Entry(next.taskId,next.userId,next.pkg,next.component,next.baseId));
                    saveHistory();notifyRecents();handler.postDelayed(()->tick(next),250);
                }
                report("LINKED_ACTIVE");
            }}catch(Throwable e){recover("关联小窗切换失败："+FrameworkAccess.reason(e));}
        });
    }
    private void cancelStart(String reason){
        SwitchStart ticket=pendingStart;pendingStart=null;
        if(ticket==null)return;
        try{synchronized(lock){
            if(ticket.target.startMask!=null)ticket.target.startMask.transfer();
            if(active==ticket.target)renderFrame(ticket.target);
        }}catch(Exception e){log.accept("LINKED_CANCEL_FAILED "+FrameworkAccess.reason(e));}
        log.accept("LINKED_CANCEL "+reason);
    }
    private Session sessionFor(Object task){
        if(task==null)return null;
        for(Session s:sessions)if(s.task==task)return s;
        return null;
    }
    private boolean live(Session s)throws Exception{
        return s!=null&&!s.restoring&&find(s.taskId)==s.task&&a.call(s.task,"topRunningActivity")!=null;
    }
    private Session returnTarget(Session from)throws Exception{
        if(from.returnTo==null)return null;
        java.util.Set<Session> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Session s=from.returnTo;s!=null&&seen.add(s);s=s.returnTo)if(live(s))return s;
        for(Session s:sessions)if(s!=from&&live(s))return s;
        return null;
    }
    private void activate(Session next,Geometry.Placement placement)throws Exception{
        if(recovering||next.restoring||!sessions.contains(next))throw new IllegalStateException("关联任务已退出");
        if(context.getSystemService(KeyguardManager.class).isKeyguardLocked()
            ||!context.getSystemService(PowerManager.class).isInteractive())throw new IllegalStateException("解锁后再切换小窗");
        next.screenSuspended=false;
        Session previous=active;
        if(previous!=null&&previous!=next){
            previous.animationRev++;previous.transitioning=false;previous.entering=false;
            previous.fullTransition=false;previous.parked=true;previous.opacity=0;
            a.call(previous.task,"setFocusable",new Class<?>[]{boolean.class},false);
            if(previous.decoration!=null)previous.decoration.hide(previous.task);
            a.opacity(previous.task,0);
        }
        next.parked=false;next.closing=false;next.returning=false;next.backRequested=false;next.backComplete=false;
        next.frame="normal";next.animationRev++;next.entering=true;next.entryStarted=false;
        next.transitioning=true;next.fullTransition=false;next.presented=false;next.opacity=0;next.barAlpha=0;
        active=next;
        a.call(atm,"deferWindowLayout");
        try{
            prepareGeometry(next);
            if(next.decoration==null)next.decoration=new TaskDecoration(a);
            a.opacity(next.task,0);
            a.call(next.task,"setFocusable",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setWindowingMode",new Class<?>[]{int.class},5);
            a.call(next.task,"setAlwaysOnTop",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setBounds",new Class<?>[]{Rect.class},new Rect(0,0,next.placement.width(),next.placement.height()));
            if(a.integer(next.task,"getWindowingMode")!=5)throw new IllegalStateException("关联任务未进入 freeform");
            a.call(wms,"requestTraversal");
        }finally{a.call(atm,"continueWindowLayout");}
    }
    private boolean returnFromTask(Session s){return returnFromTask(s,false);}
    private boolean returnFromTask(Session s,boolean beforePlayback){
        if(active==s&&s.returning)return true;
        if(active!=s||recovering||s.restoring||s.closing||pendingStart!=null)return false;
        try{synchronized(lock){
            Session target=returnTarget(s);if(target==null)return false;
            Geometry.Placement placement=s.placement;
            Runnable select=()->{
                try{synchronized(lock){
                    if(active!=s||recovering)return;
                    if(!live(target))throw new IllegalStateException("主应用任务已结束");
                    boolean ended=!live(s);
                    s.returning=false;s.closing=false;s.fullTransition=false;
                    // A live mini-program remains parked in freeform. Its own close
                    // button/activity finish, or closing the whole group, owns cleanup.
                    activate(target,placement);
                    a.call(target.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-return");
                    a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                    focusExpanded(target);renderFrame(target);
                    if(ended)retire(s);
                    log.accept("LINKED_RETURN from="+s.taskId+" to="+target.taskId+" retained="+!ended);
                    report("LINKED_RETURN");
                }}catch(Throwable e){recover("关联小窗返回失败："+FrameworkAccess.reason(e));}
            };
            s.returning=true;
            if(beforePlayback||!live(s)){
                // The app's own close already removed the content; select its parent
                // before Shell builds the closing transaction instead of fading a dead surface.
                s.parked=true;s.opacity=0;if(s.decoration!=null)s.decoration.hide(s.task);
                a.opacity(s.task,0);handler.post(select);
            }else{
                // Reuse the established close/open visuals, without restoring fullscreen.
                s.closing=true;s.fullTransition=true;s.entering=false;
                visibility(s,false,select);
            }
            return true;
        }}catch(Exception e){log.accept("LINKED_RETURN_FAILED "+FrameworkAccess.reason(e));return false;}
    }
    private void retire(Session s){
        if(s==active||s.restoring)return;
        s.restoring=true;s.parked=true;s.animationRev++;
        restoreRetired(s,0);
    }
    private void restoreRetired(Session s,int attempt){
        if(recovering||!sessions.contains(s))return;
        try{synchronized(lock){
            if(restoreTask(s,false)){forget(s);report("LINKED_REMOVED");return;}
        }
            if(attempt>=100){recover("关联任务恢复超时");return;}
            handler.postDelayed(()->restoreRetired(s,attempt+1),100);
        }catch(Throwable e){recover("关联任务恢复失败："+FrameworkAccess.reason(e));}
    }

    private void control(Intent i) throws Exception {
        Session s=active;
        if(s==null||s.restoring||!s.token.equals(i.getStringExtra("token")))throw new IllegalStateException("小窗已结束");
        String op=i.getStringExtra("op");
        if("CLOSE".equals(op)||"FULL".equals(op)){
            openGeneration++;cancelStart(op);trace.stop();
        }
        if(s.closing)return;
        if(s.fullTransition&&!"CLOSE".equals(op))return;
        if("MUTE".equals(op)){
            if(!"mini".equals(s.frame)||s.transitioning||s.entering)return;
            if(audio==null||!audio.setMuted(s.uid,!audio.isMuted(s.uid)))note("MUTE_FAILED：应用静音暂不可用");
            else report("MUTE");
            return;
        }
        if("RESTORE_MINI".equals(op)){
            if("mini".equals(s.frame)&&s.edgeHidden&&!s.transitioning)restoreMini(s);
            return;
        }
        if("MINI_SIZE".equals(op)){
            if(!"mini".equals(s.frame)||s.edgeHidden||s.transitioning||s.entering)return;
            resizeMini(s);return;
        }
        if(op!=null&&op.startsWith("EDGE_")){edgeControl(s,i,op);return;}
        if("MINI_DRAG".equals(op)||"MINI_DRAG_CANCEL".equals(op)){
            if(!"mini".equals(s.frame)||s.edgeHidden||s.entering)return;
            synchronized(lock){
                if("MINI_DRAG_CANCEL".equals(op))endMiniDrag(s,true);
                else{
                    Geometry.Placement p=s.placement;if(p==null)return;
                    if(s.miniDragBase==null){s.miniDragBase=p;s.miniDragOpacity=s.opacity;}
                    Geometry.Placement base=s.miniDragBase;
                    float progress=i.getFloatExtra("scale",0),cx=i.getFloatExtra("x",p.x()+p.width()*p.scale()/2),cy=i.getFloatExtra("y",p.y()+p.height()*p.scale()/2);
                    if(!Float.isFinite(progress)||!Float.isFinite(cx)||!Float.isFinite(cy))return;
                    float scale=base.scale()*MiniMagnet.scaleFactor(progress);
                    s.placement=new Geometry.Placement(base.width(),base.height(),scale,cx-base.width()*scale/2,cy-base.height()*scale/2);
                    s.opacity=s.miniDragOpacity*MiniMagnet.alphaFactor(progress);
                }
                renderFrame(s);
            }
            frameReport(op);return;
        }
        s.resizing="RESIZE".equals(op);
        s.animationRev++;s.transitioning=false;s.radiusFactor=1;
        if("CLOSE".equals(op)){s.miniDragBase=null;close(s);return;}
        if("DOCK".equals(op))endMiniDrag(s,false);
        if("FULL".equals(op)){fullscreen(s);return;}
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
                if(!"mini".equals(s.frame)||s.edgeHidden)return;
                float x=i.getFloatExtra("x",p.x()),y=i.getFloatExtra("y",p.y());
                if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("position");
                Rect safe=s.safeBounds==null?safeArea(a.call(s.task,"getDisplayContent")):new Rect(s.safeBounds);
                float margin=s.placementMargin;
                if("DOCK".equals(op)){
                    int side=Geometry.hideSide(p,x,s.displayW);
                    if(side!=0){hideMini(s,p,side,y,safe,margin);return;}
                    x=x+p.width()*p.scale()/2<safe.centerX()?safe.left+margin:safe.right-margin-p.width()*p.scale();
                }
                x=Math.max(safe.left,Math.min(x,safe.right-p.width()*p.scale()));
                y=Math.max(safe.top,Math.min(y,safe.bottom-p.height()*p.scale()));
                Geometry.Placement destination=new Geometry.Placement(p.width(),p.height(),p.scale(),x,y);
                if("DOCK".equals(op)){s.miniPlacement=destination;animate(s,p,destination,false,0,0);return;}
                s.placement=destination;
            }
            renderFrame(s);
        }
        if("RESIZE".equals(op)||"MOVE".equals(op))frameReport(op);else report(op);
    }

    private void endMiniDrag(Session s,boolean cancel){
        Geometry.Placement base=s.miniDragBase,current=s.placement;if(base==null||current==null)return;
        float cx=current.x()+current.width()*current.scale()/2,cy=current.y()+current.height()*current.scale()/2;
        s.placement=cancel?base:new Geometry.Placement(base.width(),base.height(),base.scale(),cx-base.width()*base.scale()/2,cy-base.height()*base.scale()/2);
        s.opacity=s.miniDragOpacity;s.miniDragBase=null;
    }

    private void edgeControl(Session s,Intent i,String op)throws Exception{
        if(!"mini".equals(s.frame)||!s.edgeHidden||s.entering||s.transitioning)return;
        synchronized(lock){
            Geometry.Placement p=s.placement;if(p==null||s.miniPlacement==null)return;
            if("EDGE_DRAG_CANCEL".equals(op)){
                if(s.edgeDragBase!=null){s.placement=s.edgeDragBase;s.miniPlacement=s.edgeDragMini;s.edgeSide=s.edgeDragSide;}
                s.edgeDragBase=null;s.edgeBarDragging=false;renderFrame(s);frameReport(op);return;
            }
            if(s.edgeDragBase==null){s.edgeDragBase=p;s.edgeDragMini=s.miniPlacement;s.edgeDragSide=s.edgeSide;}
            float x=i.getFloatExtra("x",p.x()),y=i.getFloatExtra("y",p.y());
            if(!Float.isFinite(x)||!Float.isFinite(y))return;
            Rect safe=s.safeBounds==null?safeArea(a.call(s.task,"getDisplayContent")):new Rect(s.safeBounds);
            float margin=s.placementMargin;
            if("EDGE_BAR_DRAG".equals(op)){
                s.edgeBarDragging=true;s.edgeBarCenterX=x;s.edgeBarCenterY=y;
            }else if("EDGE_BAR_DOCK".equals(op)){
                int side=x<s.displayW/2f?-1:1;
                Geometry.Placement visible=s.miniPlacement;
                float vy=Math.max(safe.top+margin,Math.min(y-visible.height()*visible.scale()/2,safe.bottom-margin-visible.height()*visible.scale()));
                s.miniPlacement=new Geometry.Placement(visible.width(),visible.height(),visible.scale(),side<0?safe.left+margin:safe.right-margin-visible.width()*visible.scale(),vy);
                s.edgeSide=side;s.placement=Geometry.offscreen(s.miniPlacement,side,0,s.displayW,margin);
                s.edgeDragBase=null;s.edgeBarDragging=false;
            }else{
                y=Math.max(safe.top,Math.min(y,safe.bottom-p.height()*p.scale()));
                Geometry.Placement destination=new Geometry.Placement(p.width(),p.height(),p.scale(),x,y);
                if("EDGE_DOCK".equals(op)){
                    s.edgeDragBase=null;s.edgeBarDragging=false;s.animationRev++;
                    int side=Geometry.hideSide(destination,x,s.displayW);
                    if(side!=0){hideMini(s,p,side,y,safe,margin);return;}
                    x=x+p.width()*p.scale()/2<safe.centerX()?safe.left+margin:safe.right-margin-p.width()*p.scale();
                    destination=new Geometry.Placement(p.width(),p.height(),p.scale(),x,y);
                    s.edgeHidden=false;s.miniPlacement=destination;animate(s,p,destination,false,0,0);return;
                }
                s.placement=destination;
            }
            renderFrame(s);frameReport(op);
        }
    }

    record BackRoot(Object activity,Object window,Object display,int uid,int taskId) {}
    boolean backCaller(int uid){return uid==1000||uid>=0&&uid==uiUid;}
    BackRoot linkedBackRoot(){
        Session s=active;
        if(s==null||s.returnTo==null||lock==null||handler==null)return null;
        try{synchronized(lock){
            if(active!=s||recovering||s.restoring||s.closing||s.transitioning||s.fullTransition||s.parked
                ||s.screenSuspended||pendingStart!=null||!"normal".equals(s.frame)||!s.lease.isBinderAlive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                ||!context.getSystemService(PowerManager.class).isInteractive()||returnTarget(s)==null)return null;
            Object top=a.call(s.task,"topRunningActivity"),first=a.call(s.task,"getRootActivity");
            if(top==null||top!=first||(Boolean)a.field(top,"finishing"))return null;
            Object display=a.call(s.task,"getDisplayContent");
            Object focus=display==null?null:a.field(display,"mCurrentFocus");
            if(focus==null||a.call(focus,"getTask")!=s.task||a.field(focus,"mActivityRecord")!=top
                ||a.call(top,"findMainWindow",new Class<?>[]{boolean.class},false)!=focus)return null;
            Object ime=a.call(display,"getImeWindow");if(ime!=null&&a.bool(ime,"isVisible"))return null;
            return new BackRoot(top,focus,display,a.integer(top,"getUid"),s.taskId);
        }}catch(Exception e){log.accept("LINKED_BACK_ROOT_FAILED "+FrameworkAccess.reason(e));return null;}
    }
    boolean sameBackRoot(BackRoot expected){
        BackRoot current=linkedBackRoot();
        return current!=null&&current.activity()==expected.activity()&&current.taskId()==expected.taskId();
    }
    boolean isBackRootToken(BackRoot expected,IBinder token){
        if(lock==null||token==null)return false;
        try{synchronized(lock){return sameBackRoot(expected)
            &&a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token)==expected.activity();}}
        catch(Exception e){return false;}
    }
    boolean returnLinkedBack(BackRoot expected,IBinder token,String source){
        if(lock==null)return false;
        try{synchronized(lock){
            if(!sameBackRoot(expected)||token!=null&&!isBackRootToken(expected,token))return false;
            Session s=active;
            if(!returnFromTask(s))return false;
            log.accept("LINKED_BACK_INTERCEPTED task="+s.taskId+" source="+source+" retained=true");return true;
        }}catch(Exception e){log.accept("LINKED_BACK_INTERCEPT_FAILED "+FrameworkAccess.reason(e));return false;}
    }

    boolean suppressLinkedBackPreview(){
        Session s=active;
        if(s==null||s.returnTo==null||s.restoring||s.closing||s.screenSuspended||lock==null||!"normal".equals(s.frame))return false;
        try{synchronized(lock){
            Object display=a.call(s.task,"getDisplayContent");
            Object focus=display==null?null:a.field(display,"mCurrentFocus");
            boolean suppress=active==s&&focus!=null&&a.call(focus,"getTask")==s.task;
            if(suppress)log.accept("LINKED_BACK_PREVIEW_SUPPRESSED task="+s.taskId);
            return suppress;
        }}catch(Exception e){return false;}
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
        handler.post(()->{if(active==s&&!s.restoring&&!returnFromTask(s))close(s);});return true;
    }
    private void close(Session s){
        if(active!=s||s.restoring||s.closing)return;
        log.accept("CLOSE_REQUEST source=control-or-switch task="+s.taskId);
        s.closing=true;s.fullTransition=true;s.entering=false;
        visibility(s,false);
    }
    private void visibility(Session s,boolean show){visibility(s,show,null);}
    private void visibility(Session s,boolean show,Runnable afterHide){
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
                        if(s.screenSuspended||s.rotation.active()||a.presentationLeashed(s.task)){
                            if(began>=0&&lastFrame>=0)began+=now-lastFrame;
                            lastFrame=now;frames.postFrameCallback(this);return;
                        }
                        if(began<0)began=now;
                        if(lastFrame>=0)maxGap=Math.max(maxGap,now-lastFrame);lastFrame=now;
                        float t=duration==0?1:Math.min(1,(float)(now-began)/duration);
                        float progress=modeCurve.getInterpolation(show?t:1-t);
                        MotionStyle.Visibility sample=MotionStyle.visibility(target,progress);
                        s.placement=show?target:sample.placement();s.opacity=startAlpha*sample.alpha();s.barAlpha=startBar*sample.alpha();
                        renderFrame(s);callbacks++;
                        frameReport(show?"APPEARING":"CLOSING");
                        if(t<1){frames.postFrameCallback(this);return;}
                    }
                    log.accept("VISIBILITY_END show="+show+" callbacks="+callbacks+" maxCallbackGapMs="+(maxGap/1000000f));
                    if(show){s.entering=false;s.transitioning=false;s.placement=target;report("APPEARED");}
                    else if(afterHide!=null)afterHide.run();
                    else recover("小窗退出动画完成");
                }catch(Throwable e){failure(s,e);}
            }
        });
    }
    private void state(Session s,String target) throws Exception {
        if(s.entering)return;
        float startBar="mini".equals(s.frame)?0:s.barAlpha;
        if("mini".equals(s.frame)&&!s.edgeHidden)s.miniPlacement=s.placement;
        s.edgeHidden=false;s.frame=target;s.animationRev++;s.fullTransition=false;s.radiusFactor=1;
        Geometry.Placement start=s.placement;
        if(start==null){refresh(s);report("STATE");return;}
        Rect safe=new Rect();
        synchronized(lock){safe.set(safeArea(a.call(s.task,"getDisplayContent")));}
        float margin=s.placementMargin;
        Geometry.Placement end=normalForIme(s);
        if("mini".equals(target)) {
            end=s.miniPlacement==null
                ?Geometry.miniTopRight(start,s.defaultScale,safe.left,safe.top,safe.right,safe.bottom,margin,s.miniLarge)
                :Geometry.resizeMini(s.miniPlacement,s.defaultScale,safe.left,safe.top,safe.right,safe.bottom,margin,s.miniLarge);
            s.miniPlacement=end;
        }
        // Change focus after the surface morph; avoid starting a competing native transition first.
        animate(s,start,end,false,startBar,"mini".equals(target)?0:1);
    }
    private void hideMini(Session s,Geometry.Placement start,int side,float y,Rect safe,float margin)throws Exception{
        float height=start.height()*start.scale(),width=start.width()*start.scale();
        y=Math.max(safe.top+margin,Math.min(y,safe.bottom-margin-height));
        s.miniPlacement=new Geometry.Placement(start.width(),start.height(),start.scale(),
            side<0?safe.left+margin:safe.right-margin-width,y);
        s.edgeHidden=true;s.edgeSide=side;
        animate(s,start,Geometry.offscreen(s.miniPlacement,side,0,s.displayW,margin),false,0,0);
        log.accept("MINI_EDGE_HIDE task="+s.taskId+" side="+side);
    }
    private void restoreMini(Session s)throws Exception{
        Geometry.Placement end=s.miniPlacement;
        if(end==null)return;
        s.animationRev++;s.edgeHidden=false;
        animate(s,s.placement,end,false,0,0);
        log.accept("MINI_EDGE_RESTORE task="+s.taskId+" side="+s.edgeSide);
    }
    private void resizeMini(Session s)throws Exception{
        synchronized(lock){
            Geometry.Placement start=s.placement;if(start==null)return;
            Rect safe=safeArea(a.call(s.task,"getDisplayContent"));
            float margin=s.placementMargin;
            s.miniLarge=!s.miniLarge;s.animationRev++;s.radiusFactor=1;
            Geometry.Placement end=Geometry.resizeMini(start,s.defaultScale,safe.left,safe.top,safe.right,safe.bottom,margin,s.miniLarge);
            s.miniPlacement=end;animate(s,start,end,false,0,0);
        }
    }
    private void fullscreen(Session s) throws Exception {
        Geometry.Placement start=s.placement;
        Rect bounds;
        synchronized(lock){bounds=new Rect((Rect)a.call(a.call(s.task,"getDisplayContent"),"getBounds"));}
        // Keep the native foreground identity until the morph finishes. The endpoint
        // restores focus/configuration together instead of starting a second transition now.
        // Preserve aspect ratio while expanding. Real fullscreen configuration is restored at
        // the endpoint; Android owns the final app relayout (especially orientation changes).
        float scale=Math.min((float)bounds.width()/start.width(),(float)bounds.height()/start.height());
        Geometry.Placement end=new Geometry.Placement(start.width(),start.height(),scale,
            bounds.centerX()-start.width()*scale/2,bounds.centerY()-start.height()*scale/2);
        beginCover(s,start,new Geometry.Placement(bounds.width(),bounds.height(),1,bounds.left,bounds.top),true);
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
                        if(s.screenSuspended||s.rotation.active()||a.presentationLeashed(s.task)){
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
                                if(modeChange&&!"mini".equals(s.frame))focusExpanded(s);
                                // Home/recents must see the real foreground task. Resume
                                // the visible mini separately without changing task focus.
                                a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
                                a.call(root,"resumeFocusedTasksTopActivities");resumeMini(s);a.call(wms,"requestTraversal");
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

    private Session snapshot(int taskId,String pkg,String token)throws Exception{return snapshot(taskId,pkg,token,false);}
    private Session snapshot(int taskId, String pkg, String token,boolean linked) throws Exception {
        Object task = taskId < 0 ? a.focused.invoke(root) : find(taskId);
        if (task == null) throw new IllegalStateException("目标任务不存在");
        Session s = new Session(); s.task = task; s.taskId = (Integer) a.field(task, "mTaskId");
        s.userId = (Integer) a.field(task, "mUserId"); s.pkg = pkg; s.token = token;
        ComponentName component = (ComponentName) a.field(task, "realActivity");
        if (component == null || !pkg.equals(component.getPackageName())) throw new IllegalStateException("应用身份不匹配");
        s.component = component.flattenToString();
        Object top=a.call(task,"topRunningActivity");
        if(top!=null)s.uid=a.integer(top,"getUid");
        Intent base=(Intent)a.call(task,"getBaseIntent");
        s.baseId=base==null?null:base.getIdentifier();
        if(Wire.PKG.equals(pkg)&&!token.equals(s.baseId)) throw new IllegalStateException("诊断任务标识不匹配");
        Point crop=(Point)a.field(task,"mLastSurfaceSize");s.crop=new Point(crop.x,crop.y);
        if(s.crop.x<0||s.crop.y<0) throw new IllegalStateException("原始裁剪不可用");
        validate(task,linked);
        boolean inherited=linked&&a.integer(task,"getWindowingMode")==5&&!a.bool(task,"isAlwaysOnTop");
        if (!inherited&&(a.integer(task, "getWindowingMode") != 1 || a.bool(task, "isAlwaysOnTop")||!a.bool(task,"isFocusable")))
            throw new IllegalStateException("只接管普通全屏或直接关联的 freeform 任务");
        Object requested = a.windowConfig(task, true);
        s.originalConfig=new Configuration(a.config(task,true));
        s.mode = a.integer(requested, "getWindowingMode");
        s.topState = (Integer) a.field(requested, "mAlwaysOnTop");
        s.bounds = new Rect((Rect) a.call(requested, "getBounds"));
        if(inherited){
            // A new task can inherit freeform from its source before we observe it.
            // Its exit must return to the display's natural fullscreen configuration.
            s.originalConfig=new Configuration();
            Object original=a.field(s.originalConfig,"windowConfiguration");
            a.call(original,"setWindowingMode",new Class<?>[]{int.class},1);
            a.call(original,"setAlwaysOnTop",new Class<?>[]{int.class},2);
            s.mode=1;s.topState=2;s.bounds.setEmpty();
        }
        if ((s.mode != 0 && s.mode != 1) || !s.bounds.isEmpty() || (s.topState != 0 && s.topState != 2))
            throw new IllegalStateException("原请求配置不是标准全屏");
        s.alwaysOnTop = false; s.parent = a.call(task, "getParent");
        s.forceTranslucent=a.bool(task,"isForceTranslucent");s.restoreTranslucent=true;
        Rect display = (Rect) a.call(a.call(task, "getDisplayContent"), "getBounds");
        s.logicalShort = Math.min(display.width(), display.height());
        s.landscape=a.config(task,false).orientation==Configuration.ORIENTATION_LANDSCAPE;
        updateOrientation(s);
        return s;
    }

    private void validate(Object task) throws Exception {validate(task,false);}
    private void validate(Object task,boolean allowTransition) throws Exception {
        if(a.bool(task,"isForceOpaque"))throw new IllegalStateException("系统已强制此任务遮挡底层，不接管");
        if (!(Boolean) a.field(atm, "mSupportsMultiWindow") || !(Boolean) a.field(atm, "mSupportsFreeformWindowManagement"))
            throw new IllegalStateException("ROM 未启用原生 freeform；此版保持预检，不全局强开");
        if (!a.bool(task, "isRootTask") || !a.bool(task, "isLeafTask") || !a.bool(task, "isAttached")
                || !a.tda.isInstance(a.call(task, "getParent"))) throw new IllegalStateException("任务层级不受首版支持");
        Object display = a.call(task, "getDisplayContent");
        if (display == null || (Integer) a.field(display, "mDisplayId") != 0 || (Integer) a.field(task, "mUserId") != 0)
            throw new IllegalStateException("首版只测试主用户、内置屏幕");
        if (a.integer(task, "getActivityType") != 1 || (!allowTransition&&a.animating(task))
                || a.integer(task, "getRelativeDisplayRotation") != 0) throw new IllegalStateException("等待当前任务转场结束后再试");
        Object area = a.call(task, "getDisplayArea");
        Object previous=resizeCandidate.get();resizeCandidate.set(task);
        try{
            if (!(Boolean) a.call(task, "supportsFreeformInDisplayArea", new Class<?>[]{a.tda}, area)
                || !(Boolean) a.call(area, "isValidWindowingMode", new Class<?>[]{int.class, a.activity, a.task}, 5, null, task)
                || !a.bool(task, "isOverrideBoundsAllowed") || !a.bool(task,"isOverrideWindowingModeAllowed"))
                throw new IllegalStateException("当前任务不支持可调整的 freeform");
        }finally{if(previous==null)resizeCandidate.remove();else resizeCandidate.set(previous);}
    }

    private boolean identity(Object task, Session s) throws Exception {
        if (task == null || (Integer) a.field(task, "mTaskId") != s.taskId || (Integer) a.field(task, "mUserId") != s.userId) return false;
        // A live task may change its root component/base intent after a document or payment return.
        // The in-memory task object, id and user are stronger identity than mutable activity metadata.
        if(s.task!=null&&WindowPolicy.sameLiveTask(task,s.task,(Integer)a.field(task,"mTaskId"),s.taskId,(Integer)a.field(task,"mUserId"),s.userId))return true;
        Intent base = (Intent) a.call(task, "getBaseIntent");
        ComponentName real = (ComponentName) a.field(task, "realActivity");
        return base != null && java.util.Objects.equals(s.baseId,base.getIdentifier()) && real != null
            && s.component.equals(real.flattenToString()) && s.pkg.equals(real.getPackageName());
    }

    private boolean updateOrientation(Session s)throws Exception{
        Object activity=a.call(s.task,"topRunningActivity");if(activity==null)return false;
        int request=a.integer(activity,"getRequestedOrientation");
        boolean next=WindowPolicy.landscape(request,s.landscape);
        if(next!=s.landscape){
            log.accept("APP_ORIENTATION task="+s.taskId+" request="+request+" landscape="+next+" activity="+activity);
            s.landscape=next;
        }
        // A native transition can defer refresh; retry until the actual canvas matches.
        return s.placement!=null&&(s.placement.width()>s.placement.height())!=s.landscape;
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
            s.miniPlacement=Geometry.miniTopRight(p,p.scale(),safe.left,safe.top,safe.right,safe.bottom,margin,s.miniLarge);
            s.placement="mini".equals(s.frame)?s.miniPlacement:p;
            s.displayW=displayBounds.width();s.displayH=displayBounds.height();s.displayRotation=a.integer(display,"getRotation");
            s.safeBounds=new Rect(safe);s.placementMargin=margin;
    }
    private Geometry.Placement normalForIme(Session s)throws Exception{
        Geometry.Placement normal=s.normalPlacement;
        if(normal==null||s.displayW>s.displayH)return normal;
        Object display=a.call(s.task,"getDisplayContent");Rect bounds=(Rect)a.call(display,"getBounds");
        Object state=a.call(a.call(display,"getInsetsStateController"),"getRawInsetsState");
        Insets ime=(Insets)a.call(state,"calculateInsets",new Class<?>[]{Rect.class,Rect.class,int.class,boolean.class},bounds,bounds,android.view.WindowInsets.Type.ime(),false);
        return Geometry.avoidIme(normal,s.displayW,s.displayH,ime.bottom);
    }
    private void refresh(Session s) throws Exception {refresh(s,false);}
    private void refresh(Session s,boolean surfaceOnly) throws Exception {
        synchronized (lock) {
            if (active != s || s.restoring) return;
            if (find(s.taskId) != s.task || !identity(s.task, s) || a.call(s.task, "getParent") != s.parent)
                throw new IllegalStateException("任务身份或父层发生变化");
            if (s.refreshingGeometry||s.closing||s.fullTransition||a.presentationLeashed(s.task)) return;
            s.refreshingGeometry=true;
            try{
            if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("任务已退出 freeform");
            ComponentName real=(ComponentName)a.field(s.task,"realActivity");Intent base=(Intent)a.call(s.task,"getBaseIntent");
            if(real!=null&&s.pkg.equals(real.getPackageName())&&base!=null&&(!s.component.equals(real.flattenToString())||!java.util.Objects.equals(s.baseId,base.getIdentifier()))){
                s.component=real.flattenToString();s.baseId=base.getIdentifier();save(s);
                history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();
                log.accept("LIVE_IDENTITY_UPDATED task="+s.taskId);
            }
            Geometry.Placement previous=s.placement,oldMini=s.miniPlacement;
            boolean completingMorph=s.transitioning;
            Rect oldSafe=s.safeBounds==null?null:new Rect(s.safeBounds);
            float oldMargin=s.placementMargin;
            int oldW=s.displayW,oldH=s.displayH,oldRotation=s.displayRotation;
            prepareGeometry(s);
            boolean rotated=oldRotation>=0&&oldRotation!=s.displayRotation;
            if(previous!=null&&(previous.width()!=s.placement.width()||previous.height()!=s.placement.height())&&!rotated&&!s.rotation.active())
                beginCover(s,previous,s.placement,false);
            boolean unchanged=!rotated&&previous!=null&&oldW==s.displayW&&oldH==s.displayH
                &&java.util.Objects.equals(oldSafe,s.safeBounds)
                &&previous.width()==s.placement.width()&&previous.height()==s.placement.height();
            if(unchanged){s.placement=previous;if(oldMini!=null)s.miniPlacement=oldMini;}
            else{
                Rect safe=s.safeBounds;
                if(oldSafe!=null&&previous!=null){
                    Geometry.Placement mini="mini".equals(s.frame)&&!s.edgeHidden?previous:oldMini;
                    if(rotated){
                        s.resizing=false;s.edgeHidden=false;s.edgeSide=0;
                        if(s.miniDragBase!=null)s.opacity=s.miniDragOpacity;
                        s.miniDragBase=null;s.edgeDragBase=null;s.edgeBarDragging=false;
                    }
                    else if(mini!=null)s.miniPlacement=Geometry.remapMini(mini,s.normalPlacement,s.defaultScale,
                        oldSafe.left,oldSafe.top,oldSafe.right,oldSafe.bottom,oldMargin,
                        safe.left,safe.top,safe.right,safe.bottom,s.placementMargin,s.miniLarge);
                    s.placement="mini".equals(s.frame)?s.miniPlacement:s.normalPlacement;
                    if(s.placement==null)s.placement=previous;
                    if(s.edgeHidden)s.placement=Geometry.offscreen(s.miniPlacement,s.edgeSide,0,s.displayW,s.placementMargin);
                }
                s.animationRev++;s.transitioning=false;s.fullTransition=false;s.radiusFactor=1;s.barAlpha="mini".equals(s.frame)?0:1;
                if(s.entering){s.entryStarted=false;s.presented=false;s.transitioning=true;s.opacity=0;s.barAlpha=0;}
            }
            s.geometryDirty=false;
            if(!unchanged&&completingMorph&&!s.entering&&!surfaceOnly){
                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},!"mini".equals(s.frame));
                if(!"mini".equals(s.frame))focusExpanded(s);
                resumeMini(s);
            }
            Geometry.Placement p=s.placement;
            Rect logical = new Rect(0, 0, p.width(), p.height());
            if (!logical.equals(a.call(s.task, "getBounds"))){
                if(surfaceOnly){s.geometryDirty=true;configurationChanged(s.task);return;}
                a.call(s.task, "setBounds", new Class<?>[]{Rect.class}, logical);
            }
            // Check actual post-configuration geometry, not only the requested rectangle.
            Rect actual = (Rect) a.call(s.task, "getBounds");
            if (actual.width() != p.width() || actual.height() != p.height())
                throw new IllegalStateException("逻辑画布被系统重写：" + actual);
            apply(s);
            if(!surfaceOnly){a.call(root,"ensureActivitiesVisible");a.call(wms,"requestTraversal");}
            }finally{s.refreshingGeometry=false;}
        }
    }

    private static RectF visualBounds(Geometry.Placement p){return new RectF(p.x(),p.y(),p.x()+p.width()*p.scale(),p.y()+p.height()*p.scale());}
    private void beginCover(Session s,Geometry.Placement from,Geometry.Placement to,boolean fullscreen){
        if(from==null||to==null)return;
        try{
            s.coverDrawn=false;s.coverFullscreen=fullscreen;
            if(s.cover!=null){s.cover.target(visualBounds(to));return;}
            float density=a.config(s.task,false).densityDpi/160f;
            float radius=a.integer(s.task,"getWindowingMode")==1?0:density*MotionStyle.radius(from.scale(),s.defaultScale);
            TaskCover cover=new TaskCover(a,context,s.task,s.parent,s.pkg,visualBounds(from),visualBounds(to),radius,fullscreen?0:density*MotionStyle.radius(to.scale(),s.defaultScale),coverReleaseAlpha(s),s.entering&&s.entryFade&&!fullscreen);
            s.cover=cover;coverTasks.put(s.task,s);pollCover(s,cover);
        }catch(Throwable error){log.accept("COVER_SKIPPED "+FrameworkAccess.reason(error));endCover(s);}
    }
    private boolean coverConfigurationMatches(Session s,Object window)throws Exception{
        if(a.integer(s.task,"getWindowingMode")!=(s.coverFullscreen?1:5))return false;
        Configuration reported=(Configuration)a.call(a.field(window,"mLastReportedConfiguration"),"getMergedConfiguration");
        Object wc=a.field(reported,"windowConfiguration"),display=a.call(s.task,"getDisplayContent");
        if(display==null||a.integer(wc,"getWindowingMode")!=(s.coverFullscreen?1:5)
            ||a.integer(wc,"getRotation")!=a.integer(display,"getRotation"))return false;
        Rect actual=(Rect)a.call(wc,"getBounds"),expected=s.coverFullscreen?(Rect)a.call(display,"getBounds"):new Rect(0,0,s.placement.width(),s.placement.height());
        return actual.width()==expected.width()&&actual.height()==expected.height();
    }
    void drawingFinished(Object window,int seq){
        if(lock==null)return;
        try{synchronized(lock){
            Object task=a.call(window,"getTask");Session s=coverTasks.get(task);
            if(s==null||s.cover==null||a.call(task,"getTopVisibleAppMainWindow")!=window
                ||seq<(Integer)a.field(window,"mSyncSeqId"))return;
            if(coverConfigurationMatches(s,window))s.coverDrawn=true;
        }}catch(Throwable error){log.accept("COVER_DRAW_CHECK_FAILED "+FrameworkAccess.reason(error));}
    }
    private void pollCover(Session s,TaskCover cover){
        handler.postDelayed(()->{synchronized(lock){
            if(s.cover!=cover)return;
            try{
                cover.ownerAlpha(coverReleaseAlpha(s));
                boolean attached=a.bool(s.task,"isAttached");
                if(!attached||s.parked||s.restoring&&!s.restoreForeground){endCover(s);return;}
                Object main=a.call(s.task,"getTopVisibleAppMainWindow");
                boolean ready=s.coverDrawn&&main!=null&&a.bool(main,"isDrawn")&&a.bool(main,"isVisible")
                    &&coverConfigurationMatches(s,main)&&!a.presentationLeashed(s.task);
                if(s.coverFullscreen){
                    Object display=a.call(s.task,"getDisplayContent");if(display!=null)cover.target(new RectF((Rect)a.call(display,"getBounds")));
                }else ready&=!s.entering;
                boolean visible=context.getSystemService(PowerManager.class).isInteractive()&&!context.getSystemService(KeyguardManager.class).isKeyguardLocked();
                if(!cover.step(ready,visible)){s.cover=null;coverTasks.remove(s.task);if(active==s&&!s.restoring)renderFrame(s);return;}
                pollCover(s,cover);
            }catch(Throwable error){log.accept("COVER_RELEASED "+FrameworkAccess.reason(error));endCover(s);}
        }},16);
    }
    private float coverReleaseAlpha(Session s){
        if(s.parked||s.screenSuspended)return 0;
        if(s.restoring)return s.restoreForeground?1:0;
        if(s.startMask!=null&&s.startMask.holding()||s.rotationMask!=null&&s.rotationMask.holding())return 0;
        return s.opacity*s.rotation.alpha(SystemClock.elapsedRealtime());
    }
    private void endCover(Session s){
        TaskCover cover=s.cover;s.cover=null;coverTasks.remove(s.task);
        if(cover!=null){
            cover.ownerAlpha(coverReleaseAlpha(s));cover.close();
            // Releasing the cover mask restores its surface alpha. Reapply the
            // live owner immediately so parked/background tasks stay hidden.
            try{
                if(s.restoring&&!s.restoreForeground)a.opacity(s.task,0);
                else if(sessions.contains(s)&&!s.restoring)renderFrame(s);
            }catch(Throwable error){log.accept("COVER_OWNER_REAPPLY_FAILED "+FrameworkAccess.reason(error));}
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
            .putExtra("hiddenTaskIds",hiddenIds());
        try{context.sendBroadcast(state,null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
        catch(Exception e){log.accept("HISTORY_PUBLISH_FAILED "+FrameworkAccess.reason(e));}
    }
    private int[] hiddenIds(){
        if(!hideRecents)return new int[0];
        java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();
        synchronized(lock){for(WindowHistory.Entry e:history.entries())try{Object task=find(e.taskId());if(task==null||hideRecent(task))ids.add(e.taskId());}catch(Exception ignored){}}
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
    private void historyTick(){
        boolean changed=false;
        for(WindowHistory.Entry e:history.entries())try{synchronized(lock){
            Object task=find(e.taskId());
            if(task==null){Object recent=a.lookup.invoke(root,e.taskId(),1);if(recent==null){history.removed(e.taskId());changed=true;}continue;}
            if(!matches(task,e)){history.removed(e.taskId());changed=true;continue;}
            boolean ours=owns(task);
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
        try{
            if(!owns(task)&&a.integer(task,"getWindowingMode")==1&&a.focused.invoke(root)==task)return false;
            return matches(task,history.get((Integer)a.field(task,"mTaskId")));
        }catch(Exception e){return false;}
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
                if(owns(child)||!a.task.isInstance(child)||!a.bool(child,"isVisible"))continue;
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
    boolean owns(Object task) { Session s=sessionFor(task);return s!=null&&!s.restoring; }
    boolean allowResize(Object task){return task!=null&&(task==resizeCandidate.get()||owns(task));}
    private void focusExpanded(Session s)throws Exception {
        if(active!=s||s.restoring||s.closing||context.getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        Object top=a.call(s.task,"topRunningActivity");if(top==null)return;
        // Same native focus path used by setFocusedTask, without requesting an
        // extra TO_FRONT transition for an already always-on-top freeform task.
        a.call(top,"moveFocusableActivityToTop",new Class<?>[]{String.class},"NativeComplements-expand");
        Object display=a.call(s.task,"getDisplayContent");
        a.call(display,"setFocusedApp",new Class<?>[]{a.activity},top);
        a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
        log.accept("MINI_EXPAND_FOCUS task="+s.taskId+" window="+a.field(display,"mCurrentFocus"));
    }
    boolean keepVisibleResumed(Object fragment,Object resuming){
        Session s=active;
        if(s==null||s.restoring||s.closing||s.fullTransition||s.parked||s.screenSuspended||lock==null
            ||!("mini".equals(s.frame)||"normal".equals(s.frame)))return false;
        try{synchronized(lock){
            if(active!=s||a.call(fragment,"getTask")!=s.task||!s.lease.isBinderAlive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                ||a.bool(fragment,"shouldSleepOrShutDownActivities"))return false;
            Object top=a.call(fragment,"topRunningActivity");
            // Only retain a live visible top Activity. Native pause still owns
            // sleep, shutdown, occlusion, same-task page changes and teardown.
            return top!=null&&!(Boolean)a.field(top,"finishing")&&a.bool(top,"isVisibleRequested")
                &&a.bool(top,"attachedToProcess")
                &&(Integer)a.call(fragment,"getVisibility",new Class<?>[]{a.activity},resuming)==0;
        }}catch(Exception e){return false;}
    }
    boolean retainMiniActivity(Object activity,Object resuming){
        Session s=active;
        if(s==null||!"mini".equals(s.frame))return false;
        try{
            Object fragment=a.call(activity,"getTaskFragment");
            return keepVisibleResumed(fragment,resuming)&&active==s
                &&a.call(fragment,"topRunningActivity")==activity;
        }catch(Exception e){return false;}
    }
    boolean shouldResumeMiniActivity(Object activity,Object resuming){
        if(!retainMiniActivity(activity,resuming))return false;
        try{
            // These are the remaining native shouldBeResumed checks. Do not
            // bypass pause completion, same-Activity guards or process compat.
            return (Boolean)a.call(activity,"shouldMakeActive",new Class<?>[]{a.activity},resuming)
                &&a.bool(activity,"canResumeByCompat");
        }catch(Exception e){return false;}
    }
    // Native Task resume is gated on focusability, also used to choose Home and
    // recents' running task. Keep that gate intact. A visible mini can use the
    // same native fragment lifecycle path without becoming the focused task.
    private void resumeMini(Session s){
        if(!visibleResumeReady||active!=s||!"mini".equals(s.frame)||s.transitioning
            ||s.backRequested||pendingStart!=null)return;
        try{
            Object top=a.call(s.task,"topRunningActivity");if(top==null)return;
            String state=String.valueOf(a.call(top,"getState"));
            if(!("PAUSED".equals(state)||"STARTED".equals(state)||"STOPPED".equals(state)||"STOPPING".equals(state)))return;
            Object fragment=a.call(top,"getTaskFragment");
            if(!shouldResumeMiniActivity(top,null))return;
            a.call(fragment,"resumeTopActivity",new Class<?>[]{a.activity,ActivityOptions.class,boolean.class},null,null,false);
        }catch(Exception e){
            // This optional continuation must not trigger fullscreen recovery.
            visibleResumeReady=false;log.accept("MINI_RESUME_UNAVAILABLE "+FrameworkAccess.reason(e));
        }
    }
    boolean ownsActivityToken(IBinder token)throws Exception{
        if(active==null||lock==null)return false;
        synchronized(lock){Object record=a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token);
            return record!=null&&owns(a.call(record,"getTask"));}
    }
    boolean ownsClientWindow(Object client)throws Exception{
        if(sessions.isEmpty()||lock==null||!(client instanceof IInterface))return false;
        IBinder token=((IInterface)client).asBinder();boolean[] found={false};
        synchronized(lock){for(Session s:sessions)if(!s.restoring){
            a.call(s.task,"forAllWindows",new Class<?>[]{Consumer.class,boolean.class},(Consumer<Object>)window->{
                try{if(((IInterface)a.field(window,"mClient")).asBinder().equals(token))found[0]=true;}
                catch(Exception e){throw new IllegalStateException(e);}
            },false);
        }return found[0];}
    }
    // Both WindowState and ActivityRecord input sinks obey the same task state.
    boolean blockContentTouch(Object container){
        try{Session s=sessionFor(a.call(container,"getTask"));
            return s!=null&&(s.parked||s.restoring||s.screenSuspended||s.rotation.active()||s.closing||"mini".equals(s.frame)
                ||s.startMask!=null&&s.startMask.holding());
        }catch(Exception e){return false;}
    }
    private boolean suppressTransition(Session s){
        return s.restoring?!s.restoreForeground||s.fullTransition:!s.fullTransition||s.closing;
    }
    // Before native display collection: hide before it can capture/reparent the old geometry.
    void displayTransitionCollecting(Object transition,Object container){
        if(lock==null||handler==null||!a.dc.isInstance(container))return;
        try{synchronized(lock){
            Session s=active;
            if(s==null||s.restoring||s.parked||s.closing||s.fullTransition
                ||a.call(s.task,"getDisplayContent")!=container)return;
            if(!(Boolean)a.call(transition,"isCollecting")||!s.rotationTransitions.add(transition))return;
            beginRotation(s);
            try{a.call(transition,"addTransitionEndedListener",new Class<?>[]{Runnable.class},(Runnable)()->handler.post(()->{
                synchronized(lock){s.rotationTransitions.remove(transition);queueRotationProbe(s,0);}
            }));}catch(Exception e){s.rotationTransitions.remove(transition);log.accept("ROTATION_END_LISTENER_UNAVAILABLE "+FrameworkAccess.reason(e));}
        }}catch(Throwable e){log.accept("ROTATION_HIDE_FAILED "+FrameworkAccess.reason(e));}
    }
    // Also covers display reconfiguration outside a Shell transition. Signature is optional/preflighted.
    void displayConfigurationChanging(Object display,Configuration next){
        if(lock==null||handler==null||next==null)return;
        try{synchronized(lock){
            Session s=active;
            if(s==null||s.restoring||s.parked||s.closing||s.fullTransition
                ||a.call(s.task,"getDisplayContent")!=display)return;
            Object old=a.windowConfig(display,true),changed=a.field(next,"windowConfiguration");
            int from=a.integer(old,"getRotation"),to=a.integer(changed,"getRotation");
            Rect before=(Rect)a.call(old,"getBounds"),after=(Rect)a.call(changed,"getBounds");
            if(from!=to&&from>=0&&to>=0||!before.equals(after)&&!after.isEmpty())beginRotation(s);
        }}catch(Throwable e){log.accept("ROTATION_CONFIG_GUARD_FAILED "+FrameworkAccess.reason(e));}
    }
    private void beginRotation(Session s){
        try{
            if(s.rotationMask==null||!s.rotationMask.holding()){
                TaskVisibilityGuard previous=s.rotationMask,next=new TaskVisibilityGuard(a,s.task);
                next.hide();s.rotationMask=next;
                if(previous!=null)previous.continueWith(next);
            }else s.rotationMask.hide();
            s.rotation.begin(SystemClock.elapsedRealtime());
            s.geometryDirty=true;
            if(s.decoration!=null)s.decoration.hide(s.task);
            frameReport("ROTATION_HIDDEN");queueRotationProbe(s,32);
            log.accept("ROTATION_HIDDEN task="+s.taskId+" frame="+s.frame);
        }catch(Throwable e){rotationFailure(s,e);}
    }
    private void queueRotationProbe(Session s,long delay){
        if(s.rotationProbeQueued||!s.rotation.active()||s.rotation.fading())return;
        s.rotationProbeQueued=true;
        handler.postDelayed(()->{synchronized(lock){s.rotationProbeQueued=false;probeRotation(s);}},delay);
    }
    private float presentationAlpha(Session s){
        return s.parked||s.restoring||s.screenSuspended||s.startMask!=null&&s.startMask.holding()
            ||s.cover!=null&&s.cover.mask.holding()?0:s.opacity*s.rotation.alpha(SystemClock.elapsedRealtime());
    }
    private TaskVisibilityGuard presentationMask(Session s){
        if(s.rotationMask!=null&&s.rotationMask.holding())return s.rotationMask;
        if(s.startMask!=null&&s.startMask.holding())return s.startMask;
        return s.cover!=null&&s.cover.mask.holding()?s.cover.mask:s.startMask;
    }
    private void probeRotation(Session s){
        if(!s.rotation.active()||s.rotation.fading())return;
        try{
            if(!sessions.contains(s)||s.restoring){s.rotation.clear();return;}
            if(find(s.taskId)!=s.task||!a.bool(s.task,"isAttached")){
                endRotation(s,false);return;
            }
            long now=SystemClock.elapsedRealtime();
            if(s.screenSuspended||!context.getSystemService(PowerManager.class).isInteractive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()){
                s.rotation.defer(now);queueRotationProbe(s,250);return;
            }
            boolean overlap=s.startMask!=null&&s.startMask.holding();
            Object display=a.call(s.task,"getDisplayContent");
            if(display==null){endRotation(s,false);return;}
            Object controller=a.field(s.task,"mTransitionController");
            boolean nativeBusy=a.presentationLeashed(s.task)
                ||(Boolean)a.call(controller,"inTransition",new Class<?>[]{a.wc},display);
            boolean changing=a.bool(display,"isRotationChanging");
            Rect bounds=new Rect((Rect)a.call(display,"getBounds")),safe=safeArea(display);
            int rotation=a.integer(display,"getRotation");
            boolean ready=!overlap&&!nativeBusy&&!changing&&s.rotationTransitions.isEmpty();
            // A missing/aborted callback must never leave a live task transparent forever.
            if(s.rotation.expired(now)&&!overlap){
                log.accept("ROTATION_TIMEOUT_VISIBLE task="+s.taskId);
                endRotation(s,true);return;
            }
            String sample=rotation+"/"+bounds+"/"+safe;
            if(!s.rotation.sample(now,ready,sample)){queueRotationProbe(s,32);return;}
            if(active==s){
                refresh(s);
                Geometry.Placement p=s.placement;
                if(s.geometryDirty||p==null||!new Rect(0,0,p.width(),p.height()).equals(a.call(s.task,"getBounds"))){
                    queueRotationProbe(s,32);return;
                }
            }
            // Restore wrapper alphas with the task still at zero. The first visible frame
            // uses the refreshed geometry; its native logical canvas never flashes onscreen.
            if(s.rotationMask!=null)s.rotationMask.transfer();
            int generation=s.rotation.startFade(SystemClock.elapsedRealtime());
            renderFrame(s);frameReport("ROTATION_APPEARING");
            frames.postFrameCallback(new Choreographer.FrameCallback(){
                public void doFrame(long time){
                    synchronized(lock){
                        if(!s.rotation.active()||s.rotation.generation()!=generation)return;
                        try{
                            if(!sessions.contains(s)||s.restoring){s.rotation.clear();return;}
                            long clock=SystemClock.elapsedRealtime();
                            if(s.screenSuspended||a.presentationLeashed(s.task)){
                                beginRotation(s);return;
                            }
                            renderFrame(s);frameReport("ROTATION_APPEARING");
                            if(s.rotation.alpha(clock)<1){frames.postFrameCallback(this);return;}
                            s.rotation.clear();renderFrame(s);report("ROTATION_VISIBLE");
                        }catch(Throwable e){rotationFailure(s,e);}
                    }
                }
            });
        }catch(Throwable e){rotationFailure(s,e);}
    }
    private void endRotation(Session s,boolean visible){
        s.rotation.clear();s.rotationTransitions.clear();
        if(s.rotationMask!=null){
            try{if(s.rotationMask.holding())s.rotationMask.transfer();
                s.rotationMask.afterTransaction(visible?presentationAlpha(s):0);
            }catch(Throwable e){log.accept("ROTATION_RELEASE_FAILED "+FrameworkAccess.reason(e));}
        }
        if(visible)try{
            // Clear the pending zero as well as the immediate surface. A native leash
            // can prevent apply() from rewriting the pending transaction after timeout.
            a.opacity(s.task,presentationAlpha(s));
            if(!a.presentationLeashed(s.task)){refresh(s);renderFrame(s);}
            else try(android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){
                android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                if(surface!=null&&surface.isValid())t.setAlpha(surface,presentationAlpha(s)).apply();
            }
            frameReport("ROTATION_VISIBLE");
        }catch(Throwable e){log.accept("ROTATION_VISIBLE_FAILED "+FrameworkAccess.reason(e));}
    }
    private void rotationFailure(Session s,Throwable e){
        log.accept("ROTATION_GUARD_FAILED "+FrameworkAccess.reason(e));
        endRotation(s,true);
    }
    void collected(Object transition,Object container){
        if(sessions.isEmpty())return;
        for(Session s:sessions)if(suppressTransition(s)&&belongsTo(container,s.task)){
            try{a.call(transition,"setNoAnimation",new Class<?>[]{a.wc},container);}
            catch(Exception e){log.accept("NO_ANIMATION_FAILED "+FrameworkAccess.reason(e));}
            return;
        }
    }
    private boolean belongsTo(Object container,Object task){
        try{return container==task||container!=null&&(Boolean)a.call(container,"isDescendantOf",new Class<?>[]{a.wc},task);}
        catch(Exception e){return false;}
    }
    void transitionReady(Object transition){
        if(lock==null||sessions.size()<2)return;
        try{synchronized(lock){
            Session s=active;
            if(s==null||s.restoring||s.closing||s.fullTransition||pendingStart!=null)return;
            if(a.call(s.task,"topRunningActivity")==null)returnFromTask(s,true);
        }}catch(Exception e){log.accept("LINKED_READY_FAILED "+FrameworkAccess.reason(e));}
    }
    // Native mode/bounds are already freeform when Shell receives this transaction.
    // Keep retained tasks invisible and repair any late Shell alpha/position reset.
    void transitionBuilt(Object transition,Object info,android.view.SurfaceControl.Transaction finish){
        if(lock==null||sessions.isEmpty())return;
        try{synchronized(lock){
            java.util.List<?> targets=(java.util.List<?>)a.field(transition,"mTargets");
            java.util.List<?> changes=(java.util.List<?>)a.call(info,"getChanges");
            if(targets.size()!=changes.size())return;
            android.view.SurfaceControl.Transaction start=(android.view.SurfaceControl.Transaction)a.field(transition,"mStartTransaction");
            java.util.ArrayList<Object> steadyMiniChanges=new java.util.ArrayList<>();
            for(Session s:sessions){
                if(!suppressTransition(s))continue;
                TaskVisibilityGuard mask=presentationMask(s);
                boolean touched=false;
                for(int i=0;i<targets.size();i++){
                    Object container=a.field(targets.get(i),"mContainer"),change=changes.get(i);
                    if(!belongsTo(container,s.task))continue;
                    if(container==s.task&&steadyMiniChange(s,transition,change,targets))steadyMiniChanges.add(change);
                    a.call(change,"setFlags",new Class<?>[]{int.class},a.integer(change,"getFlags")|a.noAnimationFlag);
                    android.view.SurfaceControl leash=(android.view.SurfaceControl)a.call(change,"getLeash");
                    if(mask!=null&&mask.holding()){
                        mask.inTransaction(start,leash);mask.inTransaction(finish,leash);
                    }else if(s.parked&&leash!=null&&leash.isValid()){
                        start.setAlpha(leash,0);finish.setAlpha(leash,0);
                        // Restore wrapper leashes after commit; the task itself stays masked.
                        finish.addTransactionCommittedListener(handler::post,()->{
                            synchronized(lock){
                                try(android.view.SurfaceControl.Transaction tx=new android.view.SurfaceControl.Transaction()){
                                    if(leash.isValid())tx.setAlpha(leash,1);
                                    Session owner=sessionFor(s.task);
                                    android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                                    if(owner!=null&&surface!=null&&surface.isValid())
                                        tx.setAlpha(surface,presentationAlpha(owner));
                                    tx.apply();
                                }catch(Exception e){failure(s,e);}
                            }
                            repairTransition(s,mask);
                        });
                    }
                    touched=true;
                }
                if(s.parked||mask!=null&&mask.holding()){
                    android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                    if(surface!=null&&surface.isValid()){start.setAlpha(surface,0);finish.setAlpha(surface,0);}
                }
                if(touched&&!s.restoring&&!s.parked&&!s.screenSuspended&&s.placement!=null
                    &&(mask==null||!mask.holding())){
                    // Native finish resets task position/scale/crop even for a no-animation
                    // foreground transition. Put the owned presentation into that same
                    // transaction instead of allowing an identity frame before a later tick.
                    Rect parent=(Rect)a.call(s.parent,"getBounds");
                    Rect crop=s.lastSurfaceCrop==null?new Rect(0,0,s.placement.width(),s.placement.height()):s.lastSurfaceCrop;
                    float radius=surfaceRadius(s,s.placement,crop);
                    a.presentation(s.task,finish,s.placement,parent,crop,radius,presentationAlpha(s));
                }
                // A display-only target also receives task alpha=0 above. Repair its
                // late commits even when the task was not an independent Shell change.
                if(touched||mask!=null&&mask.holding()){
                    start.addTransactionCommittedListener(handler::post,()->repairTransition(s,mask));
                    finish.addTransactionCommittedListener(handler::post,()->repairTransition(s,mask));
                }
            }
            // TransitionInfo.getChanges returns its actual ArrayList; onTransitionReady
            // marshals this same info after this hook. Native mTargets/completion remain
            // intact. Shell must not reset a stationary mini to its logical (0,0) bounds
            // merely because another application is opening/closing in the foreground.
            // Keep at least one unowned change, including every dependent child change.
            if(!steadyMiniChanges.isEmpty()&&steadyMiniChanges.size()<changes.size()){
                changes.removeAll(steadyMiniChanges);
                log.accept("MINI_STEADY_TRANSITION_RETAINED count="+steadyMiniChanges.size());
            }
        }}catch(Throwable e){log.accept("LINKED_TX_FAILED "+FrameworkAccess.reason(e));}
    }
    private boolean steadyMiniChange(Session s,Object transition,Object change,java.util.List<?> targets){
        try{
            int transitionType=(Integer)a.field(transition,"mType");
            boolean foregroundTransition=transitionType>=1&&transitionType<=4;
            boolean busy=s.entering||s.closing||s.restoring||s.parked||s.screenSuspended||s.transitioning||s.fullTransition
                ||s.geometryDirty||pendingStart!=null||s.startMask!=null&&s.startMask.holding()
                ||a.bool(a.field(s.task,"mSurfaceAnimator"),"hasLeash");
            boolean dependent=false;
            for(Object target:targets){
                Object container=a.field(target,"mContainer");
                if(container!=s.task&&belongsTo(container,s.task)){dependent=true;break;}
            }
            Object display=a.call(s.task,"getDisplayContent"),top=a.call(s.task,"topRunningActivity");
            Rect bounds=(Rect)a.call(s.task,"getBounds"),displayBounds=display==null?null:(Rect)a.call(display,"getBounds");
            boolean geometry=s.placement!=null&&displayBounds!=null&&top!=null&&s.displayW==displayBounds.width()&&s.displayH==displayBounds.height()
                &&new Rect(0,0,s.placement.width(),s.placement.height()).equals(bounds)
                &&java.util.Objects.equals(s.safeBounds,safeArea(display))
                &&WindowPolicy.landscape(a.integer(top,"getRequestedOrientation"),s.landscape)==s.landscape
                &&(s.placement.width()>s.placement.height())==s.landscape
                &&a.call(change,"getStartAbsBounds").equals(a.call(change,"getEndAbsBounds"));
            return WindowPolicy.steadyMiniTransition(active==s&&"mini".equals(s.frame)&&foregroundTransition,busy,
                a.integer(change,"getMode"),geometry,a.integer(change,"getStartRotation")==a.integer(change,"getEndRotation"),
                dependent,a.call(change,"getSnapshot")!=null);
        }catch(Exception|LinkageError unavailable){return false;}
    }
    private void repairTransition(Session s,TaskVisibilityGuard mask){
        try{synchronized(lock){
            Session owner=sessionFor(s.task);
            if(mask!=null){
                TaskVisibilityGuard next=owner==null?null:presentationMask(owner);
                if(next!=null&&next!=mask&&next.holding())mask.continueWith(next);
                else mask.afterTransaction(owner==null?1:presentationAlpha(owner));
            }
            if(owner!=null&&!owner.restoring&&!a.animating(owner.task))renderFrame(owner);
        }}catch(Throwable e){if(sessions.contains(s))failure(s,e);else log.accept("LINKED_LATE_TX_FAILED "+FrameworkAccess.reason(e));}
    }
    void beforeBackInner(Object task,Object transition){
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground)return;
        if(transition!=null){collected(transition,task);requesting(transition,task);}
    }
    void requesting(Object transition,Object task){
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground||transition==null)return;
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
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground)return;
        s.backComplete=true;
        log.accept("CLOSE_BACK_COMPLETE task="+s.taskId);
    }
    void bounds(Object task, Rect bounds) {
        Session s=sessionFor(task);
        if(s!=null&&!s.restoring&&s.placement!=null)bounds.set(0,0,s.placement.width(),s.placement.height());
    }
    void prepared(Object task) {
        Session s=sessionFor(task);if(s==null||lock==null)return;
        try{synchronized(lock){
            if(!s.restoring&&!s.parked&&!s.refreshingGeometry&&!a.presentationLeashed(task)){
                Object display=a.call(task,"getDisplayContent");
                Rect bounds=display==null?null:(Rect)a.call(display,"getBounds");
                // A native surface traversal may safely update position/scale. It
                // must not change Activity bounds, focus, or visibility mid-traversal.
                if(!s.transitioning&&(s.geometryDirty||bounds!=null&&(s.displayW!=bounds.width()||s.displayH!=bounds.height()))){
                    Geometry.Placement p=s.placement;
                    Rect canvas=(Rect)a.call(task,"getBounds");
                    int expectedWidth=s.landscape?Math.round(s.logicalShort*(16f/9f)):s.logicalShort;
                    int expectedHeight=s.landscape?s.logicalShort:Math.round(s.logicalShort*(16f/9f));
                    if(p!=null&&canvas.equals(new Rect(0,0,expectedWidth,expectedHeight)))refresh(s,true);
                    else configurationChanged(task);
                }
            }
            apply(s);
        }}catch(Throwable e){failure(s,e);}
    }
    // Caller holds the WM lock. Native transitions keep their own leash until finished.
    private void renderFrame(Session s) throws Exception {
        a.beginFrame();boolean success=false;
        try{apply(s);success=true;}finally{a.endFrame(success);}
    }
    private void apply(Session s) throws Exception {
        if (!sessions.contains(s)||!a.bool(s.task,"isAttached")) return;
        if(s.restoring){
            if(!s.restoreForeground&&s.startMask!=null)s.startMask.hold();
            return;
        }
        if(s.placement==null)return;
        if(s.parked||s.screenSuspended){
            a.opacity(s.task,0);if(s.decoration!=null)s.decoration.hide(s.task);return;
        }
        if(s.startMask!=null&&s.startMask.holding()){
            s.startMask.hold();if(s.decoration!=null)s.decoration.hide(s.task);return;
        }
        if(s.rotation.active()&&!s.rotation.fading()){
            try{if(s.rotationMask!=null)s.rotationMask.hold();}
            catch(Throwable e){rotationFailure(s,e);}
            if(s.decoration!=null)s.decoration.hide(s.task);return;
        }
        if(a.presentationLeashed(s.task)){
            if(s.entering||s.closing)a.opacity(s.task,0);
            if(s.decoration!=null)s.decoration.hide(s.task);return;
        }
        if("normal".equals(s.frame)&&!s.resizing&&!s.transitioning&&!s.entering&&!s.fullTransition){
            Geometry.Placement adjusted=normalForIme(s);
            if(adjusted!=null&&!adjusted.equals(s.placement)){
                s.placement=adjusted;handler.post(()->{if(active==s)frameReport("IME_PLACEMENT");});
            }
        }
        if(s.cover!=null&&!s.coverFullscreen)s.cover.target(visualBounds(s.placement));
        Rect parentBounds = (Rect) a.call(s.parent, "getBounds");
        Geometry.Placement p = s.placement;
        // Transform the task; preserve native child-window layout/input metadata.
        a.transform(s.task, p.scale(), p.x() - parentBounds.left, p.y() - parentBounds.top);
        float visualRadius=Math.min(a.config(s.task,false).densityDpi/160f*MotionStyle.radius(p.scale(),s.defaultScale)*s.radiusFactor,Math.min(p.width(),p.height())*p.scale()/2f);
        Rect crop=new Rect(0,0,p.width(),p.height());
        Object main=a.call(s.task,"getTopVisibleAppMainWindow");
        if(main!=null){
            Rect window=(Rect)a.call(main,"getFrame"),bounds=(Rect)a.call(s.task,"getBounds");
            // Some base windows start below the logical origin (native top inset).
            // Round their visible edge, rather than rounding transparent space
            // above it. Do not crop dialogs, partial-width windows or IME resize.
            if(window.left<=bounds.left&&window.right>=bounds.right&&window.bottom>=bounds.bottom
                &&window.top>bounds.top&&window.top<bounds.bottom)
                crop.top=window.top-bounds.top;
        }
        a.style(s.task,crop,Math.min(visualRadius/p.scale(),crop.height()/2f));
        if(!crop.equals(s.lastSurfaceCrop)){
            s.lastSurfaceCrop=new Rect(crop);
            log.accept("VISIBLE_WINDOW_CROP task="+s.taskId+" crop="+crop+" main="+main);
        }
        a.opacity(s.task,presentationAlpha(s));
        if(s.decoration!=null){
            boolean wasPresented=s.presented;
            s.presented=s.decoration.update(s.task,s.parent,p,a.config(s.task,false).densityDpi/160f,s.displayW>s.displayH,s.barAlpha*s.rotation.alpha(SystemClock.elapsedRealtime())*(s.cover==null?1:0),!a.inFrame()&&!s.backRequested);
            if(s.presented&&!wasPresented)handler.post(()->{
                if(active!=s||s.restoring||s.closing)return;
                log.accept("PRESENTED task="+s.taskId+" elapsedMs="+(SystemClock.elapsedRealtime()-s.startedAt));
                if(s.entering&&!s.entryStarted){
                    s.entryStarted=true;
                    if(s.cover!=null){s.entering=false;s.transitioning=false;s.opacity=1;s.barAlpha=1;try{synchronized(lock){renderFrame(s);}}catch(Exception e){failure(s,e);}}
                    else visibility(s,true);
                }
                else report("PRESENTED");
            });
        }
    }
    private float surfaceRadius(Session s,Geometry.Placement p,Rect crop)throws Exception{
        float visual=Math.min(a.config(s.task,false).densityDpi/160f*MotionStyle.radius(p.scale(),s.defaultScale)*s.radiusFactor,
            Math.min(p.width(),p.height())*p.scale()/2f);
        return Math.min(visual/p.scale(),crop.height()/2f);
    }
    void configurationChanged(Object task) {
        Session changed=sessionFor(task);
        if(changed!=null&&!changed.refreshingGeometry)changed.geometryDirty=true;
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
        if (handler != null) handler.post(() -> { if (sessions.contains(s)) recover("适配异常：" + FrameworkAccess.reason(e)); });
    }
    // Screen/keyguard changes suspend presentation, never restore the fullscreen
    // configuration. Keep frame, placement, return chain and recovery journal.
    private void updateScreenState(Session s)throws Exception {
        boolean blocked=!context.getSystemService(PowerManager.class).isInteractive()
            ||context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(s.screenSuspended==blocked)return;
        s.screenSuspended=blocked;
        if(!blocked){
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
        }
        if(a.bool(s.task,"isAttached")){renderFrame(s);a.call(wms,"requestTraversal");}
        log.accept((blocked?"SCREEN_SUSPEND":"SCREEN_RESUME")+" task="+s.taskId+" frame="+s.frame);
        report(blocked?"SCREEN_SUSPEND":"SCREEN_RESUME");
    }
    // Optional diagnostics must never enter the task recovery path.
    private void traceVisibleRuntime(Session s,Object display){
        try{
            Object top=a.call(s.task,"topRunningActivity");
            Object current=a.field(display,"mCurrentFocus");
            Object focused=a.focused.invoke(root);
            String runtime=s.frame+"/"+a.call(top,"getState")+"/input="+(current!=null&&a.call(current,"getTask")==s.task)
                +"/focusedTask="+(focused==null?-1:(Integer)a.field(focused,"mTaskId"));
            if(!runtime.equals(s.lastRuntimeState)){
                s.lastRuntimeState=runtime;
                log.accept("VISIBLE_RUNTIME task="+s.taskId+" state="+runtime+" focusable="+a.bool(s.task,"isFocusable"));
            }
        }catch(Exception|LinkageError e){
            if(!"diagnostic-unavailable".equals(s.lastRuntimeState))
                log.accept("VISIBLE_RUNTIME_UNAVAILABLE "+FrameworkAccess.reason(e));
            s.lastRuntimeState="diagnostic-unavailable";
        }
    }
    private void tick(Session s) {
        if(!sessions.contains(s)||s.restoring||recovering)return;
        try {
            if(active!=s){
                synchronized(lock){if(find(s.taskId)!=s.task||a.call(s.task,"topRunningActivity")==null){retire(s);return;}apply(s);}
                handler.postDelayed(()->tick(s),1000);return;
            }
            if (!s.lease.isBinderAlive()) { s.closing=true; recover("SystemUI 控制进程退出"); return; }
            synchronized (lock) {
                if(find(s.taskId)!=s.task||a.call(s.task,"topRunningActivity")==null){
                    if(sessions.size()>1&&!s.closing&&returnFromTask(s)){
                        if(active==s&&!s.restoring)handler.postDelayed(()->tick(s),250);
                    }else recover("应用任务已结束");
                    return;
                }
                updateScreenState(s);
                if(s.screenSuspended){handler.postDelayed(()->tick(s),500);return;}
                Object display = a.call(s.task, "getDisplayContent");
                if (display == null) throw new IllegalStateException("任务已离开显示器");
                if(!a.animating(s.task))resumeMini(s);
                traceVisibleRuntime(s,display);
                Rect b = (Rect) a.call(display, "getBounds");
                if ((!s.transitioning&&updateOrientation(s)) || s.displayW != b.width() || s.displayH != b.height()) { refresh(s); }
                else if (!s.transitioning && !a.animating(s.task)) { apply(s); a.call(wms, "requestTraversal"); }
            }
            if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), 250);
        } catch (Throwable e) { failure(s, e); }
    }

    private JSONObject encode(Session s)throws Exception{
        JSONObject o=new JSONObject().put("version",4).put("taskId",s.taskId).put("userId",s.userId)
            .put("package",s.pkg).put("token",s.token).put("component",s.component)
            .put("baseId",s.baseId==null?JSONObject.NULL:s.baseId).put("cropX",s.crop.x).put("cropY",s.crop.y)
            .put("mode",s.mode).put("bounds",s.bounds.flattenToString()).put("alwaysOnTop",s.alwaysOnTop)
            .put("topState",s.topState).put("forceTranslucent",s.forceTranslucent);
        if(s.originalConfig!=null){Parcel parcel=Parcel.obtain();try{
            s.originalConfig.writeToParcel(parcel,0);
            o.put("originalConfig",android.util.Base64.encodeToString(parcel.marshall(),android.util.Base64.NO_WRAP));
        }finally{parcel.recycle();}}
        return o;
    }
    private void writeJournal(java.util.List<Session> list)throws Exception{
        if(list.isEmpty()){journal.delete();return;}
        JSONArray entries=new JSONArray();for(Session s:list)entries.put(encode(s));
        byte[] bytes=new JSONObject().put("version",5).put("sessions",entries).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw new IOException("恢复记录过大");
        FileOutputStream stream=null;
        try{stream=journal.startWrite();stream.write(bytes);journal.finishWrite(stream);}
        catch(Exception e){if(stream!=null)journal.failWrite(stream);throw e;}
    }
    private void save(Session s)throws Exception{
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(sessions);
        if(!next.contains(s))next.add(s);
        writeJournal(next);sessions=java.util.List.copyOf(next);
    }
    private Session decode(JSONObject o)throws Exception{
        Session s=new Session();int version=o.getInt("version");
        s.taskId=o.getInt("taskId");s.userId=o.getInt("userId");
        s.pkg=o.getString("package");s.token=o.getString("token");s.component=o.getString("component");
        s.baseId=version==1?s.token:o.isNull("baseId")?null:o.getString("baseId");
        s.crop=version==1?null:new Point(o.getInt("cropX"),o.getInt("cropY"));
        if(o.has("originalConfig")){
            byte[] data=android.util.Base64.decode(o.getString("originalConfig"),android.util.Base64.NO_WRAP);
            Parcel parcel=Parcel.obtain();try{parcel.unmarshall(data,0,data.length);parcel.setDataPosition(0);
                s.originalConfig=Configuration.CREATOR.createFromParcel(parcel);
            }finally{parcel.recycle();}
        }
        s.mode=o.getInt("mode");s.bounds=Rect.unflattenFromString(o.getString("bounds"));
        s.topState=o.getInt("topState");s.alwaysOnTop=o.getBoolean("alwaysOnTop");
        s.forceTranslucent=version>=3&&o.getBoolean("forceTranslucent");s.restoreTranslucent=version>=3;
        if(version<1||version>4||!Wire.allowedPackage(s.pkg)||!Wire.validToken(s.token)
            ||ComponentName.unflattenFromString(s.component)==null
            ||!s.pkg.equals(ComponentName.unflattenFromString(s.component).getPackageName())||s.userId!=0||s.taskId<0
            ||(s.mode!=0&&s.mode!=1)||s.bounds==null||!s.bounds.isEmpty()||s.alwaysOnTop||(s.topState!=0&&s.topState!=2))
            throw new IOException("恢复记录不匹配");
        return s;
    }
    private java.util.List<Session> readJournal()throws Exception{
        byte[] bytes=journal.readFully();if(bytes.length>262144)throw new IOException("恢复记录过大");
        JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.getInt("version")!=5)return java.util.List.of(decode(doc));
        JSONArray entries=doc.getJSONArray("sessions");
        if(entries.length()==0||entries.length()>MAX_LINKED_TASKS)throw new IOException("恢复任务数量异常");
        java.util.ArrayList<Session> result=new java.util.ArrayList<>();java.util.Set<Integer> ids=new java.util.HashSet<>();
        String token=null;
        for(int n=0;n<entries.length();n++){
            Session s=decode(entries.getJSONObject(n));
            if(!ids.add(s.taskId)||token!=null&&!token.equals(s.token))throw new IOException("恢复任务重复或标识不同");
            token=s.token;result.add(s);
        }
        return java.util.List.copyOf(result);
    }
    private void forget(Session s)throws Exception{
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(sessions);next.remove(s);
        writeJournal(next);sessions=java.util.List.copyOf(next);
        if(audio!=null&&s.uid>=0)handler.post(()->{
            if(sessions.stream().noneMatch(other->other.uid==s.uid))audio.setMuted(s.uid,false);
        });
        // Successful foreground restoration already verified native fullscreen mode.
        // Release its history now: waiting for focused/drawn polling lets a quick
        // swipe into recents hide this task again as soon as it loses focus.
        // Background restores keep their history, including parked linked tasks.
        if(s.restoreForeground){
            history.removed(s.taskId);fullscreenSeen.remove(s.taskId);
            saveHistory();notifyRecents();
            log.accept("HISTORY_FULLSCREEN_RELEASED task="+s.taskId+" source=foreground-restore");
        }
        s.rotation.clear();s.rotationTransitions.clear();
        if(s.rotationMask!=null){
            if(s.rotationMask.holding())s.rotationMask.transfer();
            s.rotationMask.afterTransaction(1);
        }
        if(s.cover!=null&&!s.restoreForeground)endCover(s);
        if(s.decoration!=null)s.decoration.dispose();
        if(s.lease!=null&&s.death!=null)try{s.lease.unlinkToDeath(s.death,0);}catch(RuntimeException ignored){}
        endedTaskId=s.taskId;
    }
    private void holdRestoreMask(Session s,Object task)throws Exception{
        if(s.startMask==null||!s.startMask.holding()){
            TaskVisibilityGuard previous=s.startMask;
            TaskVisibilityGuard mask=new TaskVisibilityGuard(a,task);
            mask.hide();s.startMask=mask;
            if(previous!=null)previous.continueWith(mask);
        }else s.startMask.hold();
    }
    private boolean manualRecoveryReady(Session s)throws Exception{
        if(s.task!=null||!"手动恢复".equals(s.recoveryReason)
            ||!context.getSystemService(UserManager.class).isUserUnlocked())return false;
        Object recents=a.field(atm,"mRecentTasks");
        // Called under the WM lock: only inspect the native loaded flag. Calling
        // the loader here could invert its per-user load lock with the WM lock.
        return (Boolean)a.call(recents,"isRecentTasksLoaded",new Class<?>[]{int.class},s.userId);
    }
    private boolean releaseStaleRecovery(Session s,String reason)throws Exception{
        history.removed(s.taskId);fullscreenSeen.remove(s.taskId);saveHistory();notifyRecents();
        log.accept("RECOVERY_STALE_RELEASED task="+s.taskId+" reason="+reason);return true;
    }
    private boolean restoreTask(Session s,boolean foreground)throws Exception{
        boolean manualReady=manualRecoveryReady(s);
        Object task=find(s.taskId);boolean attached=task!=null;
        if(task==null){
            task=a.lookup.invoke(root,s.taskId,1);
            if(task==null){
                if(manualReady)return releaseStaleRecovery(s,"missing-after-recents-loaded");
                return s.task!=null; // Wait while boot-time recents are still unknown.
            }
        }
        if(!identity(task,s)||s.task!=null&&s.task!=task){
            if(manualReady)return releaseStaleRecovery(s,"task-id-reused");
            throw new IllegalStateException("恢复任务身份已改变");
        }
        if(s.task==null&&s.baseId==null&&!"手动恢复".equals(s.recoveryReason))return false;
        boolean surfaceAttached=attached&&a.bool(task,"isAttached");
        if(surfaceAttached){
            Object display=a.call(task,"getDisplayContent");
            if(display==null||(Integer)a.field(display,"mDisplayId")!=0||!a.bool(task,"isRootTask")
                ||!a.bool(task,"isLeafTask")||!a.tda.isInstance(a.call(task,"getParent")))
                throw new IllegalStateException("恢复任务层级已改变");
            if(a.integer(task,"getRelativeDisplayRotation")!=0)return false;
            if(s.task==null)s.task=task;
            if(!foreground)holdRestoreMask(s,task);
        }
        if(a.animating(task))return false;
        if(surfaceAttached){
            if(!foreground&&!s.backRequested&&a.call(task,"topRunningActivity")!=null){
                a.call(task,"setAlwaysOnTop",new Class<?>[]{boolean.class},false);
                a.call(task,"setFocusable",new Class<?>[]{boolean.class},true);
                a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);
                // moveTaskToBack may invoke its inner callback synchronously or queue it.
                // Register the request before either path can enter the transition hooks.
                s.backRequested=true;s.backComplete=false;
                try{
                    if(!(Boolean)a.call(task,"moveTaskToBack",new Class<?>[]{a.task},task))
                        throw new IllegalStateException("系统未允许任务退到后台");
                }catch(Exception e){s.backRequested=false;throw e;}
                return false;
            }
            // A queued request returning true does not mean the task is already behind Home.
            // Keep freeform geometry and the mask until the native move actually completes.
            if(!foreground&&(s.backRequested&&!s.backComplete||a.bool(task,"isVisibleRequested")))return false;
        }
        a.call(atm,"deferWindowLayout");
        try{
            if(s.decoration!=null&&surfaceAttached)s.decoration.remove(task);
            // Task.setAlwaysOnTop reorders through getParent(). A finished task
            // can survive in native recents without a parent; restore its saved
            // override below, never call hierarchy mutators on that detached task.
            if(surfaceAttached){
                a.call(task,"setFocusable",new Class<?>[]{boolean.class},true);
                if(foreground)a.call(task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-fullscreen-endpoint");
            }
            if(s.restoreTranslucent)a.call(task,"setForceTranslucent",new Class<?>[]{boolean.class},s.forceTranslucent);
            if(surfaceAttached)a.call(task,"setAlwaysOnTop",new Class<?>[]{boolean.class},s.alwaysOnTop);
            if(surfaceAttached){
                a.call(task,"setWindowingMode",new Class<?>[]{int.class},s.mode);
                a.call(task,"setBounds",new Class<?>[]{Rect.class},new Rect(s.bounds));
            }
            Configuration restored=new Configuration(s.originalConfig!=null?s.originalConfig:a.config(task,true));
            a.call(a.field(restored,"windowConfiguration"),"setAlwaysOnTop",new Class<?>[]{int.class},s.topState);
            a.call(task,"onRequestedOverrideConfigurationChanged",new Class<?>[]{Configuration.class},restored);
            Object requested=a.windowConfig(task,true);
            if(a.integer(requested,"getWindowingMode")!=s.mode||!s.bounds.equals(a.call(requested,"getBounds"))
                ||(Integer)a.field(requested,"mAlwaysOnTop")!=s.topState)throw new IllegalStateException("原请求配置尚未恢复");
            if(surfaceAttached){
                if(a.integer(task,"getWindowingMode")!=1)throw new IllegalStateException("原全屏模式尚未恢复");
                Point pos=new Point();a.call(task,"getRelativePosition",new Class<?>[]{Point.class},pos);
                a.transform(task,1f,pos.x,pos.y);
                Point cache=(Point)a.field(task,"mLastSurfaceSize");cache.set(-1,-1);
                a.call(task,"updateSurfaceSize",new Class<?>[]{android.view.SurfaceControl.Transaction.class},a.call(task,"getPendingTransaction"));
                if(cache.x<0||cache.y<0){a.style(task,0,0,0);cache.set(0,0);}
                a.call(a.call(task,"getPendingTransaction"),"setCornerRadius",new Class<?>[]{android.view.SurfaceControl.class,float.class},a.field(task,"mSurfaceControl"),0f);
                new TaskDecoration(a).restoreLayer(task);
                if(s.startMask!=null){
                    if(s.startMask.holding())s.startMask.release();
                    s.startMask.afterTransaction(1);
                }
                s.rotation.clear();
                if(s.rotationMask!=null){
                    if(s.rotationMask.holding())s.rotationMask.release();
                    s.rotationMask.afterTransaction(1);
                }
                a.opacity(task,1f);
            }
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");a.call(wms,"requestTraversal");
            return true;
        }finally{a.call(atm,"continueWindowLayout");}
    }
    private void recover(String why){
        try{synchronized(lock){
            if(!recovering){
                cancelStart("recovery");
                if(sessions.isEmpty()){
                    if(!journal.getBaseFile().exists()){note("IDLE：无需恢复");return;}
                    sessions=readJournal();
                }
                Session selected=active;
                java.util.ArrayList<Session> ordered=new java.util.ArrayList<>(sessions);
                if(selected!=null){ordered.remove(selected);ordered.add(selected);}
                for(Session s:ordered){
                    s.restoreForeground=s==selected&&("小窗动画进入全屏".equals(why)||"手动恢复".equals(why));
                    s.restoring=true;s.animationRev++;s.entering=false;s.transitioning=false;s.opacity=0;
                    s.backRequested=false;s.recoveryReason=why;
                    if(s.task!=null&&a.bool(s.task,"isAttached")){
                        if(s.decoration!=null)s.decoration.hide(s.task);
                        if(!s.restoreForeground)holdRestoreMask(s,s.task);
                        a.opacity(s.task,0);
                    }
                }
                sessions=java.util.List.copyOf(ordered);recovering=true;active=null;recoveryWaits=0;
            }
            if(sessions.isEmpty()){
                recovering=false;pendingRecovery=null;recoveryWaits=0;journal.delete();notifyRecents();note("RESTORED："+why);return;
            }
            Session s=sessions.get(0);pendingRecovery=s;
            if(restoreTask(s,s.restoreForeground)){
                forget(s);pendingRecovery=null;recoveryWaits=0;
                handler.post(()->recover(why));return;
            }
            if(++recoveryWaits>100){note("RECOVERY_PENDING：保留全部恢复记录，可手动恢复");return;}
            handler.postDelayed(()->recover(why),100);
        }}catch(Throwable e){note("RECOVERY_FAILED："+FrameworkAccess.reason(e)+"；保留全部恢复记录");}
    }

    private String describe(Session s) {
        Geometry.Placement p = s.placement;
        return "taskId=" + s.taskId + " package=" + s.pkg + "\n"
            + (p == null ? "等待转场结束" : "逻辑=" + p.width() + "×" + p.height()
                + "；显示=" + Math.round(p.width() * p.scale()) + "×" + Math.round(p.height() * p.scale())
                + "；scale=" + p.scale())
            + "\n运行时长=" + (SystemClock.elapsedRealtime()-s.startedAt)/1000 + " 秒（无定时退出）"
            + "\n窗口配置=系统统一下发；普通应用无需作用域";
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
            .putExtra("transitioning",s.transitioning||s.rotation.active()||s.cover!=null).putExtra("fullTransition",s.fullTransition)
            .putExtra("transitionHandle",s.transitionHandle).putExtra("presented",s.presented&&!s.rotation.active()).putExtra("nativeHandle",true)
            .putExtra("barAlpha",s.barAlpha).putExtra("miniLarge",s.miniLarge)
            .putExtra("edgeHidden",s.edgeHidden).putExtra("edgeSide",s.edgeSide)
            .putExtra("edgeBarDragging",s.edgeBarDragging).putExtra("edgeBarCenterX",s.edgeBarCenterX).putExtra("edgeBarCenterY",s.edgeBarCenterY)
            .putExtra("edgeCenterY",s.miniPlacement==null?p.y()+p.height()*p.scale()/2:s.miniPlacement.y()+s.miniPlacement.height()*s.miniPlacement.scale()/2)
            .putExtra("muted",audio!=null&&audio.isMuted(s.uid));
        if(s.safeBounds!=null)result.putExtra("safeLeft",s.safeBounds.left).putExtra("safeTop",s.safeBounds.top)
            .putExtra("safeRight",s.safeBounds.right).putExtra("safeBottom",s.safeBounds.bottom).putExtra("placementMargin",s.placementMargin);
    }
    private void frameReport(String event){
        Intent i=new Intent(Wire.RESULT).putExtra("protocol",Wire.PROTOCOL).putExtra("event",event)
            .putExtra("state","active").putExtra("sequence",++frameSequence);
        fillFrame(i,active);
        if(!Wire.transmit(frameSink,i))frameSink=null;
    }
    private void environment(Intent out){
        synchronized(lock){try{
            Object focused=a.focused.invoke(root);String foreground="";
            if(focused!=null){Object top=a.call(focused,"topRunningActivity");if(top!=null)foreground=(String)a.field(top,"packageName");}
            out.putExtra("foreground",foreground);
            Object display=a.call(root,"getDefaultDisplay");
            Object ime=a.call(display,"getImeWindow");boolean visible=ime!=null&&a.bool(ime,"isVisible");
            out.putExtra("imeVisible",visible).putExtra("imeTop",visible?((Rect)a.call(ime,"getFrame")).top:Integer.MAX_VALUE);
            Object focus=a.field(display,"mCurrentFocus");boolean system=false;
            if(focus!=null){android.view.WindowManager.LayoutParams attrs=(android.view.WindowManager.LayoutParams)a.field(focus,"mAttrs");
                String title=String.valueOf(attrs.getTitle());system=BackBridge.PACKAGE.equals(attrs.packageName)&&!title.startsWith("NativeComplements");}
            out.putExtra("systemOverlay",system);
        }catch(Exception e){out.putExtra("environmentError",FrameworkAccess.reason(e));}}
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
        result.putExtra("hostConnected",uiReady&&uiLease!=null&&uiLease.isBinderAlive()&&SystemClock.elapsedRealtime()-uiSeen<3000);
        result.putExtra("lastTouch",uiTouch);
        environment(result);
        Bundle transport=new Bundle();transport.putBinder("controlChannel",controlChannel);result.putExtras(transport);
        fillFrame(result,s);
        result.putExtra("immersive",backgroundImmersive(s));
        if ("recovery".equals(state) && pending != null)
            result.putExtra("recoverPackage", pending.pkg).putExtra("recoverToken", pending.token)
                .putExtra("recoverComponent", pending.component);
        handler.post(() -> {
            try { context.sendBroadcast(result, Wire.PERMISSION,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                context.sendBroadcast(new Intent(result).setPackage(BackBridge.PACKAGE),null,
                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()); }
            catch (Throwable e) { log.accept("RESULT_SEND_FAILED " + FrameworkAccess.reason(e)); }
        });
    }
}
