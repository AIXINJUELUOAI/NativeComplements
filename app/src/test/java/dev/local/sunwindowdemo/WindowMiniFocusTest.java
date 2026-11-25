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
    private Root root;
    private Area area;
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
        set(value,"activity",Activity.class);set(value,"task",Task.class);
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
        area=new Area(miniTask,background);miniTask.area=area;
        root=new Root(background);
        display.mFocusedApp=miniTask.top;
        mini=new WindowEngine.Session();mini.task=miniTask;mini.taskId=41;mini.userId=0;
        mini.frame="mini";mini.lease=new Binder();
        engine=new WindowEngine(access(),logs::add);
        set(engine,"context",context);set(engine,"lock",new Object());set(engine,"root",root);
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
    @Test public void staleRootMiniUsesNativeAreaSelectionAndYieldsToVisibleHome()throws Throwable{
        root.focused=miniTask;
        invoke("yieldMiniFocus");
        assertSame(background.top,display.mFocusedApp);assertEquals(1,area.selections);
        assertEquals(1,wms.focusUpdates);assertFalse(miniTask.focusable);assertTrue(engine.owns(miniTask));
    }
    @Test public void nullRootFocusStillFindsNativeBackgroundWithoutLaunchingOrRaisingIt()throws Throwable{
        root.focused=null;invoke("yieldMiniFocus");
        assertSame(background.top,display.mFocusedApp);assertEquals(1,area.selections);assertEquals(1,display.focusWrites);
    }
    @Test public void validNativeRootDoesNotNeedASecondAreaSelection()throws Throwable{
        invoke("yieldMiniFocus");assertEquals(0,area.selections);assertSame(background.top,display.mFocusedApp);
    }
    @Test public void nativeAreaSkipsOtherNonFocusableAndHiddenTasks()throws Throwable{
        Task nonFocusable=new Task(43,0,display,false),hidden=new Task(44,0,display,true);hidden.visible=false;
        miniTask.area=new Area(miniTask,nonFocusable,hidden,background);root.focused=miniTask;
        invoke("yieldMiniFocus");assertSame(background.top,display.mFocusedApp);
    }
    @Test public void noFocusableNativeCandidateLeavesFocusUnchanged()throws Throwable{
        root.focused=miniTask;background.focusable=false;assertUnchanged(miniTask.top);assertEquals(1,area.selections);
    }
    @Test public void fallbackCannotSelectAnotherUserOrDisplay()throws Throwable{
        root.focused=miniTask;background.mUserId=10;assertUnchanged(miniTask.top);
        background.mUserId=0;background.display=new Display();assertUnchanged(miniTask.top);
    }
    @Test public void organizedRootYieldsOnlyToItsVerifiedLiveSameUserLeaf()throws Throwable{
        Task leaf=new Task(43,0,display,true);background.top=leaf.top;root.focused=miniTask;
        invoke("yieldMiniFocus");assertSame(leaf.top,display.mFocusedApp);
    }
    @Test public void organizedRootCannotSmuggleAnotherUserOrDisplayLeaf()throws Throwable{
        Task leaf=new Task(43,10,display,true);background.top=leaf.top;root.focused=miniTask;
        assertUnchanged(miniTask.top);leaf.mUserId=0;leaf.display=new Display();assertUnchanged(miniTask.top);
    }
    @Test public void deadOrFinishingOrHiddenBackgroundDoesNotReceiveAppFocus()throws Throwable{
        root.focused=miniTask;background.top.attached=false;assertUnchanged(miniTask.top);
        background.top.attached=true;background.top.finishing=true;assertUnchanged(miniTask.top);
        background.top.finishing=false;background.top.visible=false;assertUnchanged(miniTask.top);
        background.top=null;assertUnchanged(miniTask.top);
    }
    @Test public void deadLeaseDoesNotSelectOrWriteFocus()throws Throwable{
        root.focused=miniTask;mini.lease=new Binder(){@Override public boolean isBinderAlive(){return false;}};
        assertUnchanged(miniTask.top);assertEquals(0,area.selections);
    }
    @Test public void screenOffAndRestoringMiniDoNotSelectBackground()throws Throwable{
        root.focused=miniTask;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(false);
        assertUnchanged(miniTask.top);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(true);
        mini.restoring=true;assertUnchanged(miniTask.top);assertEquals(0,area.selections);
    }
    @Test public void externalFocusedAppDoesNotEvenQueryNativeFallback()throws Throwable{
        root.focused=miniTask;display.mFocusedApp=background.top;assertUnchanged(background.top);assertEquals(0,area.selections);
    }
    @Test public void nativeSelectorFailureIsLocalAndDoesNotRecoverOrRefocusMini()throws Throwable{
        root.focused=miniTask;area.fail=true;
        Method method=WindowEngine.class.getDeclaredMethod("yieldMiniFocus",WindowEngine.Session.class);method.setAccessible(true);
        method.invoke(engine,mini);
        assertSame(miniTask.top,display.mFocusedApp);assertFalse(mini.restoring);assertFalse(miniTask.focusable);
        assertEquals(0,display.focusWrites);assertEquals(0,wms.focusUpdates);
        assertTrue(logs.toString(),logs.stream().anyMatch(line->line.contains("injected native focus selection failure")));
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
        boolean visible=true,attached=true;
        Activity(Task task){this.task=task;}
        public Task getTask(){return task;}
        public boolean isVisibleRequested(){return visible;}
        public boolean attachedToProcess(){return attached;}
    }
    public static final class Task {
        public final int mTaskId;
        public int mUserId;
        Display display;
        boolean focusable,visible=true;
        Area area;
        Activity top=new Activity(this);
        Task(int id,int user,Display display,boolean focusable){mTaskId=id;mUserId=user;this.display=display;this.focusable=focusable;}
        public boolean isFocusable(){return focusable;}
        public boolean isVisibleRequested(){return visible;}
        public Area getTaskDisplayArea(){return area;}
        public Display getDisplayContent(){return display;}
        public Activity topRunningActivity(){return top;}
    }
    public static final class Display {
        public Activity mFocusedApp;
        int focusWrites;
        public void setFocusedApp(Activity activity){mFocusedApp=activity;focusWrites++;}
    }
    public static final class Area {
        final List<Task> topToBottom;
        int selections;
        boolean fail;
        Area(Task...tasks){topToBottom=List.of(tasks);}
        // Same native ordering/ignoreCurrent/focusable-visible contract; no preferred-task cache.
        public Task getNextFocusableRootTask(Task current,boolean ignoreCurrent){
            assertTrue(ignoreCurrent);selections++;
            if(fail)throw new IllegalStateException("injected native focus selection failure");
            for(Task task:topToBottom)if((!ignoreCurrent||task!=current)&&task.isFocusable()&&task.isVisibleRequested())return task;
            return null;
        }
    }
    public static final class Root {
        private Task focused;
        Root(Task focused){this.focused=focused;}
        public Task getTopDisplayFocusedLeafTask(){return focused;}
    }
    public static final class Wms {
        int focusUpdates,lastMode=-1;
        boolean updateInputWindows;
        public void updateFocusedWindowLocked(int mode,boolean update){focusUpdates++;lastMode=mode;updateInputWindows=update;}
    }
}
