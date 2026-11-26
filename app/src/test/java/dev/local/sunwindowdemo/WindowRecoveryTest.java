package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.*;
import android.util.AtomicFile;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowRecoveryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private WindowEngine engine;
    private AtomicFile journal;
    private Object session;
    private final List<String> logs=new ArrayList<>();
    private final Recents recents=new Recents();
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(engine,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    public static final class Recents {
        boolean loaded;
        public boolean isRecentTasksLoaded(int user){assertEquals(0,user);return loaded;}
    }
    public static final class Atm {
        public final Recents mRecentTasks;
        int deferred,continued;
        Atm(Recents value){mRecentTasks=value;}
        public void deferWindowLayout(){deferred++;}
        public void continueWindowLayout(){continued++;}
    }
    public static final class Root {
        Object task;
        int visibleCalls,resumeCalls;
        final Rect nativeCanvas=new Rect(0,0,1200,2133);
        public Object anyTaskForId(int id,int mode){assertEquals(192,id);assertTrue(mode==0||mode==1);return task;}
        public void ensureActivitiesVisible(){visibleCalls++;((Task)task).mLastNonFullscreenBounds=new Rect(nativeCanvas);}
        public void resumeFocusedTasksTopActivities(){resumeCalls++;}
    }
    public static final class Wms {
        int traversals;
        public void requestTraversal(){traversals++;}
    }
    public static final class Animator { public boolean hasLeash(){return false;} }
    public static final class TransitionController { public boolean inTransition(Task task){return false;} }
    public static class Task {
        public int mTaskId=192,mUserId=0;
        public final Animator mSurfaceAnimator=new Animator();
        public final TransitionController mTransitionController=new TransitionController();
        public Rect mLastNonFullscreenBounds=new Rect(1,2,301,502);
        public android.content.ComponentName realActivity=new android.content.ComponentName("example.old","example.old.Main");
        public String baseIdentifier="old-page";
        final Configuration requested=new Configuration();
        int configWrites,compatVisits;
        public Intent getBaseIntent(){return new Intent().setIdentifier(baseIdentifier);}
        public boolean isAttached(){return false;}
        public void onRequestedOverrideConfigurationChanged(Configuration value){configWrites++;requested.setTo(value);}
        public Configuration getRequestedOverrideConfiguration(){return requested;}
        public void forAllActivities(java.util.function.Consumer<Object> consumer){compatVisits++;}
    }
    @Before public void setup()throws Throwable{
        Context context=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));set(access,"wc",Task.class);
        engine=new WindowEngine(access,logs::add);set(engine,"context",context);set(engine,"root",new Root());
        set(engine,"lock",new Object());set(engine,"atm",new Atm(recents));set(engine,"uiLease",new Binder());
        set(engine,"handler",new Handler(Looper.getMainLooper()));set(engine,"openGeneration",7L);
        journal=new AtomicFile(temporary.newFile("recovery.json"));journal.delete();set(engine,"journal",journal);
        Class<?> type=Class.forName(WindowEngine.class.getName()+"$Session");Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);session=constructor.newInstance();
        set(session,"taskId",192);set(session,"userId",0);set(session,"pkg","example.old");set(session,"component","example.old/.Main");
        set(session,"token",Wire.token());set(session,"baseId","old-page");set(session,"crop",new Point(1200,2600));set(session,"bounds",new Rect());set(session,"originalConfig",new Configuration());
        call("save",new Class<?>[]{type},session);
        set(engine,"sessions",List.of()); // Simulate a system restart: only the durable journal survives.
    }
    private Intent request(){return new Intent().putExtra("package","example.next").putExtra("token",Wire.token()).putExtra("generation",7L);}
    private boolean waitFor(Intent request,Runnable resume)throws Throwable{
        return (Boolean)call("waitForRecovery",new Class<?>[]{Intent.class,Runnable.class},request,resume);
    }
    private void idle(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}

    @Test public void boundsHistoryJournalPreservesUnknownNullEmptyAndConcreteBounds()throws Throwable{
        org.json.JSONObject legacy=(org.json.JSONObject)call("encode",new Class<?>[]{session.getClass()},session);
        assertFalse(legacy.has("lastNonFullscreenBounds"));
        Object unknown=call("decode",new Class<?>[]{org.json.JSONObject.class},legacy);
        assertFalse((Boolean)get(unknown,"restoreNonFullscreenBounds"));
        set(session,"restoreNonFullscreenBounds",true);
        for(Rect bounds:new Rect[]{null,new Rect(),new Rect(20,30,620,1030)}){
            set(session,"lastNonFullscreenBounds",bounds);
            org.json.JSONObject saved=(org.json.JSONObject)call("encode",new Class<?>[]{session.getClass()},session);
            assertTrue(saved.has("lastNonFullscreenBounds"));
            Object restored=call("decode",new Class<?>[]{org.json.JSONObject.class},saved);
            assertTrue((Boolean)get(restored,"restoreNonFullscreenBounds"));
            assertEquals(bounds,get(restored,"lastNonFullscreenBounds"));
        }
        legacy.put("lastNonFullscreenBounds","bad rectangle");
        try{call("decode",new Class<?>[]{org.json.JSONObject.class},legacy);fail("Malformed cache must reject recovery");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("边界缓存"));}
    }
    @Test public void successfulRestoreWritesKnownBoundsHistoryAfterNativeLayoutButLeavesLegacyUnknownAlone()throws Throwable{
        Root root=(Root)get(engine,"root");Atm atm=(Atm)get(engine,"atm");Wms wms=new Wms();set(engine,"wms",wms);
        recents.loaded=true;set(session,"mode",1);set(session,"topState",2);
        Configuration original=(Configuration)get(session,"originalConfig");Object window=get(original,"windowConfiguration");
        window.getClass().getDeclaredMethod("setWindowingMode",int.class).invoke(window,1);
        Method top=window.getClass().getDeclaredMethod("setAlwaysOnTop",int.class);top.setAccessible(true);top.invoke(window,2);
        Rect concrete=new Rect(20,30,620,1030);
        for(int state=0;state<4;state++){
            Task task=new Task();root.task=task;set(session,"component",task.realActivity.flattenToString());
            Rect expected=state==1?new Rect():state>=2?new Rect(concrete):null;
            set(session,"restoreNonFullscreenBounds",state<3);set(session,"lastNonFullscreenBounds",expected);
            // ensureActivitiesVisible overwrites the cache after configuration restoration; only the tail write can win.
            assertEquals(true,call("restoreTask",new Class<?>[]{session.getClass(),boolean.class},session,false));
            assertEquals(1,task.configWrites);assertEquals(1,task.compatVisits);
            if(state<3){
                assertEquals(expected,task.mLastNonFullscreenBounds);
                if(expected!=null){
                    assertNotSame(expected,task.mLastNonFullscreenBounds);task.mLastNonFullscreenBounds.offset(7,9);
                    assertEquals(state==1?new Rect():concrete,expected);
                }
            }else assertEquals("legacy cache is unknown and must not be overwritten",root.nativeCanvas,task.mLastNonFullscreenBounds);
        }
        assertEquals(4,root.visibleCalls);assertEquals(4,root.resumeCalls);assertEquals(4,wms.traversals);
        assertEquals(4,atm.deferred);assertEquals(4,atm.continued);
    }
    @Test public void missingTaskWaitsForUnlockAndLoadedRecentsBeforeReleasingJournal()throws Throwable{
        Context context=RuntimeEnvironment.getApplication();Class<?>[] signature={session.getClass(),boolean.class};
        recents.loaded=true;Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(false);
        assertEquals(false,call("restoreTask",signature,session,false));
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);recents.loaded=false;
        assertEquals(false,call("restoreTask",signature,session,false));assertTrue(journal.getBaseFile().exists());
        recents.loaded=true;call("recover",new Class<?>[]{String.class},"自动恢复后续接");idle(1);
        assertFalse(journal.getBaseFile().exists());assertEquals(false,get(engine,"recovering"));
        assertTrue(logs.stream().anyMatch(s->s.contains("RECOVERY_STALE_RELEASED task=192")));
    }
    @Test public void recoveryContinuesTheSameRequestAndOriginalPageExactlyOnce()throws Throwable{
        Intent request=request();Intent page=new Intent("example.PAGE").setData(android.net.Uri.parse("example://page/42"));
        request.putExtra("launchFillIn",page).putExtra("launched",true).putExtra("launchDispatched",true);
        List<Intent> resumed=new ArrayList<>();Runnable[] resume=new Runnable[1];
        resume[0]=()->{try{if(!waitFor(request,resume[0])){resumed.add(request);call("completeOpen",new Class<?>[]{Intent.class,boolean.class},request,true);}}catch(Throwable error){throw new AssertionError(error);}};
        assertTrue(waitFor(request,resume[0]));idle(64);assertTrue(resumed.isEmpty());
        recents.loaded=true;idle(200);
        assertEquals(List.of(request),resumed);assertSame(page,request.getParcelableExtra("launchFillIn",Intent.class));
        assertTrue(request.getBooleanExtra("launched",false));assertTrue(request.getBooleanExtra("launchDispatched",false));
        assertEquals(1,logs.stream().filter(s->s.startsWith("OPEN_AUTO_RECOVERY_BEGIN")).count());
        assertNull(get(engine,"recoveryOpen"));assertFalse(journal.getBaseFile().exists());
    }
    @Test public void cancellationInvalidatesQueuedContinuationAndRetainsUnverifiedJournal()throws Throwable{
        Intent request=request();int[] resumed={0};
        assertTrue(waitFor(request,()->{try{if(!waitFor(request,()->{}))resumed[0]++;}catch(Throwable error){throw new AssertionError(error);}}));
        call("cancelRecoveryOpen",new Class<?>[]{String.class},request.getStringExtra("token"));idle(64);
        assertEquals(0,resumed[0]);assertNull(get(engine,"recoveryOpen"));assertTrue(journal.getBaseFile().exists());
        assertEquals(8L,get(engine,"openGeneration"));
    }
    @Test public void supersedingRequestKeepsItsGenerationAndCannotBeCancelledByOldToken()throws Throwable{
        Intent old=request();assertTrue(waitFor(old,()->{}));
        set(engine,"openGeneration",8L);call("cancelRecoveryOpen",new Class<?>[]{String.class},(String)null);
        Intent next=request().putExtra("generation",8L);assertTrue(waitFor(next,()->{}));
        call("cancelRecoveryOpen",new Class<?>[]{String.class},old.getStringExtra("token"));
        assertSame(next,get(engine,"recoveryOpen"));assertEquals(8L,get(engine,"openGeneration"));
        assertTrue(waitFor(old,()->fail("Old request resumed")));
    }
    @Test public void recoveryWaitHasDeadlineAndNeverDeletesUnverifiedState()throws Throwable{
        Intent request=request();assertTrue(waitFor(request,()->{}));idle(4000);
        try{waitFor(request,()->fail("Timed out request resumed"));fail("Expected timeout");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("超时"));}
        call("completeOpen",new Class<?>[]{Intent.class,boolean.class},request,false);
        assertNull(get(engine,"recoveryOpen"));assertTrue(journal.getBaseFile().exists());
    }
    @Test public void liveMissingTaskCompletesWithoutWaitingForBootReadiness()throws Throwable{
        set(session,"task",new Task());
        assertEquals(true,call("restoreTask",new Class<?>[]{session.getClass(),boolean.class},session,false));
        assertTrue(journal.getBaseFile().exists()); // Eligibility alone never forgets the durable record.
    }
    @Test public void reusedIdRejectsUnknownBootButLoadedRecentsReleasesStaleIdentity()throws Throwable{
        Task reused=new Task();reused.mUserId=10;((Root)get(engine,"root")).task=reused;
        try{call("restoreTask",new Class<?>[]{session.getClass(),boolean.class},session,false);fail("Expected identity rejection");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("身份"));}
        assertTrue(journal.getBaseFile().exists());recents.loaded=true;
        assertEquals(true,call("restoreTask",new Class<?>[]{session.getClass(),boolean.class},session,false));
        assertTrue(logs.stream().anyMatch(s->s.contains("reason=task-id-reused")));assertTrue(journal.getBaseFile().exists());
    }
    @Test public void matchingLegacyTaskWithoutBaseIdWaitsBeforeNativeRestore()throws Throwable{
        Task legacy=new Task();legacy.baseIdentifier=null;
        set(session,"baseId",null);set(session,"component",legacy.realActivity.flattenToString());((Root)get(engine,"root")).task=legacy;
        assertEquals(false,call("restoreTask",new Class<?>[]{session.getClass(),boolean.class},session,false));
        assertTrue(journal.getBaseFile().exists());
    }
    @Test public void readinessRecheckRestartsExhaustedQueueOnlyOnce()throws Throwable{
        call("recover",new Class<?>[]{String.class},"初始化恢复");idle(10100);
        var retry=(WindowRecoveryPolicy.Retry)get(engine,"recoveryRetryState");assertEquals(101,retry.waits());
        recents.loaded=true;call("historyTick",new Class<?>[]{});
        assertFalse(retry.needsReadinessCheck());idle(1);assertFalse(journal.getBaseFile().exists());
        assertEquals(1,logs.stream().filter(s->s.contains("RECOVERY_STALE_RELEASED task=192")).count());
    }
    @Test public void recheckingRecoveryKeepsOnlyOneScheduledRetryLoop()throws Throwable{
        call("recover",new Class<?>[]{String.class},"初始化恢复");
        call("recover",new Class<?>[]{String.class},"自动恢复后续接");
        assertEquals(2,((WindowRecoveryPolicy.Retry)get(engine,"recoveryRetryState")).waits());idle(100);
        assertEquals(3,((WindowRecoveryPolicy.Retry)get(engine,"recoveryRetryState")).waits());
    }
}
