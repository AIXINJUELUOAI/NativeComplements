package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Binder;
import android.os.PowerManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowMiniFocusTest {
    private WindowEngine engine;
    private WindowEngine.Session mini;
    private Display display;
    private Task miniTask, background;
    private Wms wms;
    private final List<String> logs=new ArrayList<>();

    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess value=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class)
            .invoke(singleton.get(null),FrameworkAccess.class);
        set(value,"methods",new HashMap<>());set(value,"fields",new HashMap<>());
        set(value,"activity",Activity.class);
        set(value,"focused",Root.class.getDeclaredMethod("getTopDisplayFocusedLeafTask"));
        return value;
    }
    private void invoke(String name)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,WindowEngine.Session.class);method.setAccessible(true);
        try{method.invoke(engine,mini);}catch(InvocationTargetException error){throw error.getCause();}
        assertTrue("fixture must exercise the framework calls without swallowed reflection failures: "+logs,
            logs.stream().noneMatch(line->line.contains("UNAVAILABLE")));
    }
    @Before public void setup()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        display=new Display();wms=new Wms();
        miniTask=new Task(41,0,display,false);
        background=new Task(42,0,display,true);
        display.mFocusedApp=miniTask.top;
        mini=new WindowEngine.Session();mini.task=miniTask;mini.taskId=41;mini.userId=0;
        mini.frame="mini";mini.lease=new Binder();
        engine=new WindowEngine(access(),logs::add);
        set(engine,"context",context);set(engine,"lock",new Object());set(engine,"root",new Root(background));
        set(engine,"wms",wms);set(engine,"sessions",List.of(mini));set(engine,"pinned",mini);
    }
    private void assertUnchanged(Activity original)throws Throwable{
        invoke("yieldMiniFocus");
        assertSame(original,display.mFocusedApp);assertEquals(0,display.focusWrites);assertEquals(0,wms.focusUpdates);
    }
    @Test public void ownedNonFocusableMiniYieldsStaleFocusedAppToNativeBackground()throws Throwable{
        assertTrue(engine.owns(miniTask));
        invoke("yieldMiniFocus");
        assertSame(background.top,display.mFocusedApp);assertEquals(1,display.focusWrites);
        assertEquals(1,wms.focusUpdates);assertEquals(0,wms.lastMode);assertTrue(wms.updateInputWindows);
        assertTrue(logs.stream().anyMatch(line->line.contains("MINI_FOCUS_YIELDED")));
        assertTrue(engine.owns(miniTask));assertEquals("mini",mini.frame);assertFalse(miniTask.focusable);
    }
    @Test public void resumeMiniFinallyYieldsEvenWhenOptionalResumePathIsNotReady()throws Throwable{
        // visibleResumeReady defaults false: exercise the real early-return/finally path, not a source assertion.
        invoke("resumeMini");
        assertSame(background.top,display.mFocusedApp);assertEquals(1,wms.focusUpdates);
    }
    @Test public void externalFocusedAppIsNotOverwritten()throws Throwable{
        display.mFocusedApp=background.top;assertUnchanged(background.top);
    }
    @Test public void nativeBackgroundOnAnotherDisplayIsNotSelected()throws Throwable{
        background.display=new Display();assertUnchanged(miniTask.top);
    }
    @Test public void nativeBackgroundFromAnotherUserIsNotSelected()throws Throwable{
        background.mUserId=10;assertUnchanged(miniTask.top);
    }
    @Test public void focusableMiniKeepsItsNativeFocus()throws Throwable{
        miniTask.focusable=true;assertUnchanged(miniTask.top);
    }
    @Test public void priorityWindowDoesNotYieldFocus()throws Throwable{
        mini.prioritySuspended=true;assertUnchanged(miniTask.top);
    }
    @Test public void keyguardDoesNotYieldFocus()throws Throwable{
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        assertUnchanged(miniTask.top);
    }
    @Test public void unownedVisibleSessionCannotChangeFocusedApp()throws Throwable{
        set(engine,"sessions",List.of());assertUnchanged(miniTask.top);
    }
    public static final class Activity {
        private final Task task;
        public boolean finishing;
        Activity(Task task){this.task=task;}
        public Task getTask(){return task;}
        public boolean isVisibleRequested(){return true;}
        public boolean attachedToProcess(){return true;}
    }
    public static final class Task {
        public final int mTaskId;
        public int mUserId;
        Display display;
        boolean focusable;
        final Activity top=new Activity(this);
        Task(int id,int user,Display display,boolean focusable){mTaskId=id;mUserId=user;this.display=display;this.focusable=focusable;}
        public boolean isFocusable(){return focusable;}
        public boolean isVisibleRequested(){return true;}
        public Display getDisplayContent(){return display;}
        public Activity topRunningActivity(){return top;}
    }
    public static final class Display {
        public Activity mFocusedApp;
        int focusWrites;
        public void setFocusedApp(Activity activity){mFocusedApp=activity;focusWrites++;}
    }
    public static final class Root {
        private final Task focused;
        Root(Task focused){this.focused=focused;}
        public Task getTopDisplayFocusedLeafTask(){return focused;}
    }
    public static final class Wms {
        int focusUpdates,lastMode=-1;
        boolean updateInputWindows;
        public void updateFocusedWindowLocked(int mode,boolean update){focusUpdates++;lastMode=mode;updateInputWindows=update;}
    }
}
