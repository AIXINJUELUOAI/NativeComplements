package dev.local.sunwindowdemo;

import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises real Engine transaction masking after foreground restoration has retired ownership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowTransitionTest.SurfaceShadow.class,WindowTransitionTest.TransactionShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowFullscreenCoverTransitionTest {
    private FrameworkAccess access;
    private WindowEngine engine;
    private WindowEngine.Session retired;
    private WindowTransitionTest.Node parent,task,activity,foreign;
    private WindowTransitionTest.Transition transition;
    private WindowTransitionTest.Info info;
    private SurfaceControl.Transaction finish;
    private TaskVisibilityGuard mask;
    private Map<Object,WindowEngine.Session> covers;
    private final java.util.List<String> logs=new java.util.ArrayList<>();
    private static void set(Object owner,String name,Object value)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static Object allocate(Class<?> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type);
    }
    private static TaskCover cover(TaskVisibilityGuard mask)throws Exception {
        // Drawing belongs to the separate cover test; only its real mask is needed at this native boundary.
        TaskCover cover=(TaskCover)allocate(TaskCover.class);cover.mask=mask;return cover;
    }
    @Before @SuppressWarnings("unchecked") public void setup()throws Exception {
        access=(FrameworkAccess)allocate(FrameworkAccess.class);
        set(access,"methods",new ConcurrentHashMap<>());set(access,"fields",new ConcurrentHashMap<>());
        set(access,"wc",WindowTransitionTest.Node.class);set(access,"noAnimationFlag",262144);
        engine=new WindowEngine(access,logs::add);set(engine,"lock",new Object());set(engine,"handler",new Handler(Looper.getMainLooper()));
        set(engine,"sessions",List.of());set(engine,"active",null);set(engine,"pinned",null);
        parent=new WindowTransitionTest.Node(null);task=new WindowTransitionTest.Node(parent);
        activity=new WindowTransitionTest.Node(task);foreign=new WindowTransitionTest.Node(parent);
        mask=new TaskVisibilityGuard(access,task);mask.hide();
        retired=new WindowEngine.Session();retired.task=task;retired.parent=parent;retired.restoreForeground=true;retired.restoring=true;
        retired.cover=cover(mask);covers=(Map<Object,WindowEngine.Session>)get(engine,"coverTasks");covers.put(task,retired);
        transition=new WindowTransitionTest.Transition();info=new WindowTransitionTest.Info();finish=new SurfaceControl.Transaction();
    }
    private WindowTransitionTest.Change add(WindowTransitionTest.Node node) {
        WindowTransitionTest.Change change=new WindowTransitionTest.Change(node);
        transition.mTargets.add(new WindowTransitionTest.Target(node));info.changes.add(change);return change;
    }
    private static WindowTransitionTest.SurfaceShadow surface(SurfaceControl surface){return Shadow.extract(surface);}
    private static WindowTransitionTest.TransactionShadow tx(SurfaceControl.Transaction transaction){return Shadow.extract(transaction);}
    private static void alpha(SurfaceControl surface,float expected){assertEquals(expected,surface(surface).alpha,0);}
    private void built() {
        assertFalse(engine.owns(task));engine.transitionBuilt(transition,info,finish);
        assertTrue(logs.toString(),logs.stream().noneMatch(line->line.contains("TX_FAILED")));
    }
    private void assertNoLateFailure(){assertTrue(logs.toString(),logs.stream().noneMatch(line->line.contains("TX_FAILED")));}
    @Test public void retiredCoverMasksBothNativeTransactionsAndExactActivityLeashButNotForeignOrDisplay()throws Exception {
        WindowTransitionTest.Change owned=add(task),child=add(activity),other=add(foreign),display=add(parent);
        child.leash=ReflectionHelpers.callConstructor(SurfaceControl.class); // Shell may detach an Activity from its task subtree.
        for(SurfaceControl.Transaction transaction:List.of(transition.mStartTransaction,finish))
            for(SurfaceControl surface:List.of(task.mSurfaceControl,child.leash,foreign.mSurfaceControl,parent.mSurfaceControl))transaction.setAlpha(surface,1);
        built();
        assertEquals(List.of(owned,child,other,display),info.changes);assertEquals(4,transition.mTargets.size());
        assertEquals(262144,owned.flags);assertEquals(262144,child.flags);assertEquals(0,other.flags);assertEquals(0,display.flags);
        for(SurfaceControl.Transaction transaction:List.of(transition.mStartTransaction,finish)) {
            transaction.apply();alpha(task.mSurfaceControl,0);alpha(child.leash,0);
            alpha(foreign.mSurfaceControl,1);alpha(parent.mSurfaceControl,1);
            tx(transaction).commit();alpha(task.mSurfaceControl,0);alpha(child.leash,0);assertTrue(mask.holding());
        }
        assertFalse(engine.owns(task));assertTrue(((List<?>)get(engine,"sessions")).isEmpty());assertNoLateFailure();
    }
    @Test public void committedNativeRevealCannotReleaseAnActiveCoverAndLateCommitAfterCoverEndRestoresOne()throws Exception {
        WindowTransitionTest.Change child=add(activity);child.leash=ReflectionHelpers.callConstructor(SurfaceControl.class);
        built();
        // A native write appended after masking must be repaired without marking the live cover released.
        transition.mStartTransaction.setAlpha(task.mSurfaceControl,1);transition.mStartTransaction.apply();alpha(task.mSurfaceControl,1);
        tx(transition.mStartTransaction).commit();alpha(task.mSurfaceControl,0);alpha(child.leash,0);assertTrue(mask.holding());
        mask.release();retired.cover=null;covers.remove(task);alpha(task.mSurfaceControl,1);alpha(child.leash,1);
        // The previously built finish transaction still contains alpha=0 from before cover release.
        finish.apply();alpha(task.mSurfaceControl,0);alpha(child.leash,0);
        tx(finish).commit();alpha(task.mSurfaceControl,1);alpha(child.leash,1);assertFalse(mask.holding());assertNoLateFailure();
    }
    @Test public void lateOldMaskCommitTransfersDetachedLeashToNewCoverUntilItsRelease()throws Exception {
        WindowTransitionTest.Change child=add(activity);child.leash=ReflectionHelpers.callConstructor(SurfaceControl.class);built();
        mask.transfer();
        TaskVisibilityGuard replacement=new TaskVisibilityGuard(access,task);replacement.hide();retired.cover=cover(replacement);
        finish.apply();tx(finish).commit();
        assertTrue(replacement.holding());alpha(task.mSurfaceControl,0);alpha(child.leash,0);assertNoLateFailure();
        replacement.release();alpha(task.mSurfaceControl,1);alpha(child.leash,1);
    }
    @Test public void foreignOnlyTransitionCannotMaskOrDisableAnimationForUnrelatedTargets() {
        WindowTransitionTest.Change other=add(foreign),display=add(parent);built();
        transition.mStartTransaction.apply();finish.apply();
        alpha(foreign.mSurfaceControl,1);alpha(parent.mSurfaceControl,1);
        assertEquals(0,other.flags);assertEquals(0,display.flags);assertEquals(List.of(other,display),info.changes);
        assertTrue(mask.holding());alpha(task.mSurfaceControl,0);assertNoLateFailure();
    }
}
