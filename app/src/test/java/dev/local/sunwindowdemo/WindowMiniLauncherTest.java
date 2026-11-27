package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.AtomicFile;
import android.view.SurfaceControl;
import dev.local.sunwindowdemo.core.LauncherTargets;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Real Engine endpoints; only native WM objects and Surface transactions are local fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowMiniLauncherTest.SurfaceShadow.class,WindowMiniLauncherTest.TransactionShadow.class,WindowFullscreenCoverTest.BuilderShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowMiniLauncherTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final int HOME_UID=10042;
    private WindowEngine engine;
    private FrameworkAccess access;
    private Context context;
    private Root root;
    private Wms wms;
    private Handler handler;
    private AtomicFile journal;
    private WindowEngine.Session mini;
    private final List<String> events=new ArrayList<>(),logs=new ArrayList<>();

    private static void set(Object owner,String name,Object value)throws Exception{
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(engine,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static final class Animator { boolean leash;public boolean hasLeash(){return leash;} }
    public static final class Controller {
        boolean collecting=true,playing;
        public boolean mBuildingTransitionLayers;
        public boolean inTransition(Task task){return collecting||playing;}
        public boolean inPlayingTransition(Task task){return playing;}
        public boolean isPlaying(){return playing;}
        public boolean isCollecting(){return collecting;}
        public void setNoAnimation(Task task){}
    }
    public static final class Display {
        public int mDisplayId;
        public Rect getBounds(){return new Rect(0,0,1200,2400);}
    }
    public static final class Top {
        public String packageName;
        public int mUserId;
        int uid,orientationRequest=-1;Task task;
        public int getRequestedOrientation(){return orientationRequest;}
        public int getUid(){return uid;}
        public Task getTask(){return task;}
        public Display getDisplayContent(){return task.display;}
    }
    public static final class LaunchRecord {
        public Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        public String packageName,launchedFromPackage=LauncherTargets.PIXEL;
        public int uid,mUserId,launchedFromUid=HOME_UID;
        public int getUid(){return uid;}
        public Task getTask(){return null;}
        public Display getDisplayContent(){return null;}
    }
    public static final class Task {
        public int mTaskId,mUserId;
        public android.content.ComponentName realActivity;
        boolean translucent;
        public boolean isForceTranslucent(){return translucent;}
        public void setForceTranslucent(boolean value){translucent=value;}
        public boolean isVisibleRequested(){return true;}
        public SurfaceControl getLastRelativeLayer(){return null;}
        public int getLastLayer(){return 3;}
        public final Animator mSurfaceAnimator=new Animator();
        public final Controller mTransitionController=new Controller();
        public final SurfaceControl mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        public final Point mLastSurfaceSize=new Point(400,700);
        final SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        final Configuration requested=new Configuration();
        final Top top=new Top();
        final List<String> events;
        Display display;
        boolean attached=true,root=true,leaf=true,focusable;
        int activityType=1,rotation,mode=5,frontCalls,backCalls,configWrites;
        Task(int id,Display display,List<String> events){mTaskId=id;this.display=display;this.events=events;top.uid=10100+id;top.packageName="example.app"+id;top.task=this;realActivity=new android.content.ComponentName(top.packageName,top.packageName+".Main");}
        public boolean isAttached(){return attached;}
        public boolean isRootTask(){return root;}
        public boolean isLeafTask(){return leaf;}
        public int getActivityType(){return activityType;}
        public int getRelativeDisplayRotation(){return rotation;}
        public int getWindowingMode(){return mode;}
        public Display getDisplayContent(){return display;}
        public Task getParent(){return this;}
        public Top topRunningActivity(){return top;}
        public Rect getBounds(){return display.getBounds();}
        public Configuration getConfiguration(){return requested;}
        public Configuration getRequestedOverrideConfiguration(){return requested;}
        public void onRequestedOverrideConfigurationChanged(Configuration value){configWrites++;requested.setTo(value);events.add("config:"+mTaskId);}
        public void forAllActivities(java.util.function.Consumer<Object> consumer){}
        public void setFocusable(boolean value){focusable=value;}
        public void setAlwaysOnTop(boolean value){}
        public void setWindowingMode(int value){mode=value==0?1:value;events.add("mode:"+mTaskId);}
        public void setBounds(Rect bounds){}
        public void moveToFront(String reason){assertEquals("NativeComplements-fullscreen-endpoint",reason);frontCalls++;events.add("front:"+mTaskId);}
        public boolean moveTaskToBack(Task task){backCalls++;events.add("back:"+mTaskId);return true;}
        public void getRelativePosition(Point point){point.set(0,0);}
        public void updateSurfaceSize(SurfaceControl.Transaction transaction){mLastSurfaceSize.set(1200,2400);}
        public SurfaceControl.Transaction getPendingTransaction(){return transaction;}
    }
    public static final class Root {
        final Map<Integer,Task> tasks=new HashMap<>();
        final Display display=new Display();
        int visibleCalls,resumeCalls;
        public Object lookup(int id,int mode){return tasks.get(id);}
        public Display getDefaultDisplay(){return display;}
        public void ensureActivitiesVisible(){visibleCalls++;}
        public void resumeFocusedTasksTopActivities(){resumeCalls++;}
    }
    public static final class Policy { public int mCurrentUserId; }
    public static final class Wms {
        public final Policy mPolicy=new Policy();
        int traversals;
        public void requestTraversal(){traversals++;}
    }
    public static final class Atm {
        int deferred,continued;
        public void deferWindowLayout(){deferred++;}
        public void continueWindowLayout(){continued++;}
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        float scaleX=.25f,scaleY=.25f,x=80,y=90,alpha=1;
        @Implementation protected boolean isValid(){return true;}
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @Implementation protected void __constructor__(){}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl surface,float x,float y){SurfaceShadow s=Shadow.extract(surface);s.scaleX=x;s.scaleY=y;return self();}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl surface,float x,float y){SurfaceShadow s=Shadow.extract(surface);s.x=x;s.y=y;return self();}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl surface,float alpha){SurfaceShadow s=Shadow.extract(surface);s.alpha=alpha;return self();}
        @Implementation protected SurfaceControl.Transaction setCornerRadius(SurfaceControl surface,float radius){return self();}
        @Implementation protected SurfaceControl.Transaction show(SurfaceControl surface){return self();}
        @Implementation protected SurfaceControl.Transaction remove(SurfaceControl surface){return self();}
        @Implementation protected SurfaceControl.Transaction setRelativeLayer(SurfaceControl surface,SurfaceControl relative,int z){return self();}
        @Implementation protected SurfaceControl.Transaction setLayer(SurfaceControl surface,int z){return self();}
        @Implementation protected SurfaceControl.Transaction setColor(SurfaceControl surface,float[] color){return self();}
        @Implementation protected SurfaceControl.Transaction setWindowCrop(SurfaceControl surface,int width,int height){return self();}
        @Implementation protected void apply(){}
        @Implementation protected void close(){}
        @org.robolectric.annotation.RealObject private SurfaceControl.Transaction real;
        private SurfaceControl.Transaction self(){return real;}
    }
    private void home(boolean system){
        PackageInfo pkg=new PackageInfo();pkg.packageName=LauncherTargets.PIXEL;
        pkg.applicationInfo=new ApplicationInfo();pkg.applicationInfo.packageName=pkg.packageName;
        pkg.applicationInfo.uid=HOME_UID;pkg.applicationInfo.flags=system?ApplicationInfo.FLAG_SYSTEM:0;
        var pm=Shadows.shadowOf(context.getPackageManager());pm.installPackage(pkg);
        ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();info.activityInfo.packageName=pkg.packageName;
        info.activityInfo.name=pkg.packageName+".Home";info.activityInfo.applicationInfo=pkg.applicationInfo;
        pm.setResolveInfosForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),List.of(info));
        LauncherTargets.invalidate();
    }
    @Before public void setup()throws Throwable{
        context=RuntimeEnvironment.getApplication();home(true);
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"wc",Task.class);set(access,"task",Task.class);set(access,"tda",Task.class);
        set(access,"lookup",Root.class.getMethod("lookup",int.class,int.class));
        set(access,"playingTransition",Controller.class.getMethod("inPlayingTransition",Task.class));
        root=new Root();wms=new Wms();handler=new Handler(Looper.getMainLooper());
        engine=new WindowEngine(access,logs::add);
        set(engine,"context",context);set(engine,"root",root);set(engine,"wms",wms);set(engine,"atm",new Atm());
        set(engine,"lock",new Object());set(engine,"handler",handler);set(engine,"uiReady",true);
        Binder lease=new Binder();set(engine,"uiLease",lease);
        journal=new AtomicFile(temporary.newFile("recovery.json"));journal.delete();set(engine,"journal",journal);
        set(engine,"historyFile",new AtomicFile(temporary.newFile("history.json")));
        mini=session(42);mini.lease=lease;mini.frame="mini";
        call("save",new Class<?>[]{WindowEngine.Session.class},mini);set(engine,"active",mini);
        events.clear();
    }
    private WindowEngine.Session session(int id)throws Exception{
        Task task=new Task(id,root.display,events);root.tasks.put(id,task);
        WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.taskId=id;s.uid=task.top.uid;
        s.pkg=task.top.packageName;s.component=s.pkg+"/.Main";s.baseId="page"+id;s.token=Wire.token();
        s.crop=new Point(400,700);s.bounds=new Rect();s.mode=1;s.topState=2;s.originalConfig=new Configuration();
        Object window=get(s.originalConfig,"windowConfiguration");
        Method mode=window.getClass().getDeclaredMethod("setWindowingMode",int.class);mode.setAccessible(true);mode.invoke(window,s.mode);
        Method top=window.getClass().getDeclaredMethod("setAlwaysOnTop",int.class);top.setAccessible(true);top.invoke(window,s.topState);
        return s;
    }
    @After public void cleanup(){handler.removeCallbacksAndMessages(null);LauncherTargets.invalidate();}
    private void assertFullscreen(Task task){
        assertEquals(logs.toString(),1,task.mode);assertEquals(1,task.configWrites);assertEquals(1,task.frontCalls);assertEquals(0,task.backCalls);assertTrue(task.focusable);
        SurfaceShadow surface=Shadow.extract(task.mSurfaceControl);
        assertEquals(1,surface.scaleX,0);assertEquals(1,surface.scaleY,0);assertEquals(0,surface.x,0);assertEquals(0,surface.y,0);assertEquals(1,surface.alpha,0);
        assertFalse(engine.owns(task));
    }
    private WindowLauncherLaunch adapter()throws Exception{
        return new WindowLauncherLaunch(access,engine,WindowLauncherLaunchTest.Starter.class,WindowLauncherLaunchTest.Options.class,Object.class,logs::add);
    }
    @Test public void nativeStartResultAdapterRestoresOnFirstAuthorizedLauncherDelivery()throws Throwable{
        Task task=(Task)mini.task;LaunchRecord request=new LaunchRecord();request.uid=mini.uid;request.packageName=mini.pkg;
        WindowLauncherLaunch launch=adapter();set(launch,"ready",true);
        WindowLauncherLaunchTest.Starter starter=new WindowLauncherLaunchTest.Starter();
        // Global close policy is not a close request; launching the live mini must not enqueue app termination.
        set(engine,"endOnClose",true);
        starter.mDoResume=false;launch.beforeStartResult(starter,request,task.top,null,2,HOME_UID);assertEquals(5,task.mode);
        starter.mDoResume=true;launch.beforeStartResult(starter,request,task.top,null,1,HOME_UID);assertEquals(5,task.mode);
        starter.mBalVerdict.allowed=false;launch.beforeStartResult(starter,request,task.top,null,3,HOME_UID);assertEquals(5,task.mode);
        starter.mBalVerdict.allowed=true;launch.beforeStartResult(starter,request,task.top,null,2,HOME_UID);
        assertFullscreen(task);events.add("native-continuation");
        assertTrue(events.indexOf("config:42")<events.indexOf("native-continuation"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertFalse(journal.getBaseFile().exists());assertTrue(((Map<?,?>)get(engine,"pendingAppEnds")).isEmpty());
    }
    private interface NativeBody {Object run()throws Throwable;}
    private Object invokeHook(io.github.libxposed.api.XposedInterface.Hooker hook,Object owner,Object[] args,NativeBody nativeBody)throws Throwable{
        Object chain=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{io.github.libxposed.api.XposedInterface.Chain.class},(proxy,method,values)->{
            switch(method.getName()){
                case "getThisObject":return owner;
                case "getArg":return args[(Integer)values[0]];
                case "proceed":return nativeBody.run();
                default:throw new AssertionError(method);
            }
        });
        return hook.intercept((io.github.libxposed.api.XposedInterface.Chain)chain);
    }
    public static final class StartController {
        public void postStartActivityProcessingForLastStarter(Object activity,int result,Object task){}
    }
    @Test public void recentsFastPathAdapterRestoresExactMiniAfterOneNativeSuccess()throws Throwable{
        WindowLauncherLaunch launch=adapter();
        Map<String,io.github.libxposed.api.XposedInterface.Hooker> hooks=new HashMap<>();
        io.github.libxposed.api.XposedModule module=new io.github.libxposed.api.XposedModule(){};
        module.attachFramework((io.github.libxposed.api.XposedInterface)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{io.github.libxposed.api.XposedInterface.class},(proxy,method,args)->{
            if(method.getName().equals("deoptimize"))return true;
            if(!method.getName().equals("hook"))throw new AssertionError(method);
            java.lang.reflect.Executable executable=(java.lang.reflect.Executable)args[0];
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{io.github.libxposed.api.XposedInterface.HookBuilder.class},(builder,operation,values)->{
                if(operation.getName().equals("setExceptionMode"))return builder;
                if(!operation.getName().equals("intercept"))throw new AssertionError(operation);
                hooks.put(executable.getName(),(io.github.libxposed.api.XposedInterface.Hooker)values[0]);
                return new io.github.libxposed.api.XposedInterface.HookHandle(){
                    public java.lang.reflect.Executable getExecutable(){return executable;}
                    public void unhook(){hooks.remove(executable.getName());}
                };
            });
        }));
        Class<?> supervisorType=WindowLauncherLaunchTest.Supervisor.class,safeType=WindowLauncherLaunchTest.SafeOptions.class;
        assertTrue(launch.installRecents(module,supervisorType.getMethod("startActivityFromRecents",int.class,int.class,int.class,safeType),safeType.getMethod("getOptions",supervisorType),StartController.class.getMethod("postStartActivityProcessingForLastStarter",Object.class,int.class,Object.class),new ArrayList<>()));
        Object supervisor=new WindowLauncherLaunchTest.Supervisor(),safe=new WindowLauncherLaunchTest.SafeOptions();
        Task task=(Task)mini.task;int[] dispatches={0};Integer nativeResult=Integer.valueOf(2);
        Object result=invokeHook(hooks.get("startActivityFromRecents"),supervisor,new Object[]{123,HOME_UID,task.mTaskId,safe},()->{
            dispatches[0]++;assertEquals(5,task.mode);assertTrue(engine.owns(task));
            assertNull(invokeHook(hooks.get("getOptions"),safe,new Object[]{supervisor},()->null));
            assertTrue(launch.recent.get().observed);
            int[] nativePosts={0};
            assertNull(invokeHook(hooks.get("postStartActivityProcessingForLastStarter"),new StartController(),new Object[]{task.top,1,task},()->{nativePosts[0]++;return null;}));
            assertEquals(5,task.mode);assertFalse(launch.recent.get().completed);
            Object postResult=new Object();
            assertSame(postResult,invokeHook(hooks.get("postStartActivityProcessingForLastStarter"),new StartController(),new Object[]{task.top,2,task},()->{nativePosts[0]++;return postResult;}));
            assertEquals(2,nativePosts[0]);assertTrue(launch.recent.get().completed);
            assertFullscreen(task); // Already restored before native continueWindowLayout and return 2.
            return nativeResult;
        });
        assertSame(nativeResult,result);assertEquals(1,dispatches[0]);assertNull(launch.recent.get());
        assertFullscreen(task);assertFalse(journal.getBaseFile().exists());
    }
    private void assertRejected()throws Exception{
        Task task=(Task)mini.task;assertFalse(engine.launcherTaskStarted(task,HOME_UID));
        assertEquals(5,task.mode);assertEquals(0,task.configWrites);assertEquals(0,task.frontCalls);assertEquals(0,task.backCalls);
        assertFalse(mini.nativeForegroundRestore);assertTrue(journal.getBaseFile().exists());assertTrue(events.isEmpty());
    }
    @Test public void collectingWithoutPlaybackRestoresSynchronouslyBeforeNativeContinuationAndStaleTick()throws Throwable{
        Task task=(Task)mini.task;assertTrue(access.animating(task));assertFalse(access.presentationLeashed(task));
        assertSame(task,engine.launcherTaskCandidate(task.mTaskId));
        engine.configurationChanged(task); // Queue a pre-endpoint refresh without running it yet.
        assertTrue(engine.launcherTaskStarted(task,HOME_UID));assertFullscreen(task);
        events.add("native-continuation");assertTrue(events.indexOf("config:42")<events.indexOf("native-continuation"));
        assertFalse(journal.getBaseFile().exists());assertNull(get(engine,"active"));
        engine.configurationChanged(task);
        call("tick",new Class<?>[]{WindowEngine.Session.class},mini);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFullscreen(task);assertEquals(1,root.visibleCalls);assertEquals(1,root.resumeCalls);assertEquals(1,wms.traversals);
    }
    @Test public void nativeLaunchRestoresBehindRealMiniToFullscreenCoverWithoutExposingContent()throws Throwable{
        Task task=(Task)mini.task;mini.parent=task;mini.placement=new Geometry.Placement(400,700,.25f,80,90);
        mini.defaultScale=.75f;task.requested.densityDpi=480;
        set(engine,"frames",android.view.Choreographer.getInstance());
        assertTrue(engine.launcherTaskStarted(task,HOME_UID));
        assertNotNull(logs.toString(),mini.cover);assertTrue(mini.coverFullscreen);assertTrue(mini.cover.mask.holding());
        assertEquals(1,task.mode);assertEquals(1,task.configWrites);assertEquals(1,task.frontCalls);
        SurfaceShadow surface=Shadow.extract(task.mSurfaceControl);
        assertEquals(1,surface.scaleX,0);assertEquals(1,surface.scaleY,0);assertEquals(0,surface.alpha,0);
        assertEquals(new android.graphics.RectF(80,90,180,265),get(mini.cover,"from"));
        assertEquals(new android.graphics.RectF(0,0,1200,2400),get(mini.cover,"to"));
        assertEquals(0f,get(mini.cover,"radiusTo"));assertTrue((Float)get(mini.cover,"radiusFrom")>0);
        SurfaceShadow visibleCover=Shadow.extract((SurfaceControl)get(mini.cover,"root"));assertEquals(1,visibleCover.alpha,0);
        assertFalse(engine.owns(task));assertFalse(journal.getBaseFile().exists());
        assertSame(mini,((Map<?,?>)get(engine,"coverTasks")).get(task));
        mini.cover.close();mini.cover=null;
    }
    @Test public void swipePreparationPreservesLandscapeAcrossHomeRotationButNotNewRequestOrOwnership()throws Throwable{
        Task task=(Task)mini.task;task.mode=1;task.requested.orientation=Configuration.ORIENTATION_LANDSCAPE;
        set(engine,"sessions",List.of());set(engine,"active",null);
        set(engine,"uiSeen",android.os.SystemClock.elapsedRealtime());
        String token=Wire.token();Binder lease=(Binder)get(engine,"uiLease");
        boolean accepted=(Boolean)call("prepareBack",new Class<?>[]{int.class,String.class,String.class,android.graphics.RectF.class,float.class,SurfaceControl.class,boolean.class},
            task.mTaskId,mini.pkg,token,null,0f,null,true);
        assertTrue(logs.toString(),accepted);
        Object prepared=get(engine,"backPreparation");assertEquals(Boolean.TRUE,get(prepared,"landscape"));
        mini.orientationActivity=task.top;mini.orientationRequest=-1;
        task.requested.orientation=Configuration.ORIENTATION_PORTRAIT; // Native HOME can rotate before conversion.
        assertTrue((Boolean)call("conversionLandscape",new Class<?>[]{WindowEngine.Session.class,String.class,android.os.IBinder.class},mini,token,lease));
        assertFalse((Boolean)call("conversionLandscape",new Class<?>[]{WindowEngine.Session.class,String.class,android.os.IBinder.class},mini,Wire.token(),lease));
        assertFalse((Boolean)call("conversionLandscape",new Class<?>[]{WindowEngine.Session.class,String.class,android.os.IBinder.class},mini,token,new Binder()));
        mini.orientationActivity=new Top();assertFalse((Boolean)call("conversionLandscape",new Class<?>[]{WindowEngine.Session.class,String.class,android.os.IBinder.class},mini,token,lease));
        mini.orientationActivity=task.top;mini.orientationRequest=1;
        assertFalse((Boolean)call("conversionLandscape",new Class<?>[]{WindowEngine.Session.class,String.class,android.os.IBinder.class},mini,token,lease));
        call("cancelBackPreparation",new Class<?>[]{String.class},token);
        assertNull(get(engine,"backPreparation"));assertFalse(task.translucent);
    }
    @Test public void convertedLandscapeSurvivesUnchangedEntryBaselineAndHonorsNewAppRequest()throws Throwable{
        Task task=(Task)mini.task;mini.orientationActivity=task.top;mini.orientationRequest=-1;mini.orientationBaseline=true;
        mini.landscape=true;mini.placement=new Geometry.Placement(700,400,.25f,0,0);
        assertFalse((Boolean)call("updateOrientation",new Class<?>[]{WindowEngine.Session.class},mini));assertTrue(mini.landscape);
        engine.orientationRequested(task.top,1);assertFalse(mini.landscape);assertFalse(mini.orientationBaseline);
        task.top.orientationRequest=1;
        assertTrue((Boolean)call("updateOrientation",new Class<?>[]{WindowEngine.Session.class},mini));
        assertFalse(mini.landscape);
    }
    @Test public void inheritedFullscreenOriginalModeZeroAlsoRestores()throws Throwable{
        mini.mode=0;Object window=get(mini.originalConfig,"windowConfiguration");
        Method method=window.getClass().getDeclaredMethod("setWindowingMode",int.class);method.setAccessible(true);method.invoke(window,0);
        assertTrue(engine.launcherTaskStarted(mini.task,HOME_UID));assertFullscreen((Task)mini.task);
    }
    @Test public void independentPinnedMiniDoesNotReleaseAnotherNormalSession()throws Throwable{
        WindowEngine.Session normal=session(43);normal.lease=mini.lease;
        call("save",new Class<?>[]{WindowEngine.Session.class},normal);set(engine,"active",normal);set(engine,"pinned",mini);events.clear();
        assertTrue(engine.launcherTaskStarted(mini.task,HOME_UID));assertFullscreen((Task)mini.task);
        assertSame(normal,get(engine,"active"));assertNull(get(engine,"pinned"));assertTrue(engine.owns(normal.task));
        assertFalse(normal.restoring);assertEquals(0,((Task)normal.task).configWrites);assertTrue(journal.getBaseFile().exists());
    }
    @Test public void selectedLinkedMiniFinishesBeforeCollectingBackgroundCanRestore()throws Throwable{
        WindowEngine.Session background=session(41);background.token=mini.token;background.lease=mini.lease;background.parked=true;
        call("save",new Class<?>[]{WindowEngine.Session.class},background);
        set(engine,"sessions",List.of(background,mini));events.clear();
        assertTrue(engine.launcherTaskStarted(mini.task,HOME_UID));assertFullscreen((Task)mini.task);
        assertTrue(background.restoring);assertFalse(background.restoreForeground);assertFalse(engine.owns(background.task));
        assertEquals(0,((Task)background.task).configWrites);assertTrue(journal.getBaseFile().exists());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertFullscreen((Task)mini.task);assertEquals(0,((Task)background.task).configWrites);
    }
    @Test public void realLeashAndPlaybackAreNeverOverridden()throws Exception{
        Task task=(Task)mini.task;task.mSurfaceAnimator.leash=true;assertRejected();
        task.mSurfaceAnimator.leash=false;task.mTransitionController.playing=true;assertRejected();
    }
    @Test public void normalRestoringAndBusySessionsAreRejectedWithoutMutation()throws Exception{
        mini.frame="normal";assertNull(engine.launcherTaskCandidate(mini.taskId));assertRejected();mini.frame="mini";
        for(String gate:List.of("restoring","closing","fullTransition","transitioning","entering","returning","parked","prioritySuspended","screenSuspended")){
            set(mini,gate,true);assertRejected();set(mini,gate,false);
        }
        set(engine,"recovering",true);assertRejected();set(engine,"recovering",false);
        set(engine,"uiReady",false);assertRejected();set(engine,"uiReady",true);
        mini.mode=5;assertRejected();mini.mode=1;
        mini.lease=new Binder();assertRejected();
    }
    @Test public void hierarchyRotationCurrentUserAndLivePackageMustStillMatch()throws Exception{
        Task task=(Task)mini.task;
        task.attached=false;assertRejected();task.attached=true;
        task.root=false;assertRejected();task.root=true;
        task.leaf=false;assertRejected();task.leaf=true;
        task.activityType=2;assertRejected();task.activityType=1;
        task.rotation=1;assertRejected();task.rotation=0;
        root.display.mDisplayId=1;assertRejected();root.display.mDisplayId=0;
        wms.mPolicy.mCurrentUserId=10;assertRejected();wms.mPolicy.mCurrentUserId=0;
        task.top.packageName="example.other";assertRejected();task.top.packageName=mini.pkg;
        mini.uid=9999;assertRejected();
    }
    @Test public void lockedScreenFakeHomeDeadLeaseAndForeignTaskIdentityAreRejected()throws Exception{
        Task task=(Task)mini.task;
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);assertRejected();
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        assertFalse(engine.launcherTaskStarted(task,HOME_UID+1));home(false);assertRejected();home(true);
        Binder dead=new Binder(){@Override public boolean isBinderAlive(){return false;}};
        Binder original=(Binder)mini.lease;mini.lease=dead;set(engine,"uiLease",dead);assertRejected();mini.lease=original;set(engine,"uiLease",original);
        task.mUserId=10;assertRejected();task.mUserId=0;
        task.display=new Display();assertRejected();task.display=root.display;
        task.top.uid++;assertRejected();task.top.uid--;
        root.tasks.put(task.mTaskId,new Task(task.mTaskId,root.display,events));assertRejected();
        assertNull(engine.launcherTaskCandidate(task.mTaskId));assertFalse(engine.launcherTaskStarted(new Object(),HOME_UID));
    }
}
