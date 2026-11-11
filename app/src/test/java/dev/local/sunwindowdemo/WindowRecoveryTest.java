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
        Atm(Recents value){mRecentTasks=value;}
    }
    public static final class Root {
        public Object anyTaskForId(int id,int mode){assertEquals(192,id);assertTrue(mode==0||mode==1);return null;}
    }
    @Before public void setup()throws Throwable{
        Context context=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(true);
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));
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
    @Test public void recheckingRecoveryKeepsOnlyOneScheduledRetryLoop()throws Throwable{
        call("recover",new Class<?>[]{String.class},"初始化恢复");
        call("recover",new Class<?>[]{String.class},"自动恢复后续接");
        assertEquals(2,get(engine,"recoveryWaits"));idle(100);
        assertEquals(3,get(engine,"recoveryWaits"));
    }
}
