package dev.local.sunwindowdemo;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.*;
import android.os.Process;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // Only isolate host bootstrap/publishing: connect and dropChannel run production bodies.
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

    private SystemUiHost host()throws Exception {return host(context);}
    private SystemUiHost host(Context base)throws Exception {
        Constructor<SystemUiHost> constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        SystemUiHost host=constructor.newInstance(base);
        handlers.add((Handler)get(host,"main"));
        Config config=new Config(context);config.prefs.edit().clear().putBoolean("enabled",true).putBoolean("longBackWindow",true).putBoolean("landscapeOff",false).putBoolean("keyboardOff",false).commit();
        set(host,"config",config);set(host,"settingsReady",true);set(host,"backendReady",true);set(host,"backendState","idle");
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        return host;
    }
    private boolean commit(SystemUiHost host,String token)throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken("dev.nativecomplements.module.backLong.v1");data.writeInt(42);data.writeString("example.app");data.writeString(token);
            assertTrue(((Binder)get(host,"dropChannel")).transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            reply.readException();return reply.readBoolean();
        }finally{data.recycle();reply.recycle();}
    }
    private void assertSecondaryRejection(String changed,Object value)throws Exception {
        SystemUiHost host=host();String token=Wire.token();
        assertTrue(commit(host,token));assertTrue(((AtomicBoolean)get(host,"backPending")).get());assertTrue(cancelled.isEmpty());
        set(host,changed,value);idle(0);
        assertEquals(List.of(token),cancelled);assertFalse(((AtomicBoolean)get(host,"backPending")).get());
    }
    @Test public void acceptedCommitDispatchFailureCancelsPreparedBackAndReleasesPendingGate()throws Exception {
        Context throwing=new ContextWrapper(context){
            @Override public void sendBroadcast(Intent intent,String permission,Bundle options){throw new IllegalStateException("dispatch unavailable");}
        };
        SystemUiHost host=host(throwing);String token=Wire.token();
        assertTrue(commit(host,token));assertTrue(cancelled.isEmpty());idle(0);
        assertEquals(List.of(token),cancelled);assertFalse(((AtomicBoolean)get(host,"backPending")).get());
        assertTrue(commit(host,Wire.token())); // Failure must not wedge the next gesture.
    }
    @Test public void acceptedCommitDispatchesOnceWithoutCancellingAndReleasesPendingGate()throws Exception {
        SystemUiHost host=host();String token=Wire.token();
        int before=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size();
        assertTrue(commit(host,token));assertFalse(commit(host,Wire.token()));idle(0);
        List<Intent> broadcasts=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        assertEquals(before+1,broadcasts.size());Intent request=broadcasts.get(before);
        assertEquals("GESTURE",request.getStringExtra("op"));assertEquals(token,request.getStringExtra("token"));
        assertEquals(42,request.getIntExtra("taskId",-1));assertTrue(cancelled.isEmpty());
        assertFalse(((AtomicBoolean)get(host,"backPending")).get());
    }
    @Test public void disabledLongBackRejectsCommitWithoutTakingPendingGate()throws Exception {
        SystemUiHost host=host();Config config=(Config)get(host,"config");
        config.prefs.edit().putBoolean("longBackWindow",false).commit();
        assertFalse(commit(host,Wire.token()));
        assertFalse(((AtomicBoolean)get(host,"backPending")).get());idle(0);
        assertTrue(cancelled.isEmpty());
        config.prefs.edit().putBoolean("longBackWindow",true).commit();
        assertTrue(commit(host,Wire.token())); // Rejection must not wedge the next enabled gesture.
        idle(0);assertFalse(((AtomicBoolean)get(host,"backPending")).get());
    }
    @Test public void acceptedCommitCancelsPreparedBackWhenLongBackIsDisabledBeforeDispatch()throws Exception {
        SystemUiHost host=host();String token=Wire.token();
        assertTrue(commit(host,token));assertTrue(((AtomicBoolean)get(host,"backPending")).get());
        ((Config)get(host,"config")).prefs.edit().putBoolean("longBackWindow",false).commit();idle(0);
        assertEquals(List.of(token),cancelled);assertFalse(((AtomicBoolean)get(host,"backPending")).get());
    }
    @Test public void disabledLongBackDoesNotCreateHintOrEnterStageTwo()throws Exception {
        SystemUiHost host=host();
        ((Config)get(host,"config")).prefs.edit().putBoolean("longBackWindow",false).commit();
        int[] haptics={0};
        set(host,"left",new android.view.View(context){
            @Override public boolean performHapticFeedback(int feedbackConstant){haptics[0]++;return true;}
        });
        Method show=SystemUiHost.class.getDeclaredMethod("showSwipeHint",int.class);show.setAccessible(true);
        for(int stage:new int[]{1,2}){
            show.invoke(host,stage);
            assertNull(get(host,"swipeHint"));assertEquals(0,get(host,"hintStage"));assertEquals(0,haptics[0]);
        }
    }
    @Test public void acceptedCommitCancelsPreparedBackWhenHostIsCleanedBeforeDispatch()throws Exception {assertSecondaryRejection("cleaned",true);}
    @Test public void acceptedCommitCancelsPreparedBackWhenPolicyChangesBeforeDispatch()throws Exception {assertSecondaryRejection("systemFocus",true);}
    @Test public void acceptedCommitCancelsPreparedBackWhenBackendDisconnectsBeforeDispatch()throws Exception {assertSecondaryRejection("backendReady",false);}
    @Test public void acceptedCommitCancelsPreparedBackWhenDropSlotBecomesUnavailable()throws Exception {assertSecondaryRejection("backendState","opening");}
}
