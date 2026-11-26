package dev.local.sunwindowdemo;

import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class SystemPriorityTest {
    public static final class Task {
        final Object display; boolean focusable=true; int focusChanges;
        Task(Object display){this.display=display;}
        public Object getDisplayContent(){return display;}
        public boolean isFocusable(){return focusable;}
        public void setFocusable(boolean value){focusable=value;focusChanges++;}
    }
    public static final class Window {
        final Task task; final Object display; final int uid; boolean visible=true;
        Window(Task task,Object display,int uid){this.task=task;this.display=display;this.uid=uid;}
        public Task getTask(){return task;}
        public Object getDisplayContent(){return display;}
        public int getOwningUid(){return uid;}
        public boolean isVisible(){return visible;}
    }
    public static final class Wms { public final ArrayList<Window> mHidingNonSystemOverlayWindows=new ArrayList<>(); }
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(access,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        access.overlayHidingWindows=Wms.class.getField("mHidingNonSystemOverlayWindows");return access;
    }
    private static Object session(Task task)throws Exception{
        Class<?> type=Class.forName(WindowEngine.class.getName()+"$Session");Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object session=constructor.newInstance();set(session,"task",task);set(session,"uid",10001);set(session,"taskId",7);
        set(session,"placement",new Geometry.Placement(600,1000,.7f,100,200));return session;
    }
    private static boolean update(WindowEngine engine,Object session)throws Exception{
        Method method=WindowEngine.class.getDeclaredMethod("updatePriorityState",session.getClass());method.setAccessible(true);
        return (Boolean)method.invoke(engine,session);
    }
    @Test public void nativeListStillRequiresVisibleSameDisplayAndDifferentUid(){
        Object display=new Object(),task=new Object(),other=new Object();
        assertEquals(2,WindowEngine.systemPriority(display,task,10001,display,other,10002,true));
        assertEquals(1,WindowEngine.systemPriority(display,task,10001,display,task,10002,true));
        assertEquals(0,WindowEngine.systemPriority(display,task,10001,display,other,10001,true));
        assertEquals(0,WindowEngine.systemPriority(display,task,10001,new Object(),other,10002,true));
        assertEquals(0,WindowEngine.systemPriority(display,task,10001,display,other,10002,false));
        assertEquals(0,WindowEngine.systemPriority(display,task,10001,display,other,-1,true));
        assertEquals(0,WindowEngine.systemPriority(null,task,10001,null,other,10002,true));
        assertEquals(0,WindowEngine.systemPriority(display,task,-1,display,other,10002,true));
    }
    private static WindowLifecycle.KeyguardShell shell(int blocker){
        return new WindowLifecycle.KeyguardShell(blocker!=0,blocker!=1,blocker==2?-1:0,blocker==3?1:0,
            blocker==4?-1:10170,blocker==5?10171:10170,blocker==6,blocker!=7,blocker!=8,
            blocker==9?2009:2040,blocker==10?0x2000:0x100000,blocker==11?0:0x80000,
            blocker==12?8:blocker==13?1:2);
    }
    @Test public void onlyExactLockedNativeShadeHostCanYieldPresentationWithoutBlockingContinuation(){
        assertTrue(WindowLifecycle.keyguardShell(shell(-1)));
        assertFalse(WindowLifecycle.keyguardShell(null));
        for(int blocker=0;blocker<=13;blocker++){
            assertFalse("native evidence "+blocker,WindowLifecycle.keyguardShell(shell(blocker)));
            assertEquals("blocking priority "+blocker,2,WindowLifecycle.continuationPriority(2,shell(blocker)));
        }
        // A matching appId in another user is not the actual full UID, nor this session's user.
        assertFalse(WindowLifecycle.keyguardShell(new WindowLifecycle.KeyguardShell(true,true,0,0,10170,110170,
            false,true,true,2040,0,0x80000,0)));
        assertFalse(WindowLifecycle.keyguardShell(new WindowLifecycle.KeyguardShell(true,true,1,1,10170,10170,
            false,true,true,2040,0,0x80000,0)));
        assertTrue(WindowLifecycle.keyguardShell(new WindowLifecycle.KeyguardShell(true,true,1,1,110170,110170,
            false,true,true,2040,0,0x80000,0)));
        for(int type:new int[]{1,2000,2009,2010,2017,2024,2038,2041})
            assertFalse("Other SystemUI type "+type,WindowLifecycle.keyguardShell(new WindowLifecycle.KeyguardShell(
                true,true,0,0,10170,10170,false,true,true,type,0,0x80000,0)));
        assertFalse(WindowLifecycle.keyguardShell(new WindowLifecycle.KeyguardShell(true,true,0,0,10170,10170,
            false,true,true,2040,0,0x80000,2|8)));
    }
    @Test public void shellExemptionNeverClearsPermissionScreenshotOrOtherConcurrentPriority(){
        WindowLifecycle.KeyguardShell shell=shell(-1);
        assertEquals(0,WindowLifecycle.continuationPriority(2,shell));
        assertEquals(1,WindowLifecycle.continuationPriority(1,shell));
        assertEquals(2,WindowLifecycle.continuationPriority(2,null)); // Screenshot or unknown metadata.
        assertEquals(0,WindowLifecycle.continuationPriority(0,shell));
        for(int blocker:new int[]{1,2}){
            int shellPriority=WindowLifecycle.continuationPriority(2,shell);
            int securityPriority=WindowLifecycle.continuationPriority(blocker,null);
            assertEquals(blocker,Math.max(shellPriority,securityPriority));
            assertEquals(blocker,Math.max(securityPriority,shellPriority));
        }
        assertTrue(WindowLifecycle.priority(2).blocked()); // No presentation/input exemption.
        assertTrue(WindowLifecycle.priority(2).hide());
    }
    @Test public void externalInstallerSuspendsInputAndRestoresPlacementWithoutMovingFocus()throws Exception{
        Object display=new Object();Task task=new Task(display);Object session=session(task),placement=get(session,"placement");
        Wms wms=new Wms();wms.mHidingNonSystemOverlayWindows.add(new Window(new Task(display),display,10002));
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"wms",wms);set(engine,"sessions",List.of(session));set(engine,"active",session);
        assertTrue(update(engine,session));assertEquals(true,get(session,"prioritySuspended"));assertEquals(true,get(session,"priorityHideTask"));
        assertFalse(task.focusable);assertTrue(engine.blockContentTouch(new Window(task,display,10001)));
        assertFalse(engine.blockBackgroundTouch(wms.mHidingNonSystemOverlayWindows.get(0)));
        assertFalse(update(engine,session));assertEquals(1,task.focusChanges);
        wms.mHidingNonSystemOverlayWindows.clear();assertTrue(update(engine,session));
        assertFalse((Boolean)get(session,"prioritySuspended"));assertTrue(task.focusable);assertEquals(2,task.focusChanges);
        assertSame(placement,get(session,"placement"));assertFalse((Boolean)get(session,"restoring"));
        assertEquals(1,((List<?>)get(engine,"sessions")).size());assertTrue((Long)get(session,"priorityFocusUntil")>0);
    }
    @Test public void sameTaskPermissionKeepsTaskAndNativeInputFocusable()throws Exception{
        Object display=new Object();Task task=new Task(display);Object session=session(task);
        Wms wms=new Wms();Window permission=new Window(task,display,10002);wms.mHidingNonSystemOverlayWindows.add(permission);
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"wms",wms);set(engine,"sessions",List.of(session));set(engine,"active",session);
        assertTrue(update(engine,session));assertTrue((Boolean)get(session,"prioritySuspended"));assertFalse((Boolean)get(session,"priorityHideTask"));
        assertTrue(task.focusable);assertEquals(0,task.focusChanges);
        assertFalse(engine.blockContentTouch(permission));assertFalse(engine.blockBackgroundTouch(permission));
        set(session,"screenSuspended",true);set(session,"closing",true);
        assertFalse("Same-task permission overrides blocked content state",engine.blockContentTouch(permission));
        set(session,"screenSuspended",false);set(session,"closing",false);
        assertFalse((Boolean)get(session,"surfaceHidden"));assertFalse((Boolean)get(session,"screenSuspended"));
        wms.mHidingNonSystemOverlayWindows.clear();assertTrue(update(engine,session));assertEquals(0,task.focusChanges);
    }
    @Test public void miniPermissionTemporarilyAllowsNativeFocusThenRestoresMini()throws Exception{
        Object display=new Object();Task task=new Task(display);task.focusable=false;Object session=session(task);set(session,"frame","mini");
        Wms wms=new Wms();Window permission=new Window(task,display,10002);wms.mHidingNonSystemOverlayWindows.add(permission);
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"wms",wms);set(engine,"sessions",List.of(session));set(engine,"active",session);
        assertTrue(update(engine,session));assertTrue(task.focusable);assertEquals(1,task.focusChanges);
        assertFalse(engine.blockContentTouch(permission));assertFalse((Boolean)get(session,"priorityHideTask"));
        assertFalse(update(engine,session));assertEquals(1,task.focusChanges);
        wms.mHidingNonSystemOverlayWindows.clear();assertTrue(update(engine,session));assertFalse(task.focusable);assertEquals(2,task.focusChanges);
    }
    @Test public void externalToSameTaskRetainsOriginalMiniFocusSnapshot()throws Exception{
        Object display=new Object();Task task=new Task(display);task.focusable=false;Object session=session(task);set(session,"frame","mini");
        Wms wms=new Wms();Window external=new Window(new Task(display),display,10002),permission=new Window(task,display,10003);
        wms.mHidingNonSystemOverlayWindows.add(external);
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"wms",wms);
        assertTrue(update(engine,session));assertFalse(task.focusable);
        wms.mHidingNonSystemOverlayWindows.clear();wms.mHidingNonSystemOverlayWindows.add(permission);
        assertTrue(update(engine,session));assertTrue(task.focusable);assertFalse((Boolean)get(session,"priorityWasFocusable"));
        wms.mHidingNonSystemOverlayWindows.add(external);assertTrue(update(engine,session));assertFalse(task.focusable);
        wms.mHidingNonSystemOverlayWindows.clear();assertTrue(update(engine,session));assertFalse(task.focusable);
    }
    @Test public void nativeListKeepsPriorityUntilLastVisibleExternalWindowCloses()throws Exception{
        Object display=new Object();Task task=new Task(display);Object session=session(task);
        Wms wms=new Wms();Window first=new Window(new Task(display),display,10002),last=new Window(new Task(display),display,10003);
        wms.mHidingNonSystemOverlayWindows.add(first);wms.mHidingNonSystemOverlayWindows.add(last);
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"wms",wms);
        assertTrue(update(engine,session));wms.mHidingNonSystemOverlayWindows.remove(first);
        assertFalse(update(engine,session));assertTrue((Boolean)get(session,"priorityHideTask"));assertFalse(task.focusable);
        last.visible=false;assertTrue(update(engine,session));assertFalse((Boolean)get(session,"prioritySuspended"));assertTrue(task.focusable);
        wms.mHidingNonSystemOverlayWindows.clear();assertFalse(update(engine,session));
    }
    @Test public void priorityFrameExplicitlyHidesControlsBeforeTokenValidation()throws Exception{
        Object session=session(new Task(new Object()));set(session,"prioritySuspended",true);
        WindowEngine engine=new WindowEngine(null,message->{});android.content.Intent result=new android.content.Intent();
        Method fill=WindowEngine.class.getDeclaredMethod("fillFrame",android.content.Intent.class,session.getClass());fill.setAccessible(true);fill.invoke(engine,result,session);
        assertTrue(result.hasExtra("presented"));assertFalse(result.getBooleanExtra("presented",true));
        assertTrue(result.hasExtra("inputPending"));assertFalse(result.getBooleanExtra("inputPending",true));
        assertTrue(result.getBooleanExtra("systemOverlay",false));
    }
    @Test public void absentOptionalNativeFieldKeepsExistingBaseline()throws Exception{
        Task task=new Task(new Object());Object session=session(task);FrameworkAccess access=access();access.overlayHidingWindows=null;
        WindowEngine engine=new WindowEngine(access,message->{});
        assertFalse(update(engine,session));assertFalse((Boolean)get(session,"prioritySuspended"));assertTrue(task.focusable);
    }
}
