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
            data.writeInterfaceToken(BackBridge.CHANNEL);data.writeInt(42);data.writeString("example.app");data.writeString(token);
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

    @Test public void nativeCancellationMatchesOnlyTheCommittedHandlersTerminalUp()throws Exception{
        Object owner=new Object();
        Method cancel=Class.forName("dev.local.sunwindowdemo.BackBridge").getDeclaredMethod("cancelNativeUp",Object.class,long.class,Object.class,android.view.MotionEvent.class);
        cancel.setAccessible(true);
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL}){
            var event=android.view.MotionEvent.obtain(10,20,action,0,0,0);
            try{
                assertEquals(action==android.view.MotionEvent.ACTION_UP,cancel.invoke(null,owner,10,owner,event));
                assertEquals(false,cancel.invoke(null,owner,11,owner,event));
                assertEquals(false,cancel.invoke(null,owner,10,new Object(),event));
            }finally{event.recycle();}
        }
    }

    private Object stroke(String token,Object controller)throws Exception {
        Class<?> type=Class.forName("dev.local.sunwindowdemo.BackBridge$Stroke");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object stroke=constructor.newInstance();set(stroke,"handoff",new BackHandoffState(0));set(stroke,"token",token);set(stroke,"controller",controller);
        set(stroke,"task",42);set(stroke,"pkg","dev.example.target");return stroke;
    }
    private void callStroke(Object bridge,String method,Object stroke)throws Exception {
        Method target=bridge.getClass().getDeclaredMethod(method,stroke.getClass());target.setAccessible(true);target.invoke(bridge,stroke);
    }
    private void waitNative(Object bridge,Object stroke)throws Exception {
        Method target=bridge.getClass().getDeclaredMethod("waitForNativeBack",stroke.getClass(),int.class);
        target.setAccessible(true);target.invoke(bridge,stroke,0);
    }
    private static final class NativeTransitions {
        Runnable callback;
        boolean immediate;
        public void runOnIdle(Runnable callback){this.callback=callback;if(immediate)callback.run();}
    }
    private static final class NativeController {
        Object mBackNavigationInfo;
        boolean mPostCommitAnimationInProgress;
        final NativeTransitions mTransitions=new NativeTransitions();
        final List<Runnable> shell=new ArrayList<>();
        final java.util.concurrent.Executor mShellExecutor=shell::add;
        void runShell(){shell.remove(0).run();}
    }
    private Object liveBridge(List<String> committed,boolean accepted)throws Exception {
        Object bridge=bridge();set(bridge,"enabled",true);set(bridge,"lease",new Binder());
        set(bridge,"sink",new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                assertEquals(IBinder.FIRST_CALL_TRANSACTION,code);assertEquals(0,flags);
                data.enforceInterface(BackBridge.CHANNEL);assertEquals(42,data.readInt());
                assertEquals("dev.example.target",data.readString());committed.add(data.readString());data.enforceNoDataAvail();
                reply.writeNoException();reply.writeBoolean(accepted);return true;
            }
        });return bridge;
    }
    @Test public void shellIdleHandoffCommitsOnMainOnceDespiteDuplicateCallbacks()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object stroke=stroke(token,controller);
        set(bridge,"settling",stroke);waitNative(bridge,stroke);
        assertNull(controller.mTransitions.callback);assertTrue(committed.isEmpty());
        controller.runShell();assertNotNull(controller.mTransitions.callback);assertTrue(committed.isEmpty());
        controller.mTransitions.callback.run();controller.mTransitions.callback.run();
        assertTrue(committed.isEmpty());idle(0);
        assertEquals(List.of(token),committed);assertNull(get(bridge,"settling"));
        controller.mTransitions.callback.run();idle(650);
        assertEquals(List.of(token),committed);assertTrue(cancelled.isEmpty());
    }
    @Test public void synchronousShellAndIdleCallbacksStillPostCommitToMainWithoutDeadlock()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();controller.mTransitions.immediate=true;
        String token=Wire.token();Object stroke=stroke(token,controller);set(bridge,"settling",stroke);
        set(controller,"mShellExecutor",(java.util.concurrent.Executor)Runnable::run);
        waitNative(bridge,stroke);assertTrue(committed.isEmpty());idle(0);
        assertEquals(List.of(token),committed);idle(650);assertTrue(cancelled.isEmpty());
    }
    @Test public void idleTimeoutRejectsLateCallbackAndCancelsOnlyOnce()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object stroke=stroke(token,controller);
        set(bridge,"settling",stroke);set(bridge,"pending",stroke);waitNative(bridge,stroke);controller.runShell();
        idle(649);assertTrue(cancelled.isEmpty());idle(1);
        assertEquals(List.of(token),cancelled);assertNull(get(bridge,"settling"));assertNull(get(bridge,"pending"));
        controller.mTransitions.callback.run();controller.mTransitions.callback.run();idle(0);
        callStroke(bridge,"abort",stroke);assertTrue(committed.isEmpty());assertEquals(List.of(token),cancelled);
    }
    @Test public void newGenerationLeavesNewTicketIntactWhenOldIdleCallbackArrives()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object old=stroke(token,controller);
        set(bridge,"settling",old);waitNative(bridge,old);controller.runShell();
        // Same native invalidation used at a new DOWN, before installing the next owner ticket.
        callStroke(bridge,"abort",old);set(bridge,"generation",1);
        Object next=stroke(Wire.token(),null);set(bridge,"settling",next);set(bridge,"pending",next);
        controller.mTransitions.callback.run();idle(650);
        assertSame(next,get(bridge,"settling"));assertSame(next,get(bridge,"pending"));
        assertTrue(committed.isEmpty());assertEquals(List.of(token),cancelled);
    }
    @Test public void lateShellCheckCannotRegisterIdleAfterOldTicketIsAborted()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object old=stroke(token,controller);
        set(bridge,"settling",old);waitNative(bridge,old);callStroke(bridge,"abort",old);
        controller.runShell();idle(0);
        assertNull(controller.mTransitions.callback);assertTrue(committed.isEmpty());assertEquals(List.of(token),cancelled);
    }
    @Test public void noControllerPathAlsoRejectsDuplicateWaitAndCommitCalls()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        String token=Wire.token();Object stroke=stroke(token,null);set(bridge,"settling",stroke);
        waitNative(bridge,stroke);waitNative(bridge,stroke);idle(0);callStroke(bridge,"commit",stroke);
        callStroke(bridge,"abort",stroke);
        assertEquals(List.of(token),committed);assertTrue(cancelled.isEmpty());
    }
    @Test public void rejectedCommitCancelsSendingTicketOnceDespiteDuplicateCallbacks()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,false);
        String token=Wire.token();Object stroke=stroke(token,null);set(bridge,"settling",stroke);
        waitNative(bridge,stroke);idle(0);callStroke(bridge,"commit",stroke);callStroke(bridge,"abort",stroke);
        assertEquals(List.of(token),committed);assertEquals(List.of(token),cancelled);
    }
    @Test public void disabledLeaseBeforeIdleCancelsOldTokenWithoutCommitting()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object stroke=stroke(token,controller);
        set(bridge,"settling",stroke);waitNative(bridge,stroke);controller.runShell();set(bridge,"enabled",false);
        controller.mTransitions.callback.run();idle(650);
        assertTrue(committed.isEmpty());assertEquals(List.of(token),cancelled);
    }

    @Test public void changedGenerationAtIdleCannotClearNewSettlingTicket()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        NativeController controller=new NativeController();String token=Wire.token();Object old=stroke(token,controller);
        set(bridge,"settling",old);waitNative(bridge,old);controller.runShell();
        set(bridge,"generation",1);Object next=stroke(Wire.token(),null);set(bridge,"settling",next);set(bridge,"pending",next);
        controller.mTransitions.callback.run();idle(650);
        assertSame(next,get(bridge,"settling"));assertSame(next,get(bridge,"pending"));
        assertTrue(committed.isEmpty());assertEquals(List.of(token),cancelled);
    }
    @Test public void commitExceptionCancelsExactSendingTicketAndDoesNotRetry()throws Exception {
        List<String> committed=new ArrayList<>();Object bridge=liveBridge(committed,true);
        set(bridge,"sink",new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
                throw new RemoteException("disconnected");
            }
        });
        String token=Wire.token();Object stroke=stroke(token,null);set(bridge,"settling",stroke);
        waitNative(bridge,stroke);idle(0);callStroke(bridge,"commit",stroke);callStroke(bridge,"abort",stroke);
        assertEquals(List.of(token),cancelled);assertNull(get(bridge,"settling"));
    }

    @Test public void nativeWaitTimeoutReleasesSettlingAndPreparedCover()throws Exception{
        Object bridge=bridge();Class<?> type=Class.forName("dev.local.sunwindowdemo.BackBridge$Stroke");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object stroke=constructor.newInstance();set(stroke,"handoff",new BackHandoffState(0));String token=Wire.token();set(stroke,"token",token);
        set(bridge,"enabled",true);set(bridge,"lease",new Binder());set(bridge,"sink",new Binder());
        set(bridge,"settling",stroke);set(bridge,"pending",stroke);
        Method wait=bridge.getClass().getDeclaredMethod("waitForNativeBack",type,int.class);wait.setAccessible(true);
        wait.invoke(bridge,stroke,40);
        assertNull(get(bridge,"settling"));assertNull(get(bridge,"pending"));assertEquals(List.of(token),cancelled);
    }
}
