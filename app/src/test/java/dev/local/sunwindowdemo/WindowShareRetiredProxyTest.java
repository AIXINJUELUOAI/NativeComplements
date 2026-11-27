package dev.local.sunwindowdemo;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Real late-transaction recovery, with only native Task/Surface objects replaced by existing fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowShareRetiredProxyTest.SurfaceShadow.class,WindowMiniLauncherTest.TransactionShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowShareRetiredProxyTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow extends WindowMiniLauncherTest.SurfaceShadow {
        boolean released;
        @Implementation protected boolean isValid(){return !released;}
        @Implementation protected void release(){released=true;}
    }
    private WindowEngine engine;
    private FrameworkAccess access;
    private WindowMiniLauncherTest.Root root;
    private WindowMiniLauncherTest.Task proxy,target;
    private WindowEngine.Session old,current;
    private TaskVisibilityGuard mask;
    private SurfaceControl detachedLeash,originalSurface;
    private final List<String> logs=new ArrayList<>(),events=new ArrayList<>();
    private static void set(Object owner,String name,Object value)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private WindowEngine.Session session(WindowMiniLauncherTest.Task task,String token) {
        WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.taskId=task.mTaskId;s.userId=0;
        s.pkg=task.top.packageName;s.uid=task.top.uid;s.component=task.realActivity.flattenToString();s.token=token;
        s.crop=new Point(400,700);s.bounds=new Rect();s.mode=1;s.opacity=1;s.parent=task;
        return s;
    }
    @Before public void setup()throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new ConcurrentHashMap<>());set(access,"fields",new ConcurrentHashMap<>());
        set(access,"task",WindowMiniLauncherTest.Task.class);set(access,"wc",WindowMiniLauncherTest.Task.class);set(access,"tda",WindowMiniLauncherTest.Task.class);
        set(access,"lookup",WindowMiniLauncherTest.Root.class.getMethod("lookup",int.class,int.class));
        root=new WindowMiniLauncherTest.Root();proxy=new WindowMiniLauncherTest.Task(1130,root.display,events);target=new WindowMiniLauncherTest.Task(1116,root.display,events);
        root.tasks.put(proxy.mTaskId,proxy);root.tasks.put(target.mTaskId,target);
        String token=Wire.token();old=session(proxy,token);current=session(target,token);old.parked=true;
        old.launchFlow=current.launchFlow=new LaunchCoverFlow(android.os.SystemClock.elapsedRealtime());
        engine=new WindowEngine(access,logs::add);set(engine,"lock",new Object());set(engine,"handler",new Handler(Looper.getMainLooper()));
        set(engine,"root",root);set(engine,"sessions",List.of(old,current));set(engine,"active",current);
        set(engine,"journal",new AtomicFile(temporary.newFile("recovery.json")));
        originalSurface=proxy.mSurfaceControl;mask=new TaskVisibilityGuard(access,proxy);old.startMask=mask;mask.hide();
        detachedLeash=Shadow.newInstanceOf(SurfaceControl.class);
        SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();mask.inTransaction(transaction,detachedLeash);transaction.apply();
    }
    private void lateCommit()throws Exception {
        Method method=WindowEngine.class.getDeclaredMethod("repairTransition",WindowEngine.Session.class,TaskVisibilityGuard.class);
        method.setAccessible(true);method.invoke(engine,old,mask);
    }
    private static float alpha(SurfaceControl surface){return ((WindowMiniLauncherTest.SurfaceShadow)Shadow.extract(surface)).alpha;}
    private void assertCurrentUntouched()throws Exception {
        assertSame(current,get(engine,"active"));assertTrue(engine.owns(target));assertFalse(current.restoring);
        assertEquals(5,target.mode);assertEquals(0,target.configWrites);assertEquals(0,target.frontCalls);assertEquals(0,target.backCalls);
        assertFalse((Boolean)get(engine,"recovering"));
    }
    @Test public void endedProxyReleasedSurfaceLateCommitRetiresOnlyProxyWithoutRecoveringDestination()throws Exception {
        mask.transfer(); // The destination's handoff has already relinquished the old mask.
        root.tasks.remove(proxy.mTaskId);proxy.attached=false;originalSurface.release();
        lateCommit();
        assertCurrentUntouched();assertFalse(engine.owns(proxy));assertEquals(List.of(current),get(engine,"sessions"));
        assertFalse(mask.holding());assertEquals(1,alpha(detachedLeash),0);assertTrue(old.restoring);
        assertTrue(logs.toString(),logs.stream().noneMatch(line->line.contains("TX_FAILED")||line.startsWith("RECOVERY_FAILED")||line.startsWith("RESTORED")));
        // Shell can send both start and finish committed callbacks after the same proxy disappeared.
        lateCommit();assertCurrentUntouched();assertEquals(List.of(current),get(engine,"sessions"));
    }
    @Test public void unexpectedSurfaceReplacementOfAttachedProxyStillDisarmsAndRequestsGroupRecovery()throws Exception {
        SurfaceControl replacement=Shadow.newInstanceOf(SurfaceControl.class);set(proxy,"mSurfaceControl",replacement);
        lateCommit();
        assertTrue("live surface mismatch must reach failure, not the retired-proxy exemption",old.restoring);
        assertEquals(List.of(old,current),get(engine,"sessions"));assertTrue(mask.holding());assertFalse(current.restoring);
        // failure() disarms this session immediately and queues the existing group recovery.
        // Do not execute that unrelated recovery state machine in this late-callback regression.
        assertEquals(1,alpha(replacement),0);assertSame(current,get(engine,"active"));
    }
    @Test public void attachedParkedSameFlowProxyKeepsExactMaskAndDoesNotRetireOrRecover()throws Exception {
        lateCommit();assertCurrentUntouched();
        assertEquals(List.of(old,current),get(engine,"sessions"));assertFalse(old.restoring);assertTrue(mask.holding());
        assertEquals(0,alpha(originalSurface),0);assertEquals(0,alpha(detachedLeash),0);
        assertTrue(logs.toString(),logs.stream().noneMatch(line->line.contains("TX_FAILED")));
    }
}
