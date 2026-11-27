package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.pm.*;
import android.os.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowAudioContinuationTest {
    static final int UID=10273;
    static final String PKG="audio.continuation.owner";
    public static final class Service {
        public final Handler mHandler=new Handler(Looper.getMainLooper());
        final List<String> calls=new ArrayList<>();boolean reject;
        public void notifyWatchersOnDefaultDevice(int op,int uid){calls.add(op+":"+uid);if(reject)throw new IllegalStateException("native notify unavailable");}
    }
    static final class Lease extends Binder {
        final Set<DeathRecipient> deaths=new HashSet<>();boolean alive=true,dieOnLink;Runnable onLink;
        @Override public boolean isBinderAlive(){return alive;}
        @Override public void linkToDeath(DeathRecipient death,int flags){deaths.add(death);if(onLink!=null)onLink.run();if(dieOnLink)die();}
        @Override public boolean unlinkToDeath(DeathRecipient death,int flags){return deaths.remove(death);}
        void die(){alive=false;for(DeathRecipient death:List.copyOf(deaths))death.binderDied();}
    }
    Context context;Service service;WindowAudioContinuation bridge;Lease lease;
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        for(String name:new String[]{"methods","fields"}){Field field=FrameworkAccess.class.getDeclaredField(name);field.setAccessible(true);field.set(a,new java.util.concurrent.ConcurrentHashMap<>());}
        return a;
    }
    private void install(String pkg,int uid){
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();install(PKG,UID);service=new Service();lease=new Lease();
        bridge=new WindowAudioContinuation(access(),message->{});bridge.attach(context,service);
    }
    private void update(){bridge.update(bridge.epoch(),Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));}
    private void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    private static final int[] OPS={32,154,155};
    private void assertDenied(){for(int op:OPS)assertEquals(1,bridge.evaluate(UID,op,4,1));}
    private void assertAllowed(){for(int op:OPS)assertEquals(0,bridge.evaluate(UID,op,4,1));}
    @Test public void onlyNativeForegroundAudioDenialChangesAndClearNotifiesExactOps(){
        update();assertTrue(service.calls.isEmpty());
        assertAllowed();
        for(int op:OPS) {
            for(int raw:new int[]{0,1,2,3,5})for(int result:new int[]{0,1,2,3,4})assertEquals(result,bridge.evaluate(UID,op,raw,result));
            for(int result:new int[]{0,2,3,4})assertEquals(result,bridge.evaluate(UID,op,4,result));
            assertEquals(1,bridge.evaluate(UID+100000,op,4,1));assertEquals(1,bridge.evaluate(UID+1,op,4,1));
        }
        assertEquals(1,bridge.evaluate(UID,26,4,1));
        idle(0);assertEquals(List.of("32:"+UID,"154:"+UID,"155:"+UID),service.calls);
        bridge.clear();assertDenied();idle(0);
        assertEquals(List.of("32:"+UID,"154:"+UID,"155:"+UID,"32:"+UID,"154:"+UID,"155:"+UID),service.calls);
    }
    @Test public void sharedUidAndChangedPackageMembershipAreNeverCached(){
        update();assertAllowed();
        install("audio.continuation.sibling",UID);update();assertDenied();
        bridge.update(bridge.epoch(),Map.of(UID,new WindowAudioContinuation.Grant("wrong.pkg",lease)));assertDenied();
        bridge.update(bridge.epoch(),Map.of(UID+100000,new WindowAudioContinuation.Grant(PKG,lease)));assertDenied();
    }
    @Test public void singleInstalledMemberOfDeclaredSharedUidIsStillRejected()throws Exception{
        PackageInfo info=context.getPackageManager().getPackageInfo(PKG,0);info.sharedUserId="shared.audio.owner";
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);update();assertDenied();
    }
    @Test public void closingBridgeRevokesAndPermanentlyRejectsLaterUpdates(){
        update();idle(0);bridge.close();assertDenied();update();assertDenied();idle(0);assertEquals(6,service.calls.size());
    }
    @Test public void independentNativeHandlerExpiresEvenWithoutControllerRefresh(){
        update();idle(0);idle(999);assertAllowed();
        idle(1);assertDenied();assertEquals(6,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void refreshExtendsSingleExpiryWithoutOldTimerClearingNewSnapshot(){
        update();idle(750);update();idle(250);assertAllowed();
        assertEquals(3,service.calls.size());idle(749);assertAllowed();
        idle(1);assertDenied();assertEquals(6,service.calls.size());
    }
    @Test public void expiredSnapshotRefreshNotifiesRegrantEvenBeforeExpiryWasDispatched(){
        update();idle(0);org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1001));
        assertDenied();update();assertAllowed();idle(0);
        assertEquals(6,service.calls.size());assertAllowed();
        idle(1000);assertDenied();assertEquals(9,service.calls.size());
    }
    @Test public void clearEpochRejectsCollectedOldUpdatesAndQueuedGrantNotificationsCannotRegrant(){
        long collected=bridge.epoch();update();bridge.clear();
        bridge.update(collected,Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));assertDenied();
        idle(0);assertDenied();assertEquals(6,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void clearDuringOutOfLockValidationRejectsPublicationAndUnlinksNewLease(){
        lease.onLink=bridge::clear;update();assertDenied();assertTrue(lease.deaths.isEmpty());
        idle(0);assertTrue(service.calls.isEmpty());
    }
    @Test public void leaseDeathRevokesSynchronouslyAndNotifiesNative(){
        update();idle(0);assertEquals(1,lease.deaths.size());lease.die();assertDenied();
        idle(0);assertEquals(6,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void deathDuringRegistrationCannotPublishAStaleGrant(){
        lease.dieOnLink=true;update();assertDenied();idle(0);assertTrue(lease.deaths.isEmpty());
    }
    @Test public void nativeNotificationFailureDisablesFurtherGrantsAndAttemptsRevocation(){
        service.reject=true;update();idle(0);assertDenied();assertTrue(service.calls.size()>=3);
        service.reject=false;update();assertDenied();
    }
    @Test public void refreshDiagnosticsAreNumericBoundedAndCannotAffectAuthorization()throws Exception{
        List<String> logs=new ArrayList<>();
        bridge=new WindowAudioContinuation(access(),line->{logs.add(line);if(line.startsWith("EDGE_AUDIO_REFRESH"))throw new Error("private sink");});
        bridge.attach(context,service);
        bridge.update(bridge.epoch(),Map.of());assertEquals(1,logs.size()); // READY only for an idle feature.
        update();assertAllowed();for(int i=0;i<10;i++)update();
        assertEquals(1,logs.stream().filter(line->line.startsWith("EDGE_AUDIO_REFRESH")).count());
        String refresh=logs.stream().filter(line->line.startsWith("EDGE_AUDIO_REFRESH")).findFirst().orElseThrow();
        assertTrue(refresh.contains("desired=1 granted=1 epochValid=true ttlMillis=1000 disabled=false"));
        assertFalse(refresh.contains(PKG));assertFalse(refresh.contains("private sink"));
        idle(30_000);assertDenied();update();assertAllowed();
        assertEquals(2,logs.stream().filter(line->line.startsWith("EDGE_AUDIO_REFRESH")).count());
    }
    public static final class Evaluation {
        public int evalMode(int uid,int op,int mode){return mode;}
        public int callerOne(int op,int mode){return mode;}
        public int callerTwo(int uid,int op,int mode){return mode;}
    }
    @Test public void nativeEvaluationHookPreservesOriginalCallsAndRollbackDisablesHalfInstallation()throws Throwable{
        Map<String,io.github.libxposed.api.XposedInterface.Hooker> hooks=new HashMap<>();
        int[] nativeCalls={0},deopts={0},unhooks={0};boolean[] deoptAllowed={true};
        io.github.libxposed.api.XposedModule module=new io.github.libxposed.api.XposedModule(){};
        module.attachFramework((io.github.libxposed.api.XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{io.github.libxposed.api.XposedInterface.class},(p,m,args)->{
                if(m.getName().equals("deoptimize")){deopts[0]++;return deoptAllowed[0];}
                if(m.getName().equals("hook"))return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{io.github.libxposed.api.XposedInterface.HookBuilder.class},(bp,bm,ba)->{
                        if(bm.getName().equals("setExceptionMode"))return bp;
                        if(bm.getName().equals("intercept")){
                            hooks.put("eval",(io.github.libxposed.api.XposedInterface.Hooker)ba[0]);
                            return new io.github.libxposed.api.XposedInterface.HookHandle(){
                                public Executable getExecutable(){return (Executable)args[0];}
                                public void unhook(){unhooks[0]++;hooks.clear();}
                            };
                        }
                        throw new AssertionError(bm);
                    });
                throw new AssertionError(m);
            }));
        Method eval=Evaluation.class.getMethod("evalMode",int.class,int.class,int.class);
        List<Method> callers=List.of(Evaluation.class.getMethod("callerOne",int.class,int.class),Evaluation.class.getMethod("callerTwo",int.class,int.class,int.class));
        assertTrue(bridge.installEvaluation(module,eval,callers,new ArrayList<>()));assertEquals(2,deopts[0]);update();
        for(int op:OPS){
            assertEquals(0,invoke(hooks.get("eval"),new Object[]{UID,op,4},()->{nativeCalls[0]++;return 1;}));
            Integer original=Integer.valueOf(2);assertSame(original,invoke(hooks.get("eval"),new Object[]{UID,op,4},()->{nativeCalls[0]++;return original;}));
        }
        Error original=new AssertionError("native private error");
        try{invoke(hooks.get("eval"),new Object[]{UID,32,4},()->{nativeCalls[0]++;throw original;});fail();}catch(Throwable actual){assertSame(original,actual);}
        Integer untouched=Integer.valueOf(1);
        assertSame(untouched,invoke(hooks.get("eval"),new Object[]{UID},()->{nativeCalls[0]++;return untouched;})); // Observation fault must preserve native.
        assertEquals(8,nativeCalls[0]);
        WindowAudioContinuation failing=new WindowAudioContinuation(access(),line->{});
        List<io.github.libxposed.api.XposedInterface.HookHandle> rejected=new ArrayList<>(){
            @Override public boolean addAll(Collection<? extends io.github.libxposed.api.XposedInterface.HookHandle> values){super.addAll(values);throw new Error("handoff rejected");}
        };
        assertFalse(failing.installEvaluation(module,eval,callers,rejected));assertEquals(1,unhooks[0]);assertTrue(hooks.isEmpty());assertTrue(rejected.isEmpty());
        failing.attach(context,service);failing.update(failing.epoch(),Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));
        for(int op:OPS)assertEquals(1,failing.evaluate(UID,op,4,1));
        deoptAllowed[0]=false;assertFalse(failing.installEvaluation(module,eval,callers,new ArrayList<>()));assertTrue(hooks.isEmpty());
    }
    interface Native {Object run()throws Throwable;}
    private Object invoke(io.github.libxposed.api.XposedInterface.Hooker hook,Object[] args,Native body)throws Throwable{
        return hook.intercept((io.github.libxposed.api.XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{io.github.libxposed.api.XposedInterface.Chain.class},(p,m,a)->{
                if(m.getName().equals("getArg"))return args[(Integer)a[0]];
                if(m.getName().equals("proceed"))return body.run();
                throw new AssertionError(m);
            }));
    }
    @Test public void missingNativeAbiDoesNotAuthorize()throws Exception{
        WindowAudioContinuation unavailable=new WindowAudioContinuation(access(),message->{});unavailable.attach(context,new Object());
        unavailable.update(unavailable.epoch(),Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));
        for(int op:OPS)assertEquals(1,unavailable.evaluate(UID,op,4,1));
    }
}
