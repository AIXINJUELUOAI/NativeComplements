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
    private void assertDenied(){assertEquals(1,bridge.evaluate(UID,154,4,1));assertEquals(1,bridge.evaluate(UID,155,4,1));}
    @Test public void onlyNativeForegroundAudioDenialChangesAndClearNotifiesExactOps(){
        update();assertTrue(service.calls.isEmpty());
        assertEquals(0,bridge.evaluate(UID,154,4,1));assertEquals(0,bridge.evaluate(UID,155,4,1));
        for(int raw:new int[]{0,1,2,3,5})for(int result:new int[]{0,1,2,3})assertEquals(result,bridge.evaluate(UID,154,raw,result));
        assertEquals(2,bridge.evaluate(UID,154,4,2));assertEquals(1,bridge.evaluate(UID,26,4,1));
        assertEquals(1,bridge.evaluate(UID+100000,154,4,1));
        idle(0);assertEquals(List.of("154:"+UID,"155:"+UID),service.calls);
        bridge.clear();assertDenied();idle(0);
        assertEquals(List.of("154:"+UID,"155:"+UID,"154:"+UID,"155:"+UID),service.calls);
    }
    @Test public void sharedUidAndChangedPackageMembershipAreNeverCached(){
        update();assertEquals(0,bridge.evaluate(UID,154,4,1));
        install("audio.continuation.sibling",UID);update();assertDenied();
        bridge.update(bridge.epoch(),Map.of(UID,new WindowAudioContinuation.Grant("wrong.pkg",lease)));assertDenied();
        bridge.update(bridge.epoch(),Map.of(UID+100000,new WindowAudioContinuation.Grant(PKG,lease)));assertDenied();
    }
    @Test public void singleInstalledMemberOfDeclaredSharedUidIsStillRejected()throws Exception{
        PackageInfo info=context.getPackageManager().getPackageInfo(PKG,0);info.sharedUserId="shared.audio.owner";
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);update();assertDenied();
    }
    @Test public void closingBridgeRevokesAndPermanentlyRejectsLaterUpdates(){
        update();idle(0);bridge.close();assertDenied();update();assertDenied();idle(0);assertEquals(4,service.calls.size());
    }
    @Test public void independentNativeHandlerExpiresEvenWithoutControllerRefresh(){
        update();idle(0);idle(999);assertEquals(0,bridge.evaluate(UID,154,4,1));
        idle(1);assertDenied();assertEquals(4,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void refreshExtendsSingleExpiryWithoutOldTimerClearingNewSnapshot(){
        update();idle(750);update();idle(250);assertEquals(0,bridge.evaluate(UID,154,4,1));
        assertEquals(2,service.calls.size());idle(749);assertEquals(0,bridge.evaluate(UID,154,4,1));
        idle(1);assertDenied();assertEquals(4,service.calls.size());
    }
    @Test public void expiredSnapshotRefreshNotifiesRegrantEvenBeforeExpiryWasDispatched(){
        update();idle(0);org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1001));
        assertDenied();update();assertEquals(0,bridge.evaluate(UID,154,4,1));idle(0);
        assertEquals(4,service.calls.size());assertEquals(0,bridge.evaluate(UID,154,4,1));
        idle(1000);assertDenied();assertEquals(6,service.calls.size());
    }
    @Test public void clearEpochRejectsCollectedOldUpdatesAndQueuedGrantNotificationsCannotRegrant(){
        long collected=bridge.epoch();update();bridge.clear();
        bridge.update(collected,Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));assertDenied();
        idle(0);assertDenied();assertEquals(4,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void clearDuringOutOfLockValidationRejectsPublicationAndUnlinksNewLease(){
        lease.onLink=bridge::clear;update();assertDenied();assertTrue(lease.deaths.isEmpty());
        idle(0);assertTrue(service.calls.isEmpty());
    }
    @Test public void leaseDeathRevokesSynchronouslyAndNotifiesNative(){
        update();idle(0);assertEquals(1,lease.deaths.size());lease.die();assertDenied();
        idle(0);assertEquals(4,service.calls.size());assertTrue(lease.deaths.isEmpty());
    }
    @Test public void deathDuringRegistrationCannotPublishAStaleGrant(){
        lease.dieOnLink=true;update();assertDenied();idle(0);assertTrue(lease.deaths.isEmpty());
    }
    @Test public void nativeNotificationFailureDisablesFurtherGrantsAndAttemptsRevocation(){
        service.reject=true;update();idle(0);assertDenied();assertTrue(service.calls.size()>=3);
        service.reject=false;update();assertDenied();
    }
    @Test public void missingNativeAbiDoesNotAuthorize()throws Exception{
        WindowAudioContinuation unavailable=new WindowAudioContinuation(access(),message->{});unavailable.attach(context,new Object());
        unavailable.update(unavailable.epoch(),Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));
        assertEquals(1,unavailable.evaluate(UID,154,4,1));
    }
}
