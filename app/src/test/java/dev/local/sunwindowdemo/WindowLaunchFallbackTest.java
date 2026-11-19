package dev.local.sunwindowdemo;

import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=WindowLaunchFallbackTest.SurfaceShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowLaunchFallbackTest {
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess value=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class)
            .invoke(singleton.get(null),FrameworkAccess.class);
        set(value,"methods",new HashMap<>());set(value,"fields",new HashMap<>());
        return value;
    }
    private static Object invoke(WindowEngine engine,String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(engine,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    public static final class SurfaceTask {
        public SurfaceControl mSurfaceControl;
        int transactionReads;
        public SurfaceControl.Transaction getPendingTransaction(){
            transactionReads++;throw new AssertionError("A missing/released surface must not access a transaction");
        }
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        boolean released;
        @Implementation protected boolean isValid(){return !released;}
        @Implementation protected void release(){released=true;}
    }
    @Test public void missingSurfaceOpacityDoesNotReadTransaction()throws Exception{
        SurfaceTask task=new SurfaceTask();
        access().opacity(task,0);
        assertEquals(0,task.transactionReads);
    }
    @Test public void releasedSurfaceOpacityDoesNotReadTransaction()throws Exception{
        SurfaceTask task=new SurfaceTask();task.mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        assertTrue(task.mSurfaceControl.isValid());task.mSurfaceControl.release();assertFalse(task.mSurfaceControl.isValid());
        access().opacity(task,1);
        assertEquals(0,task.transactionReads);
    }
    @Test public void visibleFallbackReleasesBothSlotsAndOwnershipButKeepsRecoveryRecord()throws Throwable{
        WindowEngine engine=new WindowEngine(null,message->{});
        WindowEngine.Session destination=new WindowEngine.Session();destination.task=new Object();
        destination.parked=true;destination.entering=true;destination.transitioning=true;destination.animationRev=7;
        set(engine,"sessions",List.of(destination));set(engine,"active",destination);set(engine,"pinned",destination);
        // Keep recovery pending: this isolates the synchronous fallback contract from WM/Surface rendering.
        set(engine,"recovering",true);
        assertTrue(engine.owns(destination.task));
        invoke(engine,"fallbackVisible",new Class<?>[]{WindowEngine.Session.class},destination);
        assertTrue(destination.restoring);assertTrue(destination.restoreForeground);assertFalse(destination.parked);
        assertFalse(destination.entering);assertFalse(destination.transitioning);assertEquals(8,destination.animationRev);
        assertFalse(engine.owns(destination.task));assertNull(get(engine,"active"));assertNull(get(engine,"pinned"));
        assertEquals(List.of(destination),get(engine,"sessions"));
    }
    public static final class Animator { public boolean hasLeash(){return false;} }
    public static final class TransitionController { public boolean inTransition(ForegroundTask task){return false;} }
    public static final class Display { public int mDisplayId; }
    public static final class ForegroundTask {
        public int mTaskId=42,mUserId;
        public SurfaceControl mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        public final Animator mSurfaceAnimator=new Animator();
        public final TransitionController mTransitionController=new TransitionController();
        int backRequests;
        public boolean isAttached(){return true;}
        public Object topRunningActivity(){return this;}
        public Display getDisplayContent(){return new Display();}
        public boolean isRootTask(){return true;}
        public boolean isLeafTask(){return true;}
        public Object getParent(){return this;}
        public int getRelativeDisplayRotation(){return 0;}
        public boolean moveTaskToBack(ForegroundTask task){backRequests++;throw new AssertionError("Visible fallback is not a close");}
    }
    public static final class Root {
        final ForegroundTask task;
        Root(ForegroundTask task){this.task=task;}
        public ForegroundTask lookup(int id,int mode){assertEquals(task.mTaskId,id);assertEquals(0,mode);return task;}
    }
    private static final class AtLayout extends RuntimeException { }
    public static final class Atm {
        public void deferWindowLayout(){throw new AtLayout();}
    }
    @Test public void liveTaskWithMissingSurfaceWaitsForVisibleRestoration()throws Throwable{
        FrameworkAccess a=access();set(a,"lookup",Root.class.getDeclaredMethod("lookup",int.class,int.class));
        ForegroundTask task=new ForegroundTask();task.mSurfaceControl=null;
        WindowEngine.Session destination=new WindowEngine.Session();destination.task=task;destination.taskId=task.mTaskId;
        destination.restoreForeground=true;destination.restoring=true;
        WindowEngine engine=new WindowEngine(a,message->{});set(engine,"root",new Root(task));
        assertEquals(false,invoke(engine,"restoreTask",new Class<?>[]{WindowEngine.Session.class,boolean.class},destination,true));
        assertEquals(0,task.backRequests);assertTrue(destination.restoring);
    }
    @Test public void foregroundRestoreSkipsBackgroundMoveBeforeLayout()throws Throwable{
        FrameworkAccess a=access();set(a,"wc",ForegroundTask.class);set(a,"task",ForegroundTask.class);
        set(a,"tda",ForegroundTask.class);set(a,"lookup",Root.class.getDeclaredMethod("lookup",int.class,int.class));
        ForegroundTask task=new ForegroundTask();WindowEngine.Session destination=new WindowEngine.Session();
        destination.task=task;destination.taskId=task.mTaskId;destination.restoreForeground=true;destination.restoring=true;
        WindowEngine engine=new WindowEngine(a,message->{});set(engine,"root",new Root(task));set(engine,"atm",new Atm());
        // Stop at the first layout mutation; native Surface/configuration restoration is a device integration check.
        try{
            invoke(engine,"restoreTask",new Class<?>[]{WindowEngine.Session.class,boolean.class},destination,true);
            fail("Expected the layout boundary");
        }catch(InvocationTargetException error){assertTrue(error.getCause() instanceof AtLayout);}
        assertEquals(0,task.backRequests);assertFalse(destination.backRequested);
    }
}
