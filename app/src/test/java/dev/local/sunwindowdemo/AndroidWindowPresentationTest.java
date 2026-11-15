package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={AndroidWindowPresentationTest.TransactionShadow.class,AndroidWindowPresentationTest.SurfaceShadow.class})
public class AndroidWindowPresentationTest {
    static final Object LOCK=new Object();
    public static final class Task {
        public final SurfaceControl mSurfaceControl=ReflectionHelpers.callConstructor(SurfaceControl.class);
        final SurfaceControl.Transaction pending=new SurfaceControl.Transaction();
        public SurfaceControl.Transaction getPendingTransaction(){assertTrue(Thread.holdsLock(LOCK));return pending;}
        public Rect getBounds(){assertTrue(Thread.holdsLock(LOCK));return new Rect(20,30,1220,2163);}
        // No leash/Activity API: a suspension route must never probe one.
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        for(String name:new String[]{"methods","fields"}){Field f=FrameworkAccess.class.getDeclaredField(name);f.setAccessible(true);f.set(a,new java.util.concurrent.ConcurrentHashMap<>());}
        return a;
    }
    private static WindowEngine.Session session(Task task){
        WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.parent=task;
        s.placement=new Geometry.Placement(600,1000,.75f,100,200);return s;
    }
    @Test public void suspendedOwnerHidesNativeTaskBeforeZeroAlphaWithoutLeashProbe()throws Exception{
        FrameworkAccess a=access();Task task=new Task();WindowEngine.Session s=session(task);
        AndroidWindowPresentation executor=new AndroidWindowPresentation(new WindowEngine(a,message->{}));
        synchronized(LOCK){executor.apply(s,WindowPresentation.Route.SUSPENDED);}
        assertEquals(List.of("hide","alpha:0.0"),((TransactionShadow)Shadow.extract(task.pending)).calls);assertTrue(s.surfaceHidden);
    }
    @Test public void externalPriorityWritesNativeHideLastAndSameTaskPermissionStaysVisible()throws Exception{
        FrameworkAccess a=access();Task task=new Task();WindowEngine.Session s=session(task);s.priorityHideTask=true;
        AndroidWindowPresentation executor=new AndroidWindowPresentation(new WindowEngine(a,message->{}));
        synchronized(LOCK){executor.apply(s,WindowPresentation.Route.PRIORITY_HIDDEN);}
        TransactionShadow tx=Shadow.extract(task.pending);assertEquals(List.of("alpha:0.0","hide"),tx.calls);
        tx.calls.clear();s.priorityHideTask=false;
        synchronized(LOCK){executor.apply(s,WindowPresentation.Route.PRIORITY_SAME_TASK);}
        assertEquals(List.of("show","alpha:1.0","scale","position"),tx.calls);assertFalse(s.surfaceHidden);
        assertEquals(80,tx.x,0);assertEquals(170,tx.y,0);assertEquals(.75f,tx.scale,0);
    }
    @Test public void visibleStateCacheDoesNotSkipRequiredNativeHide()throws Exception{
        FrameworkAccess a=access();Task task=new Task();WindowEngine.Session s=session(task);
        AndroidWindowPresentation executor=new AndroidWindowPresentation(new WindowEngine(a,message->{}));
        synchronized(LOCK){executor.visible(s,true);executor.visible(s,false);executor.visible(s,false);executor.visible(s,true);executor.visible(s,true);}
        assertEquals(List.of("hide","hide","show"),((TransactionShadow)Shadow.extract(task.pending)).calls);
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow { @Implementation protected boolean isValid(){return true;} }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;final List<String> calls=new ArrayList<>();float x,y,scale;
        @Implementation protected void __constructor__(){}
        private void call(String name){assertTrue(Thread.holdsLock(LOCK));calls.add(name);}
        @Implementation protected SurfaceControl.Transaction hide(SurfaceControl s){call("hide");return real;}
        @Implementation protected SurfaceControl.Transaction show(SurfaceControl s){call("show");return real;}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl s,float alpha){call("alpha:"+alpha);return real;}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl s,float x,float y){call("scale");scale=x;return real;}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl s,float x,float y){call("position");this.x=x;this.y=y;return real;}
    }
}
