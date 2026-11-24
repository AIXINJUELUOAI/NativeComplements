package dev.local.sunwindowdemo;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.*;
import android.os.Process;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35, shadows=BackHandoffTest.HostStartShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BackHandoffTest {
    // Bootstrap recovery remains active; long-side-swipe handoff has been retired.
    @Implements(SystemUiHost.class)
    public static class HostStartShadow {
        static int starts, failures, publishes;
        @Implementation protected static void start(Context context) throws Exception {
            starts++;
            if(failures-->0)throw new IllegalStateException("host unavailable");
            if(SystemUiHost.live==null){
                Constructor<SystemUiHost> constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);SystemUiHost.live=constructor.newInstance(context);
            }
        }
        @Implementation protected void publish(boolean force){publishes++;}
    }

    private Context context;
    private final List<String> cancelled=new ArrayList<>();
    private final List<Handler> handlers=new ArrayList<>();
    private final List<SystemUiHost> hosts=new ArrayList<>();
    private static Field field(Class<?> type,String name)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Object get(Object owner,String name)throws Exception{return field(owner.getClass(),name).get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{field(owner.getClass(),name).set(owner,value);}
    private static Object allocate(Class<?> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field(unsafe,"theUnsafe").get(null),type);
    }
    private static void idle(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}

    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();
        SystemUiHost.live=null;HostStartShadow.starts=0;HostStartShadow.failures=0;HostStartShadow.publishes=0;
        Config.hostPreferences=null;
        PackageInfo info=new PackageInfo();info.packageName="com.android.systemui";
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=info.packageName;info.applicationInfo.uid=Process.myUid();
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        ShadowBinder.setCallingUid(Process.myUid());
        field(Wire.class,"endpoint").set(null,new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                assertEquals(Wire.CANCEL_BACK,code);assertEquals(IBinder.FLAG_ONEWAY,flags);
                data.enforceInterface(Wire.CHANNEL);cancelled.add(data.readString());data.enforceNoDataAvail();return true;
            }
        });
    }
    @After public void cleanup()throws Exception {
        if(SystemUiHost.live!=null&&!hosts.contains(SystemUiHost.live))hosts.add(SystemUiHost.live);
        for(SystemUiHost host:hosts){
            ((Handler)get(host,"main")).removeCallbacksAndMessages(null);
            ((java.util.concurrent.ExecutorService)get(host,"settingsWorker")).shutdownNow();
        }
        field(Wire.class,"endpoint").set(null,null);SystemUiHost.live=null;Config.hostPreferences=null;
        ShadowBinder.setCallingUid(Process.myUid());
        for(Handler handler:handlers)handler.removeCallbacksAndMessages(null);
    }

    private static final class RegistrationContext extends ContextWrapper {
        int registrations,unregistrations;
        boolean failSecond=true;
        final Set<BroadcastReceiver> active=Collections.newSetFromMap(new IdentityHashMap<>());
        RegistrationContext(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler handler,int flags){
            registrations++;
            if(failSecond&&registrations==2)throw new IllegalStateException("registration unavailable");
            assertTrue(active.add(receiver));return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver){assertTrue(active.remove(receiver));unregistrations++;}
    }
    private Object bridge()throws Exception {
        Object bridge=allocate(Class.forName("dev.local.sunwindowdemo.BackBridge"));
        Handler handler=new Handler(Looper.getMainLooper());handlers.add(handler);
        set(bridge,"main",handler);return bridge;
    }
    private void connect(Object bridge,Context context)throws Exception {
        Method method=bridge.getClass().getDeclaredMethod("connect",Context.class);method.setAccessible(true);method.invoke(bridge,context);
    }

    @Test public void secondReceiverFailureRollsBackAndRetryRegistersExactlyOnePair()throws Exception {
        Object bridge=bridge();RegistrationContext app=new RegistrationContext(context);
        connect(bridge,app);
        assertNull(get(bridge,"context"));assertEquals(1,app.unregistrations);assertTrue(app.active.isEmpty());
        assertEquals(0,HostStartShadow.starts);assertNotNull(get(bridge,"reconnect"));
        idle(2999);assertEquals(2,app.registrations);
        idle(1);
        assertSame(app,get(bridge,"context"));assertEquals(4,app.registrations);assertEquals(2,app.active.size());
        assertEquals(1,HostStartShadow.starts);assertEquals(1,HostStartShadow.publishes);assertNull(get(bridge,"reconnect"));
        connect(bridge,app);idle(6000);
        assertEquals(4,app.registrations);assertEquals(1,HostStartShadow.starts);
    }

    @Test public void hostFailureAfterContextAssignmentStillRetriesWithoutRegisteringAgain()throws Exception {
        Object bridge=bridge();RegistrationContext app=new RegistrationContext(context);app.failSecond=false;
        HostStartShadow.failures=1;connect(bridge,app);
        assertSame(app,get(bridge,"context"));assertNull(SystemUiHost.live);assertEquals(2,app.registrations);
        assertNotNull(get(bridge,"reconnect"));idle(3000);
        assertEquals(2,HostStartShadow.starts);assertNotNull(SystemUiHost.live);assertEquals(2,app.registrations);
        assertEquals(1,HostStartShadow.publishes);assertNull(get(bridge,"reconnect"));
    }

    @Test public void repeatedConnectReplacesRetryInsteadOfLeavingParallelCallbacks()throws Exception {
        Object bridge=bridge();RegistrationContext app=new RegistrationContext(context);app.failSecond=false;
        HostStartShadow.failures=10;
        connect(bridge,app);Object first=get(bridge,"reconnect");
        idle(1000);connect(bridge,app);assertNotSame(first,get(bridge,"reconnect"));
        idle(2000);assertEquals(2,HostStartShadow.starts); // Original callback was removed.
        idle(1000);assertEquals(3,HostStartShadow.starts);
        assertEquals(2,app.registrations);
        ((Handler)get(bridge,"main")).removeCallbacks((Runnable)get(bridge,"reconnect"));
    }

    private SystemUiHost host()throws Exception {
        Constructor<SystemUiHost> constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        SystemUiHost host=constructor.newInstance(context);hosts.add(host);return host;
    }
    @Test public void retiredGestureOperationsStayRejectedEvenWithLegacySettingEnabled()throws Exception {
        SystemUiHost host=host();
        int before=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size();
        for(boolean enabled:new boolean[]{false,true}){
            Config config=new Config(context);config.prefs.edit().clear().putBoolean("enabled",true).putBoolean("longBackWindow",enabled).commit();
            set(host,"config",config);set(host,"settingsReady",true);set(host,"backendReady",true);set(host,"backendState","idle");
            for(int operation=0;operation<3;operation++){
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                try{
                    data.writeInterfaceToken(BackBridge.CHANNEL);data.writeInt(42);data.writeString("example.app");data.writeString(Wire.token());
                    assertFalse(((Binder)get(host,"dropChannel")).transact(IBinder.FIRST_CALL_TRANSACTION+operation,data,reply,0));
                }finally{data.recycle();reply.recycle();}
            }
        }
        idle(0);assertTrue(cancelled.isEmpty());
        assertEquals(before,Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size());
    }
    @Test public void remainingScreenshotOperationRejectsForeignUidBeforeChangingPriority()throws Exception {
        SystemUiHost host=host();ShadowBinder.setCallingUid(Process.myUid()+1);
        Parcel data=Parcel.obtain();
        try{
            data.writeInterfaceToken(BackBridge.CHANNEL);data.writeBoolean(true);
            assertFalse(((Binder)get(host,"dropChannel")).transact(IBinder.FIRST_CALL_TRANSACTION+3,data,null,IBinder.FLAG_ONEWAY));
        }finally{data.recycle();}
        idle(0);assertEquals(false,get(host,"screenshotVisible"));assertTrue(cancelled.isEmpty());
    }
}
