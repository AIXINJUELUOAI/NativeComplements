package dev.local.sunwindowdemo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class TaskCleanupGuardTest {
    @Test public void visibilityResetsTheFullGracePeriod(){
        var state=new TaskCleanupGuard.State(10042);
        assertTrue(state.protectedAt(10));
        state.observe(false,1000);
        assertTrue(state.protectedAt(1000+TaskCleanupGuard.GRACE_MS-1));
        assertFalse(state.protectedAt(1000+TaskCleanupGuard.GRACE_MS));
        state.observe(true,400_000);
        assertTrue(state.protectedAt(900_000));
        state.observe(false,1_000_000);
        assertTrue(state.protectedAt(1_000_000+TaskCleanupGuard.GRACE_MS-1));
        assertFalse(state.protectedAt(1_000_000+TaskCleanupGuard.GRACE_MS));
    }

    @Test public void repeatedBackgroundObservationsDoNotExtendTheDeadline(){
        var state=new TaskCleanupGuard.State(10042);
        state.observe(false,1000);
        state.observe(false,200_000);
        assertEquals(1000,state.absentSince);
        assertFalse(state.protectedAt(301_000));
    }

    @Test public void retainedSideHiddenSessionKeepsProtectionWithoutADeadline(){
        var state=new TaskCleanupGuard.State(10042);
        state.observe(false,1000);
        state.observe(true,2000);
        assertEquals(-1,state.absentSince);
        assertTrue(state.protectedAt(10_000_000));
    }

    @Test public void userAndPackageIdentitiesStayIndependent(){
        assertNotEquals(new TaskCleanupGuard.Key("a.app",0),new TaskCleanupGuard.Key("a.app",10));
        assertNotEquals(new TaskCleanupGuard.Key("a.app",0),new TaskCleanupGuard.Key("b.app",0));
    }

    @Test public void explicitBinderAndWindowCloseRequestsRemainExplicit(){
        StackTraceElement[] async={new StackTraceElement("maintenance.Work","run","Work.java",1)};
        assertTrue(TaskCleanupGuard.automatic(1000,42,42,async));
        assertFalse(TaskCleanupGuard.automatic(2000,42,42,async));
        assertFalse(TaskCleanupGuard.automatic(1000,43,42,async));
        assertFalse(TaskCleanupGuard.automatic(1000,42,42,new StackTraceElement[]{
            new StackTraceElement("android.app.IActivityManager$Stub","onTransact","IActivityManager.java",1)}));
        assertFalse(TaskCleanupGuard.automatic(1000,42,42,new StackTraceElement[]{
            new StackTraceElement(WindowEngine.class.getName(),"endClosedApplications","WindowEngine.java",1)}));
    }

    @SuppressWarnings("unchecked") private static java.util.Map<TaskCleanupGuard.Key,TaskCleanupGuard.State> states(TaskCleanupGuard guard)throws Exception{
        var field=TaskCleanupGuard.class.getDeclaredField("states");field.setAccessible(true);
        return (java.util.Map<TaskCleanupGuard.Key,TaskCleanupGuard.State>)field.get(guard);
    }
    @Test public void pendingCleanupSurvivesReappearanceAndRunsOnlyAfterAFullNewGrace()throws Exception{
        var guard=new TaskCleanupGuard(message->{});var key=new TaskCleanupGuard.Key("a.app",0);
        guard.acquired(key.pkg(),key.user(),10042);
        var request=new TaskCleanupGuard.Request(new Object(),new Object[]{key.pkg(),0},null);
        assertTrue(guard.delay(key,request,true,100,null));
        var state=states(guard).get(key);assertSame(request,state.pending);
        state.observe(false,1000);
        assertTrue(guard.delay(key,request,true,300_999,request));
        guard.resumed(key.pkg(),0,10042);
        assertTrue(guard.delay(key,request,true,301_000,request));
        state.observe(false,400_000);
        assertFalse(guard.delay(key,request,true,700_000,request));
        assertFalse(states(guard).containsKey(key));
        assertTrue(guard.delay(key,request,true,700_001,request));
    }
    @Test public void explicitStopAndUidReplacementInvalidateQueuedCleanup()throws Exception{
        var guard=new TaskCleanupGuard(message->{});var key=new TaskCleanupGuard.Key("a.app",0);
        var request=new TaskCleanupGuard.Request(null,new Object[]{key.pkg(),0},null);
        guard.acquired(key.pkg(),0,10042);assertTrue(guard.delay(key,request,true,100,null));
        assertFalse(guard.delay(key,request,false,200,null));
        assertTrue(guard.delay(key,request,true,400_000,request));
        guard.acquired(key.pkg(),0,10042);guard.delay(key,request,true,500_000,null);
        guard.acquired(key.pkg(),0,10043);
        assertNull(states(guard).get(key).pending);
        assertTrue(guard.delay(key,request,true,900_000,request));
        assertFalse(guard.delay(new TaskCleanupGuard.Key("untracked.app",0),request,true,900_000,null));
    }

    public static final class Activity {
        public final String packageName;
        Activity(String pkg){packageName=pkg;}
        public int getUid(){return 10042;}
    }
    public static final class Task {
        final Activity top;
        Task(String pkg){top=new Activity(pkg);}
        public boolean isLeafTask(){return true;}
        public boolean isVisibleRequested(){return true;}
        public Activity topRunningActivity(){return top;}
    }
    public static final class Root {
        final java.util.List<Task> tasks;
        Root(Task... tasks){this.tasks=java.util.List.of(tasks);}
        public void forAllTasks(java.util.function.Consumer<Object> consumer){tasks.forEach(consumer);}
    }
    @Test public void visibilitySamplingExcludesParkedTasksAndRetainsSideHiddenSessions()throws Exception{
        var access=SurfaceFrameTest.class.getDeclaredMethod("access");access.setAccessible(true);
        var engine=new WindowEngine((FrameworkAccess)access.invoke(null),message->{});
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        var power=org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.PowerManager.class));
        power.setIsInteractive(true);
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        Task parked=new Task("a.app"),foreground=new Task("b.app");
        org.robolectric.util.ReflectionHelpers.setField(engine,"context",context);
        org.robolectric.util.ReflectionHelpers.setField(engine,"lock",new Object());
        org.robolectric.util.ReflectionHelpers.setField(engine,"root",new Root(parked,foreground));
        Class<?> type=Class.forName(WindowEngine.class.getName()+"$Session");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object session=constructor.newInstance();
        org.robolectric.util.ReflectionHelpers.setField(session,"task",parked);
        org.robolectric.util.ReflectionHelpers.setField(session,"pkg","a.app");
        org.robolectric.util.ReflectionHelpers.setField(session,"parked",true);
        org.robolectric.util.ReflectionHelpers.setField(engine,"sessions",java.util.List.of(session));
        org.robolectric.util.ReflectionHelpers.setField(engine,"active",session);
        var sample=WindowEngine.class.getDeclaredMethod("cleanupVisible",java.util.Set.class);sample.setAccessible(true);
        var a=new TaskCleanupGuard.Key("a.app",0);var b=new TaskCleanupGuard.Key("b.app",0);
        assertEquals(java.util.Set.of(b),sample.invoke(engine,java.util.Set.of(a,b)));
        org.robolectric.util.ReflectionHelpers.setField(session,"parked",false);
        org.robolectric.util.ReflectionHelpers.setField(session,"edgeHidden",true);power.setIsInteractive(false);
        assertEquals(java.util.Set.of(a),sample.invoke(engine,java.util.Set.of(a,b)));
    }
}
