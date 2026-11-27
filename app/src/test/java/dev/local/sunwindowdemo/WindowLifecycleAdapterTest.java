package dev.local.sunwindowdemo;

import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowLifecycleAdapterTest {
    static final Object LOCK=new Object();
    static final List<String> CALLS=new ArrayList<>();
    public static final class Task {
        boolean rejectRecovery;final Display display=new Display();
        public Display getDisplayContent(){assertTrue(Thread.holdsLock(LOCK));return display;}
        public boolean isAttached(){assertTrue(Thread.holdsLock(LOCK));CALLS.add("attached");if(rejectRecovery)throw new IllegalStateException("native recovery unavailable");return false;}
        public Object topRunningActivity(){assertTrue(Thread.holdsLock(LOCK));CALLS.add("top");return new Object();}
    }
    public static final class Policy {
        public int mCurrentUserId;
        public boolean isKeyguardShowingAndNotOccluded(){assertTrue(Thread.holdsLock(LOCK));return true;}
    }
    public static final class Display {
        public Window mNotificationShade;
        public Display getDisplayPolicy(){assertTrue(Thread.holdsLock(LOCK));return this;}
    }
    public static final class Window {
        final Display display;public final android.view.WindowManager.LayoutParams mAttrs=new android.view.WindowManager.LayoutParams();
        Window(Display display)throws Exception{this.display=display;mAttrs.type=2040;set(mAttrs,"privateFlags",0x80000);}
        public Object getTask(){assertTrue(Thread.holdsLock(LOCK));return null;}
        public Display getDisplayContent(){assertTrue(Thread.holdsLock(LOCK));return display;}
        public int getOwningUid(){assertTrue(Thread.holdsLock(LOCK));return 10170;}
        public boolean isVisible(){assertTrue(Thread.holdsLock(LOCK));return true;}
        public boolean hideNonSystemOverlayWindowsWhenVisible(){assertTrue(Thread.holdsLock(LOCK));return true;}
    }
    public static final class Wms {
        public final Policy mPolicy=new Policy();
        public final ArrayList<Window> mHidingNonSystemOverlayWindows=new ArrayList<>();
    }
    public static final class Root {
        final Object task;Root(Object task){this.task=task;}
        public Object anyTaskForId(int id,int mode){assertTrue(Thread.holdsLock(LOCK));CALLS.add("find");return task;}
    }
    public static final class Lease extends Binder {
        int reads;boolean alive=true;
        @Override public boolean isBinderAlive(){reads++;CALLS.add("lease");return alive;}
    }
    public static final class RecordingHandler extends Handler {
        final List<Long> delays=new ArrayList<>();
        RecordingHandler(){super(Looper.getMainLooper());}
        @Override public boolean sendMessageAtTime(Message msg,long uptimeMillis){delays.add(uptimeMillis-SystemClock.uptimeMillis());return true;}
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type);
    }
    private static FrameworkAccess access()throws Exception{
        FrameworkAccess a=(FrameworkAccess)allocate(FrameworkAccess.class);
        set(a,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(a,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        set(a,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));return a;
    }
    private static Object call(WindowEngine engine,String method,WindowEngine.Session session)throws Exception{
        Method m=WindowEngine.class.getDeclaredMethod(method,WindowEngine.Session.class);m.setAccessible(true);return m.invoke(engine,session);
    }
    @Test public void hiddenTickChecksIdentityThenTopThenAppliesUnderLockWithoutReadingLease()throws Exception{
        CALLS.clear();Task task=new Task();Lease lease=new Lease();RecordingHandler handler=new RecordingHandler();
        WindowEngine engine=new WindowEngine(access(),message->{});WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.taskId=7;s.lease=lease;
        set(engine,"root",new Root(task));set(engine,"lock",LOCK);set(engine,"handler",handler);set(engine,"sessions",List.of(s));
        call(engine,"tick",s);
        assertEquals(List.of("find","top","attached"),CALLS);assertEquals(0,lease.reads);assertEquals(List.of(1000L),handler.delays);
        assertFalse(s.restoring);assertSame(task,s.task);assertFalse(Thread.holdsLock(LOCK));
    }
    @Test public void visibleDeadLeaseDoesNotQueryTaskAndKeepsRecoveryPath()throws Exception{
        CALLS.clear();Task task=new Task();Lease lease=new Lease();lease.alive=false;
        WindowEngine engine=new WindowEngine(access(),message->{});WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.taskId=7;s.lease=lease;
        set(engine,"root",new Root(task));set(engine,"lock",LOCK);set(engine,"active",s);set(engine,"sessions",List.of(s));
        task.rejectRecovery=true;set(engine,"handler",new RecordingHandler());
        call(engine,"tick",s);
        assertFalse(CALLS.contains("find"));assertFalse(CALLS.contains("top"));assertEquals("lease",CALLS.get(0));
        assertEquals(1,lease.reads);assertTrue(s.closing);assertTrue(s.restoring);assertTrue(CALLS.contains("attached"));
    }
    @Test public void continuationDoesNotReadLeaseUntilExactHiddenMiniAndAppUidEligibility()throws Exception{
        CALLS.clear();Lease lease=new Lease();WindowEngine engine=new WindowEngine(access(),message->{});WindowEngine.Session s=new WindowEngine.Session();
        s.frame="mini";s.edgeHidden=true;s.uid=9999;s.lease=lease;s.task=new Task();
        set(engine,"active",s);set(engine,"sessions",List.of(s));set(engine,"lock",LOCK);
        set(engine,"continuationReady",true);set(engine,"keepEdgeAwake",true);set(engine,"continuation",allocate(WindowContinuation.class));
        assertEquals(false,call(engine,"continueHidden",s));assertEquals(0,lease.reads);
        s.uid=10000;assertEquals(true,call(engine,"continueHidden",s));assertEquals(1,lease.reads);
        s.prioritySuspended=true;set(s,"continuationPrioritySuspended",true);
        // A stale continuation denial must not prevent a fresh native sample from clearing it.
        assertEquals(true,call(engine,"continueHidden",s));assertEquals(2,lease.reads);
        assertFalse(s.continuationPrioritySuspended);
        assertTrue(s.prioritySuspended);s.edgeHidden=false;assertEquals(false,call(engine,"continueHidden",s));assertEquals(2,lease.reads);
        s.edgeHidden=true;lease.alive=false;assertEquals(false,call(engine,"continueHidden",s));assertEquals(3,lease.reads);
        assertFalse(s.restoring);assertTrue(s.edgeHidden);
    }
    public static final class Fragment {
        final Object task;boolean sleeping;int topReads,visibilityReads;Top top;
        Fragment(Object task){this.task=task;}
        public Object getTask(){return task;}
        public boolean shouldSleepOrShutDownActivities(){assertTrue(Thread.holdsLock(LOCK));return sleeping;}
        public Top topRunningActivity(){assertTrue(Thread.holdsLock(LOCK));topReads++;return top;}
        public int getVisibility(Object resuming){assertTrue(Thread.holdsLock(LOCK));visibilityReads++;return 0;}
    }
    public static final class Top {
        public boolean finishing;boolean visible=true,attached=true;int visibleReads,attachedReads;
        public boolean isVisibleRequested(){visibleReads++;return visible;}
        public boolean attachedToProcess(){attachedReads++;return attached;}
    }
    @Test public void shellContinuationRetainsNativeResumeGatesWithoutReenablingPresentation()throws Exception{
        FrameworkAccess a=access();set(a,"activity",Object.class);Task task=new Task();Fragment fragment=new Fragment(task);fragment.top=new Top();Lease lease=new Lease();
        WindowEngine engine=new WindowEngine(a,message->{});WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.lease=lease;
        s.frame="mini";s.edgeHidden=true;s.uid=10000;s.prioritySuspended=true;s.screenSuspended=true;
        set(engine,"active",s);set(engine,"sessions",List.of(s));set(engine,"lock",LOCK);
        Window shell=new Window(task.display);task.display.mNotificationShade=shell;Wms wms=new Wms();wms.mHidingNonSystemOverlayWindows.add(shell);
        a.overlayHidingWindows=Wms.class.getField("mHidingNonSystemOverlayWindows");set(engine,"wms",wms);set(engine,"uiUid",10170);
        set(engine,"context",org.robolectric.RuntimeEnvironment.getApplication());set(engine,"continuationReady",true);
        set(engine,"keepEdgeAwake",true);set(engine,"continuation",allocate(WindowContinuation.class));
        assertTrue(engine.keepVisibleResumed(fragment,null));assertTrue(s.prioritySuspended);assertTrue(s.screenSuspended);
        // Same WindowState and same native list: security changes must revoke a previously cached shell exemption synchronously.
        for(int sensitive:new int[]{0x2000,8}){
            set(s,"continuationPrioritySuspended",false);shell.mAttrs.flags=sensitive==0x2000?0x2000:0;
            set(shell.mAttrs,"inputFeatures",sensitive==8?8:0);int leaseReads=lease.reads;
            assertEquals(false,call(engine,"continueHidden",s));assertEquals(leaseReads,lease.reads);
            assertTrue(s.continuationPrioritySuspended);assertFalse(engine.keepVisibleResumed(fragment,null));
        }
        shell.mAttrs.flags=0;set(shell.mAttrs,"inputFeatures",0);set(s,"continuationPrioritySuspended",false);
        assertTrue(engine.keepVisibleResumed(fragment,null));
        s.edgeHidden=false;assertFalse(engine.keepVisibleResumed(fragment,null));
        s.edgeHidden=true;s.frame="normal";assertFalse(engine.keepVisibleResumed(fragment,null));
        s.frame="mini";lease.alive=false;assertFalse(engine.keepVisibleResumed(fragment,null));lease.alive=true;
        fragment.sleeping=true;assertFalse(engine.keepVisibleResumed(fragment,null));fragment.sleeping=false;
        fragment.top.finishing=true;assertFalse(engine.keepVisibleResumed(fragment,null));
        assertTrue(s.prioritySuspended);assertTrue(s.screenSuspended);assertFalse(s.restoring);
    }
    @Test public void visibleResumeKeepsSleepAndFinishingShortCircuitBeforeLaterNativeQueries()throws Exception{
        FrameworkAccess a=access();set(a,"activity",Object.class);Task task=new Task();Fragment fragment=new Fragment(task);fragment.top=new Top();Lease lease=new Lease();
        WindowEngine engine=new WindowEngine(a,message->{});WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.lease=lease;
        set(engine,"active",s);set(engine,"sessions",List.of(s));set(engine,"lock",LOCK);set(engine,"context",org.robolectric.RuntimeEnvironment.getApplication());
        fragment.sleeping=true;assertFalse(engine.keepVisibleResumed(fragment,null));assertEquals(0,fragment.topReads);
        fragment.sleeping=false;fragment.top.finishing=true;assertFalse(engine.keepVisibleResumed(fragment,null));assertEquals(0,fragment.top.visibleReads);assertEquals(0,fragment.visibilityReads);
        fragment.top.finishing=false;fragment.top.visible=false;assertFalse(engine.keepVisibleResumed(fragment,null));assertEquals(0,fragment.top.attachedReads);
        fragment.top.visible=true;fragment.top.attached=false;assertFalse(engine.keepVisibleResumed(fragment,null));assertEquals(0,fragment.visibilityReads);
        fragment.top.attached=true;assertTrue(engine.keepVisibleResumed(fragment,null));assertEquals(1,fragment.visibilityReads);
    }
}
