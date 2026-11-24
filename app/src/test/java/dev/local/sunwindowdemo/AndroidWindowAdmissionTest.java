package dev.local.sunwindowdemo;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class AndroidWindowAdmissionTest {
    static final Object LOCK=new Object();
    public static final class Atm { public boolean mSupportsMultiWindow=true,mSupportsFreeformWindowManagement=true; }
    public static final class Display { public int mDisplayId;public Rect getBounds(){return new Rect(0,0,1200,2600);} }
    public static final class Area {
        public boolean isValidWindowingMode(int mode,Object activity,Task task){
            assertTrue(Thread.holdsLock(LOCK));assertSame(task,task.candidate.get());return task.areaValid;
        }
    }
    public static final class Animator {
        boolean leashed;int reads;
        public boolean hasLeash(){assertTrue(Thread.holdsLock(LOCK));reads++;return leashed;}
    }
    public static final class TransitionController {
        boolean transitioning;int reads;
        public boolean inTransition(Task task){assertTrue(Thread.holdsLock(LOCK));reads++;return transitioning;}
    }
    public static final class Task {
        public final Animator mSurfaceAnimator=new Animator();
        public final TransitionController mTransitionController=new TransitionController();
        public int mTaskId=42,mUserId;public ComponentName realActivity=new ComponentName("example.app","example.app.Main");
        public Point mLastSurfaceSize=new Point(1200,2133);
        final ThreadLocal<Object> candidate;final Area area=new Area();final Display display=new Display();
        final Configuration requested=new Configuration();
        int mode=1,activityType=1,relativeRotation,displayReads,resizeReads;boolean opaque,root=true,freeform=true,areaValid=true,throwResize,top,focusable=true;
        Task(ThreadLocal<Object> candidate){this.candidate=candidate;}
        public boolean isForceOpaque(){assertTrue(Thread.holdsLock(LOCK));return opaque;}
        public boolean isRootTask(){return root;}
        public boolean isLeafTask(){return true;}
        public boolean isAttached(){return true;}
        public Area getParent(){return area;}
        public Display getDisplayContent(){displayReads++;return display;}
        public int getActivityType(){return activityType;}
        public int getRelativeDisplayRotation(){return relativeRotation;}
        public Area getDisplayArea(){return area;}
        public boolean supportsFreeformInDisplayArea(Area area){
            assertTrue(Thread.holdsLock(LOCK));assertSame(this,candidate.get());resizeReads++;
            if(throwResize)throw new IllegalStateException("native query failed");return freeform;
        }
        public boolean isOverrideBoundsAllowed(){assertSame(this,candidate.get());return true;}
        public boolean isOverrideWindowingModeAllowed(){assertSame(this,candidate.get());return true;}
        public Object topRunningActivity(){return null;}
        public Intent getBaseIntent(){return new Intent();}
        public int getWindowingMode(){return mode;}
        public boolean isAlwaysOnTop(){return top;}
        public boolean isFocusable(){return focusable;}
        public Configuration getRequestedOverrideConfiguration(){return requested;}
        public boolean isForceTranslucent(){return true;}
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(a,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        set(a,"tda",Area.class);set(a,"task",Task.class);set(a,"wc",Task.class);set(a,"activity",Object.class);return a;
    }
    private static void rejected(AndroidWindowAdmission adapter,Task task,String message)throws Exception{
        try{synchronized(LOCK){adapter.validate(task,true);}fail("expected rejection");}
        catch(IllegalStateException error){assertEquals(message,error.getMessage());}
    }
    @Test public void earlyRejectionNeverQueriesDisplayResizeOrLaterNativeApis()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Atm atm=new Atm();Task task=new Task(candidate);
        AndroidWindowAdmission adapter=new AndroidWindowAdmission(access(),atm,candidate);
        task.opaque=true;rejected(adapter,task,WindowAdmission.Rejection.OPAQUE.message);assertEquals(0,task.displayReads);
        task.opaque=false;atm.mSupportsMultiWindow=false;rejected(adapter,task,WindowAdmission.Rejection.CAPABILITY.message);assertEquals(0,task.displayReads);
        atm.mSupportsMultiWindow=true;task.root=false;rejected(adapter,task,WindowAdmission.Rejection.HIERARCHY.message);assertEquals(0,task.displayReads);
        assertEquals(0,task.resizeReads);assertNull(candidate.get());
    }
    @Test public void resizeCandidateIsExactAndPreviousCandidateSurvivesSuccessRejectionAndNativeFailure()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Task task=new Task(candidate);Object previous=new Object();candidate.set(previous);
        AndroidWindowAdmission adapter=new AndroidWindowAdmission(access(),new Atm(),candidate);
        synchronized(LOCK){adapter.validate(task,true);}assertSame(previous,candidate.get());
        task.freeform=false;rejected(adapter,task,WindowAdmission.Rejection.RESIZE.message);assertSame(previous,candidate.get());
        task.freeform=true;task.throwResize=true;
        try{synchronized(LOCK){adapter.validate(task,true);}fail("expected native failure");}catch(InvocationTargetException expected){assertEquals("native query failed",expected.getCause().getMessage());}
        assertSame(previous,candidate.get());candidate.remove();task.throwResize=false;
        synchronized(LOCK){adapter.validate(task,true);}assertNull(candidate.get());
    }
    @Test public void busyEntryKeepsRetryExceptionAndNeverGrantsResizeCandidate()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Task task=new Task(candidate);task.relativeRotation=1;
        AndroidWindowAdmission adapter=new AndroidWindowAdmission(access(),new Atm(),candidate);
        try{synchronized(LOCK){adapter.validate(task,true);}fail("expected retry");}catch(WindowEngine.EntryNotReady expected){assertEquals(WindowAdmission.Rejection.WAIT.message,expected.getMessage());}
        assertEquals(0,task.resizeReads);assertNull(candidate.get());
    }
    @Test public void ordinaryEntryRejectsNativeLeashAndTransitionButLinkedEntryOnlyBypassesThose()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Task task=new Task(candidate);
        AndroidWindowAdmission adapter=new AndroidWindowAdmission(access(),new Atm(),candidate);
        synchronized(LOCK){adapter.validate(task,false);}
        assertEquals(1,task.mSurfaceAnimator.reads);assertEquals(1,task.mTransitionController.reads);
        task.mSurfaceAnimator.leashed=true;
        try{synchronized(LOCK){adapter.validate(task,false);}fail("ordinary entry must wait for leash");}
        catch(WindowEngine.EntryNotReady expected){assertEquals("leash rejection short-circuits native transition query",1,task.mTransitionController.reads);}
        task.mSurfaceAnimator.leashed=false;task.mTransitionController.transitioning=true;
        try{synchronized(LOCK){adapter.validate(task,false);}fail("ordinary entry must wait for transition");}
        catch(WindowEngine.EntryNotReady expected){assertNull(candidate.get());}
        int animatorReads=task.mSurfaceAnimator.reads,transitionReads=task.mTransitionController.reads;
        synchronized(LOCK){adapter.validate(task,true);}
        assertEquals(animatorReads,task.mSurfaceAnimator.reads);assertEquals(transitionReads,task.mTransitionController.reads);
        task.relativeRotation=1;
        try{synchronized(LOCK){adapter.validate(task,true);}fail("linked entry must still wait for relative rotation");}
        catch(WindowEngine.EntryNotReady expected){assertNull(candidate.get());}
    }
    @Test public void snapshotCopiesNativeConfigurationAndInheritedFreeformGetsLocalFullscreenEndpoint()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Task task=new Task(candidate);FrameworkAccess a=access();
        AndroidWindowAdmission adapter=new AndroidWindowAdmission(a,new Atm(),candidate);
        Object nativeConfig=a.field(task.requested,"windowConfiguration");
        a.call(nativeConfig,"setWindowingMode",new Class<?>[]{int.class},1);a.call(nativeConfig,"setAlwaysOnTop",new Class<?>[]{int.class},2);
        WindowEngine.Session snapshot;
        synchronized(LOCK){snapshot=adapter.snapshot(task,"example.app","token",true,null);}
        assertSame(task,snapshot.task);assertNotSame(task.requested,snapshot.originalConfig);assertTrue(snapshot.forceTranslucent);
        assertEquals(1,snapshot.mode);assertTrue(snapshot.bounds.isEmpty());assertEquals(1200,snapshot.logicalShort);
        task.mode=5;task.focusable=false;
        a.call(nativeConfig,"setWindowingMode",new Class<?>[]{int.class},5);a.call(nativeConfig,"setAlwaysOnTop",new Class<?>[]{int.class},1);
        a.call(nativeConfig,"setBounds",new Class<?>[]{Rect.class},new Rect(20,30,620,1030));
        synchronized(LOCK){snapshot=adapter.snapshot(task,"example.app","token",true,null);}
        assertEquals(1,snapshot.mode);assertEquals(2,snapshot.topState);assertTrue(snapshot.bounds.isEmpty());
        Object endpoint=a.field(snapshot.originalConfig,"windowConfiguration");
        assertEquals("fullscreen restore must retain the admitted ordinary task type",1,a.integer(endpoint,"getActivityType"));
        assertEquals(1,a.integer(endpoint,"getWindowingMode"));assertTrue(((Rect)a.call(endpoint,"getBounds")).isEmpty());
        assertEquals(0,a.integer(nativeConfig,"getActivityType"));
        assertEquals(5,a.integer(nativeConfig,"getWindowingMode"));assertEquals(new Rect(20,30,620,1030),a.call(nativeConfig,"getBounds"));
        try{synchronized(LOCK){adapter.snapshot(task,"example.app","token",false,null);}fail("cannot acquire unlinked native freeform");}
        catch(IllegalStateException error){assertEquals(WindowAdmission.Rejection.OWNERSHIP.message,error.getMessage());}
        // Restore uses a full Configuration replacement, not an undefined-field merge.
        task.requested.setTo(snapshot.originalConfig);task.mode=a.integer(nativeConfig,"getWindowingMode");
        task.activityType=a.integer(nativeConfig,"getActivityType");task.focusable=true;
        synchronized(LOCK){snapshot=adapter.snapshot(task,"example.app","token",false,null);}
        assertEquals(1,snapshot.mode);assertEquals(1,a.integer(a.field(snapshot.originalConfig,"windowConfiguration"),"getActivityType"));
    }
    @Test public void inheritedEndpointNeverAdmitsUndefinedOrNonApplicationTaskTypes()throws Exception{
        ThreadLocal<Object> candidate=new ThreadLocal<>();Task task=new Task(candidate);task.mode=5;
        FrameworkAccess a=access();AndroidWindowAdmission adapter=new AndroidWindowAdmission(a,new Atm(),candidate);
        for(int type:new int[]{0,2,3,4,5}){
            task.activityType=type;
            try{synchronized(LOCK){adapter.snapshot(task,"example.app","token",true,null);}fail("type="+type);}
            catch(IllegalStateException error){assertEquals(WindowAdmission.Rejection.ACTIVITY_TYPE.message,error.getMessage());}
            assertEquals(0,a.integer(a.field(task.requested,"windowConfiguration"),"getActivityType"));
            assertNull(candidate.get());
        }
    }
}
