package dev.local.sunwindowdemo.features.background;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundFreezerAccessTest {
    public static class Freezer {
        int pid,timeout,calls,result;
        boolean freeze;
        RuntimeException failure;
        public int freezeBinder(int pid,boolean freeze,int timeout){
            this.pid=pid;this.freeze=freeze;this.timeout=timeout;calls++;
            if(failure!=null)throw failure;
            return result;
        }
        public int freezeBinder(int pid,int uid,boolean freeze,int timeout){throw new AssertionError("unverified overload");}
    }
    public static class UnverifiedFreezer {
        public int freezeBinder(int pid,int uid,boolean freeze,int timeout){return 0;}
    }
    @Test public void recoveryUsesExactlyPidFalseZeroEvenWhenUidOverloadExists()throws Exception{
        Freezer freezer=new Freezer();Method method=BackgroundFreezerAccess.binderMethod(Freezer.class);
        assertArrayEquals(new Class<?>[]{int.class,boolean.class,int.class},method.getParameterTypes());
        assertEquals(int.class,method.getReturnType());
        BackgroundFreezerAccess.recoverBinder(method,freezer,12345);
        assertEquals(1,freezer.calls);assertEquals(12345,freezer.pid);assertFalse(freezer.freeze);assertEquals(0,freezer.timeout);
    }
    @Test public void failedBinderRecoveryMustPropagateBeforeContinueOrMarkerDeletion()throws Exception{
        Freezer freezer=new Freezer();Method method=BackgroundFreezerAccess.binderMethod(Freezer.class);
        freezer.result=-11;
        assertThrows(IOException.class,()->BackgroundFreezerAccess.recoverBinder(method,freezer,12345));
        freezer.failure=new IllegalStateException("native failure");
        InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->BackgroundFreezerAccess.recoverBinder(method,freezer,12345));
        assertSame(freezer.failure,failure.getCause());assertEquals(2,freezer.calls);
    }
    @Test public void unknownUidOnlyAbiIsNotGuessed(){
        assertThrows(NoSuchMethodException.class,()->BackgroundFreezerAccess.binderMethod(UnverifiedFreezer.class));
    }
}
