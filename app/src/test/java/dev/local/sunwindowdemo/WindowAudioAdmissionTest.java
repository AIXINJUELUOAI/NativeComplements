package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.*;
import android.view.WindowManager;
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
public class WindowAudioAdmissionTest {
    static final int UID=10273,UI_UID=10170;
    static final String PKG="audio.admission.owner";
    static final Object LOCK=new Object();
    public static final class Top {
        public boolean finishing;
        int uid=UID;boolean attached=true;
        public int getUid(){assertTrue(Thread.holdsLock(LOCK));return uid;}
        public boolean attachedToProcess(){assertTrue(Thread.holdsLock(LOCK));return attached;}
    }
    public static final class Task {
        public int mUserId;
        Display display=new Display();Top top=new Top();boolean attached=true;
        public Display getDisplayContent(){assertTrue(Thread.holdsLock(LOCK));return display;}
        public Top topRunningActivity(){assertTrue(Thread.holdsLock(LOCK));return top;}
        public boolean isAttached(){assertTrue(Thread.holdsLock(LOCK));return attached;}
    }
    public static final class Display {
        public int mDisplayId;
        public Window mNotificationShade;
        public Display getDisplayPolicy(){assertTrue(Thread.holdsLock(LOCK));return this;}
    }
    public static final class Window {
        final Display display;
        public final WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
        boolean visible=true;
        Window(Display display)throws Exception{this.display=display;mAttrs.type=2040;set(mAttrs,"privateFlags",0x80000);}
        public Object getTask(){assertTrue(Thread.holdsLock(LOCK));return null;}
        public Display getDisplayContent(){assertTrue(Thread.holdsLock(LOCK));return display;}
        public int getOwningUid(){assertTrue(Thread.holdsLock(LOCK));return UI_UID;}
        public boolean isVisible(){assertTrue(Thread.holdsLock(LOCK));return visible;}
        public boolean hideNonSystemOverlayWindowsWhenVisible(){assertTrue(Thread.holdsLock(LOCK));return true;}
    }
    public static final class Policy {
        public int mCurrentUserId;
        boolean keyguard=true;
        public boolean isKeyguardShowingAndNotOccluded(){assertTrue(Thread.holdsLock(LOCK));return keyguard;}
    }
    public static final class Wms {
        public final Policy mPolicy=new Policy();
        public final ArrayList<Window> mHidingNonSystemOverlayWindows=new ArrayList<>();
    }
    public static final class Atm { public boolean mShuttingDown; }
    public static final class Root {
        Object found;Display display;
        public Object anyTaskForId(int id,int mode){assertTrue(Thread.holdsLock(LOCK));assertEquals(7,id);assertEquals(0,mode);return found;}
        public Display getDefaultDisplay(){assertTrue(Thread.holdsLock(LOCK));return display;}
    }
    public static final class Lease extends Binder {
        boolean alive=true;
        @Override public boolean isBinderAlive(){return alive;}
    }
    public static final class NativeService {
        public final Handler mHandler=new Handler(Looper.getMainLooper());
        int notifications;
        final List<Integer> operations=new ArrayList<>();
        public void notifyWatchersOnDefaultDevice(int op,int uid){assertFalse(Thread.holdsLock(LOCK));assertEquals(UID,uid);notifications++;operations.add(op);}
    }
    public static final class NativePower {
        int renewals;
        public void addToTemporaryAllowList(String pkg,int reason,String tag,long duration){
            assertFalse(Thread.holdsLock(LOCK));assertEquals(PKG,pkg);assertEquals(60000L,duration);renewals++;
        }
    }
    private WindowEngine engine;
    private WindowEngine.Session session;
    private WindowAudioContinuation bridge;
    private NativeService service;
    private Task task;
    private Window shell;
    private Wms wms;
    private Atm atm;
    private Root root;
    private Lease lease;
    private WindowContinuation power;
    private NativePower nativePower;
    private Map<Integer,PowerManager.WakeLock> wakeLocks;
    private static void set(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type);
    }
    @Before public void setup()throws Exception{
        FrameworkAccess access=(FrameworkAccess)allocate(FrameworkAccess.class);
        set(access,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(access,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        set(access,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));
        access.overlayHidingWindows=Wms.class.getField("mHidingNonSystemOverlayWindows");
        Context context=RuntimeEnvironment.getApplication();PackageInfo info=new PackageInfo();info.packageName=PKG;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=PKG;info.applicationInfo.uid=UID;Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        task=new Task();shell=new Window(task.display);task.display.mNotificationShade=shell;
        wms=new Wms();wms.mHidingNonSystemOverlayWindows.add(shell);atm=new Atm();root=new Root();root.found=task;root.display=task.display;lease=new Lease();
        engine=new WindowEngine(access,message->{});session=new WindowEngine.Session();session.task=task;session.taskId=7;session.userId=0;session.uid=UID;session.pkg=PKG;session.lease=lease;
        session.frame="mini";session.edgeHidden=true;session.screenSuspended=true;session.prioritySuspended=true;
        set(engine,"lock",LOCK);set(engine,"root",root);set(engine,"wms",wms);set(engine,"atm",atm);set(engine,"context",context);set(engine,"uiUid",UI_UID);
        set(engine,"handler",new Handler(Looper.getMainLooper()));set(engine,"active",session);set(engine,"sessions",List.of(session));
        set(engine,"uiReady",true);set(engine,"uiLease",lease);
        power=(WindowContinuation)allocate(WindowContinuation.class);nativePower=new NativePower();wakeLocks=new HashMap<>();
        set(power,"context",context);set(power,"log",(java.util.function.Consumer<String>)message->{});
        set(power,"locks",wakeLocks);set(power,"renewed",new HashMap<Integer,Long>());set(power,"exemptions",nativePower);
        set(power,"allow",NativePower.class.getMethod("addToTemporaryAllowList",String.class,int.class,String.class,long.class));
        set(engine,"keepEdgeAwake",true);set(engine,"continuationReady",true);set(engine,"continuation",power);
        service=new NativeService();bridge=new WindowAudioContinuation(access,message->{});bridge.attach(context,service);engine.audioContinuation(bridge);
        // Deliberately do not mark audioWindowHooksReady: candidate eligibility is independent of installation readiness.
    }
    @After public void cleanup()throws Exception{
        Field field=WindowEngine.class.getDeclaredField("handler");field.setAccessible(true);
        ((Handler)field.get(engine)).removeCallbacksAndMessages(null);
        power.update(Map.of());bridge.clear();service.mHandler.removeCallbacksAndMessages(null);
    }
    private boolean candidate()throws Exception{
        Method method=WindowEngine.class.getDeclaredMethod("audioContinuationCandidate",WindowEngine.Session.class);method.setAccessible(true);
        synchronized(LOCK){return (Boolean)method.invoke(engine,session);}
    }
    private void rejectCandidate()throws Exception{assertFalse(candidate());}
    private void publish(long epoch){bridge.update(epoch,Map.of(UID,new WindowAudioContinuation.Grant(PKG,lease)));}
    private void grant(){
        publish(bridge.epoch());
        granted();
    }
    private void granted(){for(int op:new int[]{32,154,155})assertEquals("op="+op,0,bridge.evaluate(UID,op,4,1));}
    private void denied(){for(int op:new int[]{32,154,155})assertEquals("op="+op,1,bridge.evaluate(UID,op,4,1));}
    private void update()throws Exception{
        Method method=WindowEngine.class.getDeclaredMethod("updateContinuation");method.setAccessible(true);
        try{method.invoke(engine);}catch(InvocationTargetException failure){throw new AssertionError(failure.getCause());}
    }
    private void integratedUpdate()throws Exception{set(engine,"audioWindowHooksReady",true);update();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void candidateRequiresLiveOptedHiddenMiniRegardlessOfScreenWithoutReenablingPresentation()throws Exception{
        assertTrue(candidate());assertTrue(session.screenSuspended);assertTrue(session.prioritySuspended);
        session.screenSuspended=false;assertTrue(candidate());session.screenSuspended=true;
        session.edgeHidden=false;assertFalse(candidate());session.edgeHidden=true;
        session.frame="normal";assertFalse(candidate());session.frame="mini";
        session.restoring=true;assertFalse(candidate());session.restoring=false;
        set(engine,"keepEdgeAwake",false);assertFalse(candidate());set(engine,"keepEdgeAwake",true);
        set(engine,"uiReady",false);assertFalse(candidate());set(engine,"uiReady",true);
        set(engine,"uiLease",new Binder());assertFalse(candidate());set(engine,"uiLease",lease);
        lease.alive=false;assertFalse(candidate());lease.alive=true;
        atm.mShuttingDown=true;assertFalse(candidate());atm.mShuttingDown=false;
        assertTrue(candidate());assertFalse(session.restoring);
    }
    @Test public void candidateRequiresCurrentPrimaryUserExactDefaultDisplayTaskAndTopIdentity()throws Exception{
        // Isolate identity guards from the shell's separate current-user authentication check.
        wms.mHidingNonSystemOverlayWindows.clear();assertTrue(candidate());
        session.userId=1;session.uid=UID+100000;task.top.uid=session.uid;wms.mPolicy.mCurrentUserId=1;
        rejectCandidate();session.userId=0;session.uid=UID;task.top.uid=UID;wms.mPolicy.mCurrentUserId=0;
        session.uid=UID+100000;rejectCandidate();session.uid=UID;
        wms.mPolicy.mCurrentUserId=1;rejectCandidate();wms.mPolicy.mCurrentUserId=0;
        root.display=new Display();rejectCandidate();root.display=task.display;
        task.display.mDisplayId=1;rejectCandidate();task.display.mDisplayId=0;
        root.found=new Task();rejectCandidate();root.found=task;
        task.mUserId=1;rejectCandidate();task.mUserId=0;
        task.attached=false;rejectCandidate();task.attached=true;
        task.top.uid=UID+1;rejectCandidate();task.top.uid=UID;
        task.top.finishing=true;rejectCandidate();task.top.finishing=false;
        task.top.attached=false;rejectCandidate();task.top.attached=true;
        Top top=task.top;task.top=null;rejectCandidate();task.top=top;
        assertTrue(candidate());
    }
    @Test public void sensitiveNativeMutationRevokesAtBothEdgesAndInhibitsOldAttrsRegrant()throws Exception{
        assertTrue(candidate());
        for(int flag:new int[]{0x2000,8}){
            grant();long before=bridge.epoch();WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();
            attrs.flags=flag==0x2000?0x2000:0;set(attrs,"inputFeatures",flag==8?8:0);
            boolean security; synchronized(LOCK){security=engine.audioWindowAttributesChanging(attrs);}
            assertTrue(security);long during=bridge.epoch();
            try{
                denied();assertTrue(during>before);assertFalse(candidate());
                // The original native shell attrs are still benign: mutation has not reached WindowState yet.
                assertEquals(0,shell.mAttrs.flags);assertEquals(0,service.notifications);
                publish(before);denied();
            }finally{engine.audioWindowAttributesChanged(security);}
            assertTrue(bridge.epoch()>during);publish(during);denied();publish(before);denied();
            assertTrue(candidate());assertEquals(0,service.notifications);
        }
    }
    @Test public void nestedSecurityMutationsCannotReenableCandidateUntilBothNativeCallsFinish()throws Exception{
        grant();WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();attrs.flags=0x2000;
        boolean outer=engine.audioWindowAttributesChanging(attrs),inner=false;
        try{
            inner=engine.audioWindowAttributesChanging(attrs);assertTrue(outer);assertTrue(inner);assertFalse(candidate());denied();
            engine.audioWindowAttributesChanged(inner);inner=false;assertFalse(candidate());denied();
        }finally{engine.audioWindowAttributesChanged(inner);engine.audioWindowAttributesChanged(outer);}
        assertTrue(candidate());denied();
    }
    @Test public void enginePublishesPowerAndAllAudioOpsAcrossScreenCyclesAndSameUidPlayerReplacement()throws Exception{
        session.screenSuspended=false;shell.visible=false;wms.mPolicy.keyguard=false;
        integratedUpdate();granted();assertEquals(List.of(32,154,155),service.operations);
        assertEquals(1,nativePower.renewals);PowerManager.WakeLock wake=wakeLocks.get(UID);assertNotNull(wake);assertTrue(wake.isHeld());
        for(int tick=0;tick<100;tick++){
            boolean locked=tick%2==0;session.screenSuspended=locked;shell.visible=locked;wms.mPolicy.keyguard=locked;
            task.top=new Top(); // Native player activities may replace one another without changing owner UID.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            update();Shadows.shadowOf(Looper.getMainLooper()).idle();granted();
            assertTrue(session.edgeHidden);assertEquals("mini",session.frame);assertTrue(session.prioritySuspended);
            for(int op:new int[]{32,154,155})assertEquals(1,bridge.evaluate(UID+1,op,4,1));
            assertSame(wake,wakeLocks.get(UID));assertTrue(wake.isHeld());
        }
        assertEquals(3,service.notifications);assertEquals(1,nativePower.renewals);
        // Stop refreshing: the independent native-handler TTL must revoke every operation.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WindowAudioContinuation.VALID_MS));denied();
        assertEquals(List.of(32,154,155,32,154,155),service.operations);
        update();Shadows.shadowOf(Looper.getMainLooper()).idle();granted();assertEquals(9,service.notifications);
    }
    @Test public void completedNestedNativeMutationFreshlySamplesAtOutermostEdgeOnly()throws Exception{
        integratedUpdate();granted();WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();attrs.flags=0x2000;
        boolean outer=engine.audioWindowAttributesChanging(attrs),inner=engine.audioWindowAttributesChanging(attrs);
        assertTrue(outer);assertTrue(inner);denied();
        update();Shadows.shadowOf(Looper.getMainLooper()).idle();denied();
        engine.audioWindowAttributesChanged(inner);Shadows.shadowOf(Looper.getMainLooper()).idle();denied();
        engine.audioWindowAttributesChanged(outer);denied();Shadows.shadowOf(Looper.getMainLooper()).idle();granted();
        assertEquals(List.of(32,154,155,32,154,155,32,154,155),service.operations);
        assertEquals(1,nativePower.renewals);assertTrue(wakeLocks.get(UID).isHeld());
    }
    @Test public void persistentNativeAuthenticationDeniesThenSameWindowClearingRecoversWithoutListEvent()throws Exception{
        integratedUpdate();granted();Window original=task.display.mNotificationShade;
        WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();attrs.flags=0x2000;
        boolean security=engine.audioWindowAttributesChanging(attrs);shell.mAttrs.flags=0x2000;
        engine.audioWindowAttributesChanged(security);Shadows.shadowOf(Looper.getMainLooper()).idle();denied();
        assertTrue(session.continuationPrioritySuspended);assertTrue(wakeLocks.isEmpty());
        security=engine.audioWindowAttributesChanging(attrs);shell.mAttrs.flags=0;
        engine.audioWindowAttributesChanged(security);denied();Shadows.shadowOf(Looper.getMainLooper()).idle();granted();
        assertSame(original,task.display.mNotificationShade);assertSame(original,wms.mHidingNonSystemOverlayWindows.get(0));
        assertFalse(session.continuationPrioritySuspended);assertTrue(wakeLocks.get(UID).isHeld());assertEquals(2,nativePower.renewals);
        assertEquals(List.of(32,154,155,32,154,155,32,154,155),service.operations);
    }
    @Test public void engineWithdrawsPublicationOnExitIdentityChangesAndDeadOrForeignLease()throws Exception{
        integratedUpdate();granted();PowerManager.WakeLock wake=wakeLocks.get(UID);
        session.edgeHidden=false;integratedUpdate();denied();assertFalse(wake.isHeld());assertTrue(wakeLocks.isEmpty());
        session.edgeHidden=true;integratedUpdate();granted();
        session.frame="normal";integratedUpdate();denied();session.frame="mini";
        task.top.uid=UID+1;integratedUpdate();denied();task.top.uid=UID;
        task.mUserId=1;integratedUpdate();denied();task.mUserId=0;
        task.display.mDisplayId=1;integratedUpdate();denied();task.display.mDisplayId=0;
        root.found=new Task();integratedUpdate();denied();root.found=task;
        set(engine,"uiLease",new Binder());integratedUpdate();denied();set(engine,"uiLease",lease);
        integratedUpdate();granted();lease.alive=false;integratedUpdate();denied();assertTrue(wakeLocks.isEmpty());
        set(engine,"sessions",List.of());integratedUpdate();denied();
        for(int op:new int[]{32,154,155})assertEquals(1,bridge.evaluate(UID+100000,op,4,1));
        assertTrue(service.operations.stream().allMatch(op->op==32||op==154||op==155));
    }
    @Test public void mixedShellAndAuthenticationRevokesBothPowerAndPublishedAudio()throws Exception{
        integratedUpdate();granted();Window auth=new Window(task.display);auth.mAttrs.flags=0x2000;
        wms.mHidingNonSystemOverlayWindows.add(auth);integratedUpdate();denied();assertTrue(wakeLocks.isEmpty());
        assertEquals(List.of(32,154,155,32,154,155),service.operations);
        wms.mHidingNonSystemOverlayWindows.remove(auth);integratedUpdate();granted();assertTrue(wakeLocks.get(UID).isHeld());
        assertEquals(9,service.notifications);
    }
    @Test public void sameWindowSameNativeListSecurityMutationRevokesSynchronously()throws Exception{
        assertTrue(candidate());Window original=task.display.mNotificationShade;
        for(int flag:new int[]{0x2000,8}){
            shell.mAttrs.flags=0;set(shell.mAttrs,"inputFeatures",0);
            assertTrue(candidate());grant();long epoch=bridge.epoch();
            shell.mAttrs.flags=flag==0x2000?0x2000:0;set(shell.mAttrs,"inputFeatures",flag==8?8:0);
            engine.priorityWindowsChanged();
            assertSame(original,task.display.mNotificationShade);assertSame(original,wms.mHidingNonSystemOverlayWindows.get(0));
            denied();assertTrue(session.continuationPrioritySuspended);assertFalse(candidate());assertTrue(bridge.epoch()>epoch);assertEquals(0,service.notifications);
        }
    }
}
