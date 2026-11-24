package dev.local.sunwindowdemo.features.shade;

import android.view.ViewGroup;
import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

public class MobileSignalBinderTest {
    public static class Model {}
    public static class Logger {}
    public interface ModernStatusBarViewBinding {}
    public static class InstanceBinder {
        public ModernStatusBarViewBinding bind(ViewGroup view,Model model,int location,Logger logger){return null;}
    }
    public static class StaticBinder {
        public static ModernStatusBarViewBinding bind(ViewGroup view,Model model,int location,Logger logger){return null;}
    }
    public static class UnknownBinder {
        public Object bind(ViewGroup view,Model model,int location,Logger logger){return null;}
    }
    @Test public void kotlinStaticAndInstanceBindShareExactArgumentPositions()throws Exception{
        var instance=MobileSignalBinder.bindMethod(InstanceBinder.class,Model.class,Logger.class);
        var singleton=MobileSignalBinder.bindMethod(StaticBinder.class,Model.class,Logger.class);
        assertFalse(Modifier.isStatic(instance.getModifiers()));assertTrue(Modifier.isStatic(singleton.getModifiers()));
        assertArrayEquals(new Class<?>[]{ViewGroup.class,Model.class,int.class,Logger.class},singleton.getParameterTypes());
        assertEquals(ModernStatusBarViewBinding.class,instance.getReturnType());
        assertThrows(NoSuchMethodException.class,()->MobileSignalBinder.bindMethod(UnknownBinder.class,Model.class,Logger.class));
    }
}
