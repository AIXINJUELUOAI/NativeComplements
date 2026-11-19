package dev.local.sunwindowdemo;

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
@Config(sdk=35,manifest=Config.NONE,shadows={SwipeCoverLayerTest.TransactionShadow.class})
public class SwipeCoverLayerTest {
    public static final class Task {
        final Object parent=new Object();
        int layer=3;
        SurfaceControl relative;
        public Object getParent(){return parent;}
        public int getLastLayer(){return layer;}
        public SurfaceControl getLastRelativeLayer(){return relative;}
    }
    private static <T>T allocate(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    private static TaskCover cover(Task task)throws Exception{
        FrameworkAccess a=allocate(FrameworkAccess.class);
        for(String name:new String[]{"methods","fields"})ReflectionHelpers.setField(a,name,new java.util.concurrent.ConcurrentHashMap<>());
        TaskCover cover=allocate(TaskCover.class);
        ReflectionHelpers.setField(cover,"a",a);ReflectionHelpers.setField(cover,"task",task);
        ReflectionHelpers.setField(cover,"parent",task.parent);
        ReflectionHelpers.setField(cover,"root",ReflectionHelpers.callConstructor(SurfaceControl.class));
        return cover;
    }
    private static List<String> order(TaskCover cover)throws Exception{
        SurfaceControl.Transaction tx=new SurfaceControl.Transaction();
        Method method=TaskCover.class.getDeclaredMethod("order",SurfaceControl.Transaction.class,SurfaceControl.class);method.setAccessible(true);
        method.invoke(cover,tx,ReflectionHelpers.callConstructor(SurfaceControl.class));
        return ((TransactionShadow)Shadow.extract(tx)).calls;
    }
    @Test public void swipeUsesSameNativeZWithoutRelativeHiddenInheritance()throws Exception{
        Task task=new Task();TaskCover cover=cover(task);cover.independentSwipeLayer();
        assertEquals(List.of("layer:3"),order(cover));
        task.layer=7;assertEquals(List.of("layer:7"),order(cover));
    }
    @Test public void leashResetOrRelativeOffsetNeverReplacesLastAbsoluteLayer()throws Exception{
        Task task=new Task();TaskCover cover=cover(task);cover.independentSwipeLayer();
        task.layer=-1;assertEquals(List.of("layer:3"),order(cover));
        task.layer=1;task.relative=ReflectionHelpers.callConstructor(SurfaceControl.class);
        assertEquals(List.of("layer:3"),order(cover));
    }
    @Test public void unknownInitialOrderingKeepsExistingCoverAndCanRetryAfterLeashFinish()throws Exception{
        Task task=new Task();task.layer=-1;TaskCover cover=cover(task);
        assertFalse(cover.independentSwipeLayer());
        assertEquals(List.of("relative:1"),order(cover));
        task.layer=1;task.relative=ReflectionHelpers.callConstructor(SurfaceControl.class);
        assertFalse(cover.independentSwipeLayer());
        task.relative=null;task.layer=3;
        assertTrue(cover.independentSwipeLayer());
        assertEquals(List.of("layer:3"),order(cover));
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;
        final List<String> calls=new ArrayList<>();
        @Implementation protected void __constructor__(){}
        @Implementation protected SurfaceControl.Transaction setLayer(SurfaceControl surface,int z){calls.add("layer:"+z);return real;}
        @Implementation protected SurfaceControl.Transaction setRelativeLayer(SurfaceControl surface,SurfaceControl relative,int z){calls.add("relative:"+z);return real;}
        @Implementation protected void apply(){}
        @Implementation protected void close(){}
    }
}
