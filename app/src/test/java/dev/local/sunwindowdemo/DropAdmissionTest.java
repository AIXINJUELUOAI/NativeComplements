package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.*;
import android.os.PowerManager;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35)
public class DropAdmissionTest {
    private Context context;
    private SystemUiHost host;
    private final List<Class<?>> queries=new ArrayList<>();
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{
        Method method=SystemUiHost.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(host,args);
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();Config.hostPreferences=null;
        context.getSharedPreferences("corner",0).edit().clear().putBoolean("enabled",true).putBoolean("longBackWindow",true)
            .putBoolean("landscape",false).putBoolean("keyboard",false).commit();
        Context watched=new ContextWrapper(context){
            @Override public Object getSystemService(String name){
                if(Context.POWER_SERVICE.equals(name))queries.add(PowerManager.class);
                if(Context.KEYGUARD_SERVICE.equals(name))queries.add(KeyguardManager.class);
                if(Context.ACTIVITY_SERVICE.equals(name))queries.add(android.app.ActivityManager.class);
                return super.getSystemService(name);
            }
        };
        Constructor<SystemUiHost> constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);host=constructor.newInstance(watched);
        set(host,"config",new Config(context));set(host,"settingsReady",true);set(host,"backendReady",true);set(host,"backendState","idle");
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
    }
    @After public void cleanup()throws Exception{
        Field worker=SystemUiHost.class.getDeclaredField("settingsWorker");worker.setAccessible(true);((java.util.concurrent.ExecutorService)worker.get(host)).shutdownNow();
        Field handler=SystemUiHost.class.getDeclaredField("main");handler.setAccessible(true);((android.os.Handler)handler.get(host)).removeCallbacksAndMessages(null);
        Config.hostPreferences=null;
    }
    @Test public void disabledAndScreenOffShortCircuitBeforeLaterNativeServices()throws Exception{
        context.getSharedPreferences("corner",0).edit().putBoolean("enabled",false).commit();
        assertEquals(false,call("allowed",new Class<?>[]{}));assertTrue(queries.isEmpty());
        assertFalse(host.notificationWindowReady());assertTrue(queries.isEmpty());
        context.getSharedPreferences("corner",0).edit().putBoolean("enabled",true).commit();
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        assertEquals(false,call("allowed",new Class<?>[]{}));assertEquals(List.of(PowerManager.class),queries);
        queries.clear();assertFalse(host.notificationWindowReady());assertEquals(List.of(PowerManager.class),queries);
    }
    @Test public void recoveryAllowsDefaultDropButRejectsNotificationBeforePowerQuery()throws Exception{
        set(host,"backendState","recovery");assertEquals(true,call("dropAvailable",new Class<?>[]{}));
        assertFalse(host.notificationWindowReady());assertTrue(queries.isEmpty());
        set(host,"backendState","active");set(host,"normalOccupied",true);
        assertEquals(false,call("dropAvailable",new Class<?>[]{}));assertTrue(host.notificationWindowReady());
        assertEquals(List.of(PowerManager.class,KeyguardManager.class),queries);
    }
    @Test public void screenshotBlocksCornerButNotificationKeepsItsDistinctPolicy()throws Exception{
        set(host,"screenshotVisible",true);assertEquals(false,call("allowed",new Class<?>[]{}));assertTrue(queries.isEmpty());
        assertTrue(host.notificationWindowReady());assertEquals(List.of(PowerManager.class,KeyguardManager.class),queries);
        queries.clear();set(host,"screenshotVisible",false);set(host,"keyboard",true);
        context.getSharedPreferences("corner",0).edit().putBoolean("keyboard",true).commit();
        assertEquals(false,call("allowed",new Class<?>[]{}));assertTrue(host.notificationWindowReady());
    }
    @Test public void currentEntryDoesNotSamplePowerAndRejectsBeforeRunningTaskQueryWhenLocked()throws Exception{
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        call("floatCurrent",new Class<?>[]{int.class},0);assertEquals(List.of(KeyguardManager.class),queries);
    }
}
