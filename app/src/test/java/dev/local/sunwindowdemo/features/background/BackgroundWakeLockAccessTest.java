package dev.local.sunwindowdemo.features.background;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundWakeLockAccessTest {
    public static class WakeLock {
        boolean disabled;
        public boolean setDisabled(boolean disabled){boolean changed=this.disabled!=disabled;this.disabled=disabled;return changed;}
        public boolean setDisabled(boolean disabled,boolean context){throw new AssertionError("unknown boolean semantics");}
    }
    public static class UnknownWakeLock {public boolean setDisabled(boolean disabled,boolean context){return true;}}
    @Test public void onlyVerifiedSingleBooleanSetterIsSelected()throws Exception{
        var method=BackgroundWakeLockAccess.disabledMethod(WakeLock.class);WakeLock lock=new WakeLock();
        assertArrayEquals(new Class<?>[]{boolean.class},method.getParameterTypes());
        assertTrue((Boolean)method.invoke(lock,true));assertFalse((Boolean)method.invoke(lock,true));
        assertTrue((Boolean)method.invoke(lock,false));assertFalse(lock.disabled);
        assertThrows(NoSuchMethodException.class,()->BackgroundWakeLockAccess.disabledMethod(UnknownWakeLock.class));
    }
}
