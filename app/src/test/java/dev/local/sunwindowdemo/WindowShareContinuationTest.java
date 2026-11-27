package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.Choreographer;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Same-UID share proxy continuation through real snapshot, durable save, mask and activation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowFullscreenCoverTest.SurfaceShadow.class,
        WindowFullscreenCoverTest.BuilderShadow.class,WindowFullscreenCoverTest.TransactionShadow.class,
        WindowFullscreenCoverTest.FramesShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowShareContinuationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final List<String> logs=new ArrayList<>();
    private WindowEngine engine;
    private FrameworkAccess access;
    private Context context;
    private Root root;
    private Wms wms;
    private Atm atm;
    private Handler handler;
    private Task proxy,target;
    private Activity source,request,started;
    private WindowEngine.Session owner;
    private Binder lease;
    private AtomicFile journal;
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    public static final class Display {
        public int mDisplayId;
        final Configuration config=new Configuration();
        public Rect getBounds(){return new Rect(0,0,1200,2670);}
        public int getRotation(){return 0;}
        public void getStableRect(Rect value){value.set(getBounds());}
        public Configuration getConfiguration(){return config;}
        public Display getInsetsStateController(){return this;}
        public Display getRawInsetsState(){return this;}
        public Insets calculateInsets(Rect display,Rect bounds,int types,boolean ignore){return Insets.NONE;}
    }
    public static final class Area {
        public final SurfaceControl mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        public Rect getBounds(){return new Rect(0,0,1200,2670);}
        public boolean isValidWindowingMode(int mode,Activity activity,Task task){return mode==5;}
    }
    public static final class Controller {
        int suppressed;
        public boolean inTransition(Task task){return false;}
        public boolean inPlayingTransition(Task task){return false;}
        public void setNoAnimation(Task task){suppressed++;}
    }
    public static final class Animator {boolean leash;public boolean hasLeash(){return leash;}}
    public static final class Task {
        public int mTaskId,mUserId;
        public final Point mLastSurfaceSize=new Point(1200,2133);
        public Rect mLastNonFullscreenBounds;
        public final ComponentName realActivity=new ComponentName("example.same","example.same.Main");
        public final SurfaceControl mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        public final Controller mTransitionController=new Controller();
        public final Animator mSurfaceAnimator=new Animator();
        final Configuration config=new Configuration();
        final SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        final Area area=new Area();
        Display display;
        Activity top;
        int mode=5,modeWrites,boundsWrites,frontCalls;
        public void moveToFront(String reason){frontCalls++;}
        boolean attached=true,focusable=true,alwaysTop=true;
        Task(int id,Display display){mTaskId=id;this.display=display;}
        public boolean isForceOpaque(){return false;}
        public boolean isRootTask(){return true;}
        public boolean isLeafTask(){return true;}
        public boolean isAttached(){return attached;}
        public boolean isVisibleRequested(){return true;}
        public int getActivityType(){return 1;}
        public int getRelativeDisplayRotation(){return 0;}
        public Area getParent(){return area;}
        public Area getDisplayArea(){return area;}
        public Display getDisplayContent(){return display;}
        public Activity topRunningActivity(){return top;}
        public Intent getBaseIntent(){return new Intent().setIdentifier("page"+mTaskId);}
        public boolean supportsFreeformInDisplayArea(Area area){return true;}
        public boolean isOverrideBoundsAllowed(){return true;}
        public boolean isOverrideWindowingModeAllowed(){return true;}
        public boolean isAlwaysOnTop(){return alwaysTop;}
        public boolean isFocusable(){return focusable;}
        public boolean isForceTranslucent(){return false;}
        public int getWindowingMode(){return mode;}
        public Configuration getRequestedOverrideConfiguration(){return config;}
        public Configuration getConfiguration(){return config;}
        public SurfaceControl.Transaction getPendingTransaction(){return transaction;}
        public void setFocusable(boolean value){focusable=value;}
        public void setAlwaysOnTop(boolean value){alwaysTop=value;}
        public void setForceTranslucent(boolean value){}
        public void setWindowingMode(int value){mode=value;modeWrites++;}
        public void setBounds(Rect value){boundsWrites++;}
    }
    public static final class Activity {
        public String packageName="example.same",launchedFromPackage="example.same";
        public int launchedFromUid=10123;
        public final ActivityInfo info=new ActivityInfo();
        public Intent intent=new Intent(Intent.ACTION_VIEW);
        Task task;
        int uid=10123;
        boolean resolver;
        Activity(Task task){this.task=task;info.packageName=packageName;info.name="example.same.Main";}
        public Task getTask(){return task;}
        public int getUid(){return uid;}
        public Display getDisplayContent(){return task==null?null:task.display;}
        public boolean isResolverOrDelegateActivity(){return resolver;}
        public int getRequestedOrientation(){return -1;}
        public Object findMainWindow(boolean starting){return null;}
    }
    public static final class PriorityWindow {
        public Activity mActivityRecord;
        final Display display;
        int owningUid=1000;
        PriorityWindow(Display display,boolean resolver){this.display=display;mActivityRecord=new Activity(null);mActivityRecord.resolver=resolver;mActivityRecord.uid=1000;}
        public Object getTask(){return null;}
        public Display getDisplayContent(){return display;}
        public int getOwningUid(){return owningUid;}
        public boolean isVisible(){return true;}
    }
    public static final class Policy {public int mCurrentUserId;}
    public static final class Wms {
        public final Policy mPolicy=new Policy();
        public final List<Object> overlayHidingWindows=new ArrayList<>();
        int traversals;
        public void requestTraversal(){traversals++;}
    }
    public static final class Root {
        final Display display=new Display();
        final HashMap<Integer,Task> tasks=new HashMap<>();
        int visibleCalls,resumeCalls;
        public void ensureActivitiesVisible(){visibleCalls++;}
        public void resumeFocusedTasksTopActivities(){resumeCalls++;}
        public Task lookup(int id,int mode){return tasks.get(id);}
        public Display getDefaultDisplay(){return display;}
    }
    public static final class Atm {
        public boolean mSupportsMultiWindow=true,mSupportsFreeformWindowManagement=true;
        int deferred,continued;
        public void deferWindowLayout(){deferred++;}
        public void continueWindowLayout(){continued++;}
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();handler=new Handler(Looper.getMainLooper());
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());set(access,"wc",Task.class);
        set(access,"task",Task.class);set(access,"activity",Activity.class);set(access,"tda",Area.class);
        set(access,"lookup",Root.class.getMethod("lookup",int.class,int.class));
        set(access,"playingTransition",Controller.class.getMethod("inPlayingTransition",Task.class));
        set(access,"overlayHidingWindows",Wms.class.getField("overlayHidingWindows"));
        root=new Root();wms=new Wms();atm=new Atm();root.display.config.densityDpi=480;
        engine=new WindowEngine(access,logs::add);lease=new Binder();
        set(engine,"context",context);set(engine,"handler",handler);set(engine,"lock",new Object());set(engine,"root",root);
        set(engine,"wms",wms);set(engine,"atm",atm);set(engine,"uiLease",lease);set(engine,"uiReady",true);
        set(engine,"frames",Shadow.newInstanceOf(Choreographer.class));
        journal=new AtomicFile(temporary.newFile("recovery.json"));journal.delete();set(engine,"journal",journal);
        set(engine,"historyFile",new AtomicFile(temporary.newFile("history.json")));
        proxy=new Task(1130,root.display);target=new Task(1116,root.display);
        target.alwaysTop=false; // Native inherited mode 5, not an already module-owned topmost task.
        root.tasks.put(proxy.mTaskId,proxy);root.tasks.put(target.mTaskId,target);
        source=new Activity(proxy);proxy.top=source;request=new Activity(target);started=new Activity(target);target.top=started;
        owner=new WindowEngine.Session();owner.task=proxy;owner.taskId=proxy.mTaskId;owner.uid=10123;owner.pkg="example.same";
        owner.token=Wire.token();owner.component="example.same/.Main";owner.crop=new Point(1200,2133);owner.bounds=new Rect();owner.mode=1;
        owner.lease=lease;owner.launchFlow=new LaunchCoverFlow(SystemClock.elapsedRealtime());owner.placement=new Geometry.Placement(1200,2133,.75f,150,500);
        owner.prioritySuspended=true;owner.priorityHideTask=true;
        set(engine,"sessions",List.of(owner));set(engine,"active",owner);
        wms.overlayHidingWindows.add(new PriorityWindow(root.display,true));
    }
    @After @SuppressWarnings("unchecked") public void cleanup()throws Exception{
        handler.removeCallbacksAndMessages(null);
        for(WindowEngine.Session s:(List<WindowEngine.Session>)get(engine,"sessions"))if(s.cover!=null)s.cover.close();
    }
    private boolean continuing()throws Exception{return engine.continuingShare(owner,source,request,10123);}
    private void rejected()throws Exception{
        assertNull(engine.activityStarting(source,request,started,0,10123));assertSame(owner,get(engine,"active"));
        assertFalse(engine.owns(target));assertEquals(0,target.modeWrites);assertEquals(0,target.boundsWrites);
    }
    @Test public void resolverSameUidHopAcquiresExistingInheritedTaskThroughRealSnapshotAndActivation()throws Exception{
        assertTrue(continuing());assertTrue(engine.continuationPriorityAllowed(owner));
        Object ticket=engine.activityStarting(source,request,started,0,10123);assertNotNull(logs.toString(),ticket);
        WindowEngine.Session next=(WindowEngine.Session)get(engine,"active");assertSame(target,next.task);assertNotSame(owner,next);
        assertTrue(engine.owns(target));assertTrue(owner.parked);assertSame(owner,next.returnTo);assertSame(owner.launchFlow,next.launchFlow);
        assertEquals(5,target.mode);assertEquals(1,target.modeWrites);assertEquals(1,target.boundsWrites);assertEquals(1,target.mTransitionController.suppressed);
        assertEquals(1,next.mode);assertTrue(next.bounds.isEmpty());assertEquals(2,next.topState);assertNotNull(next.originalConfig);
        assertSame(lease,next.lease);assertNotNull(logs.toString(),next.cover);assertTrue(next.cover.mask.holding());assertNull(next.startMask);
        assertFalse(logs.toString(),logs.stream().anyMatch(line->line.startsWith("COVER_SKIPPED")));assertTrue(journal.getBaseFile().exists());
        assertEquals(atm.deferred,atm.continued);assertEquals(1,wms.traversals);
    }
    private void postedSuccessStaysBehindPriority(boolean authentication)throws Exception{
        Object ticket=engine.activityStarting(source,request,started,0,10123);assertNotNull(logs.toString(),ticket);
        WindowEngine.Session next=(WindowEngine.Session)get(engine,"active");
        if(authentication){wms.overlayHidingWindows.clear();wms.overlayHidingWindows.add(new PriorityWindow(root.display,false));}
        // Native Shell can own a leash while the one post-result callback observes priority.
        target.mSurfaceAnimator.leash=true;engine.activityStartResult(ticket,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(logs.toString(),0,target.frontCalls);assertEquals(0,root.visibleCalls);assertEquals(0,root.resumeCalls);
        assertFalse(logs.toString(),next.restoring);assertTrue(engine.owns(target));assertNull(get(engine,"pendingStart"));
    }
    @Test public void postedNativeSuccessDoesNotStealFocusFromFadingChooser()throws Exception{postedSuccessStaysBehindPriority(false);}
    @Test public void newAuthenticationWinsBeforePostedNativeSuccess()throws Exception{postedSuccessStaysBehindPriority(true);}
    @Test public void expiredFinishedAndAbsentFlowNeverCreatePriorityException()throws Exception{
        owner.launchFlow=null;assertFalse(continuing());rejected();
        owner.launchFlow=new LaunchCoverFlow(SystemClock.elapsedRealtime()-LaunchCoverFlow.TIMEOUT_MS);assertFalse(continuing());rejected();
        owner.launchFlow=new LaunchCoverFlow(SystemClock.elapsedRealtime());owner.launchFlow.finish();assertFalse(continuing());rejected();
    }
    @Test public void authenticationOrOtherPriorityCannotUseResolverException()throws Exception{
        ((PriorityWindow)wms.overlayHidingWindows.get(0)).mActivityRecord.resolver=false;
        assertFalse(engine.continuationPriorityAllowed(owner));rejected();
    }
    @Test public void unavailablePriorityAccessorCannotClearCachedSuspension()throws Exception{
        assertTrue(owner.prioritySuspended);assertTrue(continuing());
        set(access,"overlayHidingWindows",null);assertFalse(engine.continuationPriorityAllowed(owner));rejected();
        set(access,"overlayHidingWindows",Wms.class.getField("overlayHidingWindows"));
        set(engine,"wms",null);assertFalse(engine.continuationPriorityAllowed(owner));rejected();
        assertFalse(journal.getBaseFile().exists());
    }
    @Test public void apparentResolverRequiresRealSystemUidButKnownClearedPriorityIsSafe()throws Exception{
        PriorityWindow chooser=(PriorityWindow)wms.overlayHidingWindows.get(0);
        chooser.mActivityRecord.uid=2000;assertFalse(engine.continuationPriorityAllowed(owner));rejected();
        chooser.mActivityRecord.uid=1000;wms.overlayHidingWindows.clear();assertTrue(engine.continuationPriorityAllowed(owner));
    }
    @Test public void oneValidResolverCannotHideConcurrentAuthenticationOrUnknownActivity()throws Exception{
        PriorityWindow other=new PriorityWindow(root.display,false);wms.overlayHidingWindows.add(other);
        assertFalse(engine.continuationPriorityAllowed(owner));rejected();
        other.mActivityRecord=null;assertFalse(engine.continuationPriorityAllowed(owner));rejected();
    }
    @Test public void validResolverCannotHideVisibleSameDisplayWindowWithUnknownUid()throws Exception{
        PriorityWindow unknown=new PriorityWindow(root.display,false);unknown.owningUid=-1;unknown.mActivityRecord=null;
        wms.overlayHidingWindows.add(unknown);
        assertFalse(engine.continuationPriorityAllowed(owner));rejected();
        wms.overlayHidingWindows.clear();wms.overlayHidingWindows.add(unknown);
        assertFalse(engine.continuationPriorityAllowed(owner));rejected();
    }
    @Test public void resolverSystemAppIdFromAnotherUserNeverAuthorizesCurrentShare()throws Exception{
        PriorityWindow chooser=(PriorityWindow)wms.overlayHidingWindows.get(0);
        chooser.owningUid=101000;chooser.mActivityRecord.uid=101000;
        assertFalse(engine.continuationPriorityAllowed(owner));rejected();
    }
    @Test public void continuationRequiresExactLiveTaskCallerPackageUidUserDisplayAndLease()throws Exception{
        source.task=target;assertFalse(continuing());source.task=proxy;
        request.uid++;assertFalse(continuing());request.uid--;
        request.packageName="example.other";assertFalse(continuing());rejected();request.packageName=owner.pkg;
        source.uid++;assertFalse(continuing());source.uid--;
        request.launchedFromUid++;assertFalse(continuing());request.launchedFromUid--;
        assertFalse(engine.continuingShare(owner,source,request,10124));
        request.launchedFromPackage="example.other";assertFalse(continuing());request.launchedFromPackage=owner.pkg;
        proxy.mUserId=10;assertFalse(continuing());proxy.mUserId=0;
        proxy.display=new Display();assertFalse(continuing());proxy.display=root.display;
        root.tasks.put(proxy.mTaskId,target);assertFalse(continuing());root.tasks.put(proxy.mTaskId,proxy);
        owner.lease=new Binder();assertFalse(continuing());owner.lease=lease;
        Binder dead=new Binder(){@Override public boolean isBinderAlive(){return false;}};
        owner.lease=dead;set(engine,"uiLease",dead);assertFalse(continuing());
        assertEquals(0,target.modeWrites);assertEquals(0,target.boundsWrites);assertFalse(journal.getBaseFile().exists());
    }
    @Test public void sameTaskOnNewIntentKeepsOneSessionWithoutSnapshotOrNewOwnership()throws Exception{
        request.task=proxy;started.task=proxy;Object ticket=engine.activityStarting(source,request,started,0,10123);
        assertNotNull(logs.toString(),ticket);assertSame(owner,get(engine,"active"));assertEquals(1,((List<?>)get(engine,"sessions")).size());
        assertEquals(0,target.modeWrites);assertFalse(engine.owns(target));assertFalse(journal.getBaseFile().exists());
    }
}
