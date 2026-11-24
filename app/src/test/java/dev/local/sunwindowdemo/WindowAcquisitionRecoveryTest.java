package dev.local.sunwindowdemo;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Executes real posted acquisition callbacks with native operation failures, not a policy surrogate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=WindowAcquisitionRecoveryTest.TransactionShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowAcquisitionRecoveryTest {
    public static final class Animator { public boolean hasLeash(){return true;} }
    public static final class Task {
        public final int mTaskId,mUserId=0;
        public final Animator mSurfaceAnimator=new Animator();
        public final SurfaceControl mSurfaceControl=null;
        boolean focusable,failFront,failFocus;
        int frontCalls,focusCalls;
        Task(int id){mTaskId=id;}
        // Detached surfaces bypass rendering; a native animation keeps durable restore pending.
        public boolean isAttached(){return false;}
        public Object topRunningActivity(){return this;}
        public void setFocusable(boolean value){focusCalls++;if(failFocus)throw new IllegalStateException("injected rollback focus failure");focusable=value;}
        public void moveToFront(String reason){frontCalls++;if(failFront)throw new IllegalStateException("injected destination front failure");}
    }
    public static final class Root {
        final Map<Integer,Task> tasks=new HashMap<>();
        boolean failVisibility,failLookup;
        public Object anyTaskForId(int id,int mode){if(failLookup)throw new IllegalStateException("injected settle lookup failure");return tasks.get(id);}
        public void ensureActivitiesVisible(){if(failVisibility)throw new IllegalStateException("injected visible failure");}
        public void resumeFocusedTasksTopActivities(){}
    }
    public static final class Atm {
        public void deferWindowLayout(){throw new IllegalStateException("injected activate after-owner-change failure");}
    }
    private WindowEngine engine;
    private Root root;
    private WindowEngine.Session previous,target,mini;
    private Handler handler;
    private final List<String> logs=new ArrayList<>();
    private static void set(Object object,String name,Object value)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static Object get(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    @Before public void setup()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));
        engine=new WindowEngine(access,logs::add);root=new Root();handler=new Handler(Looper.getMainLooper());
        set(engine,"root",root);set(engine,"handler",handler);set(engine,"lock",new Object());set(engine,"openGeneration",7L);
        previous=session(1);target=session(2);mini=session(3);mini.frame="mini";
        // State immediately after activate(B) parked and disabled A, with independent mini M untouched.
        previous.parked=true;previous.opacity=0;
        previous.prioritySuspended=true;target.prioritySuspended=true; // Avoid irrelevant focus/IME sampling.
        set(engine,"atm",new Atm());
        set(engine,"sessions",List.of(previous,target,mini));set(engine,"active",target);set(engine,"pinned",mini);
    }
    private WindowEngine.Session session(int id){
        WindowEngine.Session s=new WindowEngine.Session();s.taskId=id;s.task=new Task(id);root.tasks.put(id,(Task)s.task);
        s.pkg="example.app"+id;s.token=Wire.token();s.component=s.pkg+"/.Main";s.lease=new Binder();
        return s;
    }
    @After public void cleanup(){handler.removeCallbacksAndMessages(null);}
    private Object ticket(String name,Object...args)throws Exception{
        Class<?> type=Class.forName(WindowEngine.class.getName()+"$"+name);
        Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);return constructor.newInstance(args);
    }
    private void share()throws Exception{
        engine.activityStartResult(ticket("ShareStarted",target,previous,previous,mini),true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void assertRestoredSource()throws Exception{
        assertSame(previous,get(engine,"active"));assertSame(mini,get(engine,"pinned"));
        assertFalse(previous.parked);assertEquals(1,previous.opacity,0);assertTrue(((Task)previous.task).focusable);
        assertTrue(target.restoring);assertTrue(target.restoreForeground);assertFalse(target.parked);
        assertFalse(previous.restoring);assertFalse(mini.restoring);
        assertTrue(logs.stream().anyMatch(s->s.startsWith("SHARE_WINDOW_PRESENT_FAILED")||s.startsWith("LINKED_PRESENT_FAILED")));
    }
    @Test public void shareFrontFailureRestoresParkedSourceAndPinnedBeforeDestinationFallback()throws Exception{
        ((Task)target.task).failFront=true;share();assertRestoredSource();
        assertEquals(1,((Task)previous.task).frontCalls);assertEquals(1,((Task)previous.task).focusCalls);
    }
    @Test public void shareVisibilityFailureAlsoRestoresSourceEvenIfRollbackVisibilityFails()throws Exception{
        root.failVisibility=true;share();
        assertTrue("a second native rollback failure must enter existing durable recovery",(Boolean)get(engine,"recovering"));
        assertTrue(target.restoreForeground);assertTrue(previous.restoring);assertTrue(mini.restoring);
        assertTrue(logs.stream().anyMatch(s->s.startsWith("ACQUISITION_ROLLBACK_FAILED")));
    }
    @Test public void rollbackFailureStillStartsDestinationFallbackAndRecoversAllHiddenSessions()throws Exception{
        ((Task)target.task).failFront=true;((Task)previous.task).failFocus=true;share();
        assertTrue(target.restoreForeground);assertTrue(target.restoring);assertTrue(previous.restoring);assertTrue(mini.restoring);
        assertTrue((Boolean)get(engine,"recovering"));assertNull(get(engine,"active"));
    }
    @Test public void fallbackFailureAfterSuccessfulRollbackStillEntersDurableRecovery()throws Exception{
        ((Task)target.task).failFront=true;set(engine,"coverTasks",null);share();
        assertTrue(target.restoreForeground);assertTrue(previous.restoring);assertTrue(target.restoring);
        assertTrue((Boolean)get(engine,"recovering"));
        assertTrue(logs.stream().anyMatch(s->s.startsWith("ACQUISITION_FALLBACK_FAILED")));
    }
    @Test public void staleShareResultDoesNotStealANewerOwnerOrRestoreItsTarget()throws Exception{
        Object ticket=ticket("ShareStarted",target,previous,previous,mini);
        set(engine,"active",mini);engine.activityStartResult(ticket,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(mini,get(engine,"active"));assertFalse(target.restoring);assertTrue(previous.parked);
        assertEquals(0,((Task)target.task).frontCalls);assertEquals(0,((Task)previous.task).focusCalls);
    }
    @Test public void removedOrRestoringTargetIgnoresPostedShareResult()throws Exception{
        Object ticket=ticket("ShareStarted",target,previous,previous,mini);
        set(engine,"sessions",List.of(previous,mini));engine.activityStartResult(ticket,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        set(engine,"sessions",List.of(previous,target,mini));target.restoring=true;
        engine.activityStartResult(ticket,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,((Task)target.task).frontCalls);assertEquals(0,((Task)previous.task).focusCalls);
    }
    @Test public void alreadyRetiredPreviousIsNotResurrectedOnDestinationFailure()throws Exception{
        previous.restoring=true;((Task)target.task).failFront=true;share();
        assertTrue(previous.restoring);assertTrue(previous.parked);assertEquals(0,((Task)previous.task).focusCalls);
        assertTrue(target.restoring);assertNull(get(engine,"active"));
    }
    @Test public void failureAfterSettlementDoesNotPromoteTheRetainedMiniBackToDefault()throws Exception{
        previous.frame="mini";set(engine,"history",null);share(); // Failure after settle retained A as mini.
        assertSame(previous,get(engine,"pinned"));assertNull(get(engine,"active"));
        assertFalse(previous.parked);assertEquals("mini",previous.frame);assertFalse(previous.restoring);
        assertEquals(0,((Task)previous.task).focusCalls);assertTrue(target.restoreForeground);
    }
    @Test public void failureDuringSettlementDoesNotBlindlyRollbackButRecoversStillParkedSource()throws Exception{
        root.failLookup=true;share();
        assertTrue(target.restoreForeground);assertTrue(previous.restoring);
        assertTrue(logs.stream().anyMatch(s->s.startsWith("RECOVERY_FAILED")));
        assertEquals(0,((Task)previous.task).focusCalls);
    }
    @Test public void linkedPresentationFailureUsesTheSameSourceRollback()throws Exception{
        Object ticket=ticket("SwitchStart",previous,target,true,7L);set(engine,"pendingStart",ticket);
        ((Task)target.task).failFront=true;engine.activityStartResult(ticket,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertRestoredSource();assertNull(get(engine,"pendingStart"));
    }
    @Test public void unsuccessfulShareStillFallsBackWhenRollbackChangesOwnerThenThrows()throws Exception{
        ((Task)previous.task).failFocus=true;
        engine.activityStartResult(ticket("ShareStarted",target,previous,previous,mini),false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(target.restoreForeground);assertTrue(target.restoring);assertTrue(previous.restoring);
        assertTrue((Boolean)get(engine,"recovering"));
    }
    @Test public void unsuccessfulLinkedResultFallsBackAfterActivateSelectedSourceThenThrows()throws Exception{
        set(engine,"context",RuntimeEnvironment.getApplication());
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.os.PowerManager.class)).setIsInteractive(true);
        target.parked=true; // Skip cover creation; inject at deferWindowLayout after active=source.
        Object ticket=ticket("SwitchStart",previous,target,true,7L);set(engine,"pendingStart",ticket);
        engine.activityStartResult(ticket,false);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(previous,get(engine,"active"));assertFalse(previous.parked);assertTrue(((Task)previous.task).focusable);
        assertTrue(target.restoreForeground);assertTrue(target.restoring);
        assertTrue(logs.toString(),logs.stream().anyMatch(s->s.contains("injected activate after-owner-change failure")));
    }
    @Test public void obsoleteLinkedTicketCannotRollbackTheCurrentOwner()throws Exception{
        Object old=ticket("SwitchStart",previous,target,true,7L),current=ticket("SwitchStart",previous,mini,true,7L);
        set(engine,"pendingStart",current);set(engine,"active",mini);
        engine.activityStartResult(old,true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(current,get(engine,"pendingStart"));assertSame(mini,get(engine,"active"));assertFalse(target.restoring);
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @Implementation protected void __constructor__(){}
        @Implementation protected void apply(){}
        @Implementation protected void close(){}
    }
}
