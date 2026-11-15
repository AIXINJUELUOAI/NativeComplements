package dev.local.sunwindowdemo;

import android.content.Intent;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowCapabilityResponseTest {
    public static final class Atm { public boolean mSupportsMultiWindow=true,mSupportsFreeformWindowManagement=true; }
    public static final class InvalidAtm { public String mSupportsMultiWindow="true"; public boolean mSupportsFreeformWindowManagement=true; }
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(a,"fields",new java.util.concurrent.ConcurrentHashMap<>());return a;
    }
    private static WindowEngine engine(Object atm)throws Exception{
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"atm",atm);set(engine,"lock",new Object());return engine;
    }
    private static Intent status(WindowEngine engine,String event,String requestId){
        Intent out=new Intent();engine.fillCapabilities(out,event,requestId);return out;
    }
    @Test public void uncorrelatedHeartbeatStartsUnknownAndNeverEchoesRequestIds()throws Exception{
        WindowEngine engine=engine(new Atm());
        for(String event:new String[]{"STATUS","TICK","ACTIVE"}){
            Intent out=status(engine,event,"STATUS".equals(event)?null:"not-a-status-request");
            assertFalse(out.hasExtra("requestId"));assertFalse(out.getBooleanExtra("capabilitiesKnown",true));
            assertFalse(out.getBooleanExtra("supportsMultiWindow",true));assertFalse(out.getBooleanExtra("supportsFreeform",true));
            assertFalse(out.getBooleanExtra("preflightReady",true));assertFalse(out.getBooleanExtra("ownershipConflict",true));
        }
    }
    @Test public void everyCorrelatedStatusReadsCurrentFlagsRatherThanThirtySecondDiagnosticCache()throws Exception{
        Atm atm=new Atm();WindowEngine engine=engine(atm);set(engine,"attached",true);engine.preflightReady();
        Intent first=status(engine,"STATUS","one");assertEquals("one",first.getStringExtra("requestId"));
        assertTrue(first.getBooleanExtra("capabilitiesKnown",false));assertTrue(first.getBooleanExtra("supportsMultiWindow",false));
        assertTrue(first.getBooleanExtra("supportsFreeform",false));assertTrue(first.getBooleanExtra("preflightReady",false));
        atm.mSupportsFreeformWindowManagement=false;
        Intent second=status(engine,"STATUS","two");assertEquals("two",second.getStringExtra("requestId"));assertFalse(second.getBooleanExtra("supportsFreeform",true));
        atm.mSupportsMultiWindow=false;
        Intent third=status(engine,"STATUS","three");assertFalse(third.getBooleanExtra("supportsMultiWindow",true));
        assertTrue(third.getBooleanExtra("capabilitiesKnown",false));
        Intent heartbeat=status(engine,"STATUS",null);assertFalse(heartbeat.hasExtra("requestId"));assertFalse(heartbeat.getBooleanExtra("supportsFreeform",true));
    }
    @Test public void failedReadClearsPreviousSuccessAndKeepsInstallationSeparateFromNativeSupport()throws Exception{
        WindowEngine engine=engine(new Atm());engine.preflightReady();
        assertFalse(status(engine,"STATUS","before-attach").getBooleanExtra("preflightReady",true));
        set(engine,"attached",true);assertTrue(status(engine,"STATUS","ready").getBooleanExtra("preflightReady",false));
        set(engine,"atm",new InvalidAtm());Intent failed=status(engine,"STATUS","wrong-abi");
        assertFalse(failed.getBooleanExtra("capabilitiesKnown",true));assertFalse(failed.getBooleanExtra("supportsMultiWindow",true));
        assertFalse(failed.getBooleanExtra("supportsFreeform",true));assertTrue(failed.getBooleanExtra("preflightReady",false));
        assertEquals("wrong-abi",failed.getStringExtra("requestId"));
    }
    @Test public void emptyOversizedOrNonStatusIdsCannotTriggerCapabilityProbe()throws Exception{
        WindowEngine engine=engine(new Atm());
        for(String request:new String[]{"","x".repeat(129)}){
            Intent out=status(engine,"STATUS",request);assertFalse(out.hasExtra("requestId"));assertFalse(out.getBooleanExtra("capabilitiesKnown",true));
        }
        Intent wrongEvent=status(engine,"TICK","valid-id");assertFalse(wrongEvent.hasExtra("requestId"));assertFalse(wrongEvent.getBooleanExtra("capabilitiesKnown",true));
        assertTrue(status(engine,"STATUS","x".repeat(128)).getBooleanExtra("capabilitiesKnown",false));
    }
}
