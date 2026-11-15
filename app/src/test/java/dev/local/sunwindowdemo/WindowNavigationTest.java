package dev.local.sunwindowdemo;

import android.os.Binder;
import java.io.File;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WindowNavigationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static AndroidWindowLaunch launch(long generation,String hint,String expected,android.os.IBinder sender,String foreground){
        return new AndroidWindowLaunch(new WindowNavigation.Launch(generation,hint,expected,0,0,foreground),sender);
    }

    @Test public void delegatedNotificationBindsItsActualTargetOnlyForTheExactSend(){
        Binder original=new Binder(),unrelated=new Binder();
        AndroidWindowLaunch launch=launch(12,"dev.willdo.app",null,original,"com.example.browser");
        assertTrue(launch.accepts(12,original,null));
        assertTrue(launch.targetAllowed("com.example.ride",0));
        assertFalse(launch.accepts(12,unrelated,null));
        assertFalse(launch.accepts(13,original,null));
        assertFalse(launch.targetAllowed("com.example.ride",10));
        Object dispatchedTask=new Object();launch.task=dispatchedTask;launch.state.dispatched(12);
        assertTrue("An activity forwarding after PendingIntent completion is still part of this task's launch",launch.accepts(12,null,dispatchedTask));
        assertFalse(launch.accepts(12,null,new Object()));
        AndroidWindowLaunch suggestion=launch(12,"com.example.shop","com.example.shop",original,null);
        assertFalse(suggestion.targetAllowed("com.example.other",0));
    }

    @Test public void notificationsKeepTheActualForegroundTargetNativeIncludingDelegatedSenders(){
        WindowNavigation.Launch delegated=launch(12,"com.example.publisher",null,new Binder(),"com.example.chat").state;
        delegated.targetPackage="com.example.chat";delegated.nativeTarget=true;
        assertTrue(delegated.keepNativeForeground());
        delegated.targetPackage="com.example.browser";delegated.nativeTarget=false;
        assertFalse(delegated.keepNativeForeground());
        WindowNavigation.Launch unknown=launch(12,"com.example.publisher",null,new Binder(),null).state;
        unknown.targetPackage="com.example.chat";
        assertFalse("Unknown startup focus must not exempt arbitrary notification targets",unknown.keepNativeForeground());
        WindowNavigation.Launch shortcut=launch(12,"com.example.chat","com.example.chat",new Binder(),"com.example.chat").state;
        shortcut.targetPackage="com.example.chat";
        assertFalse("Explicit shortcuts still open their requested window",shortcut.keepNativeForeground());
        shortcut.nativeTarget=true;
        assertTrue("A suggestion that lands in the fullscreen foreground task keeps it native",shortcut.keepNativeForeground());
    }

    public static final class ProbeBinder extends Binder {
        int comparisons;
        @Override public boolean equals(Object other){comparisons++;return this==other;}
        @Override public int hashCode(){return System.identityHashCode(this);}
    }
    @Test public void nativeLineageNeverMatchesNullAndOldGenerationNeverCallsBinderEquals(){
        AndroidWindowLaunch empty=launch(12,"com.example.app",null,null,null);
        assertFalse(empty.accepts(12,null,null));
        ProbeBinder sender=new ProbeBinder();AndroidWindowLaunch ticket=launch(12,"com.example.app",null,sender,null);
        assertFalse(ticket.accepts(13,sender,null));assertEquals(0,sender.comparisons);
        assertFalse(ticket.accepts(12,null,null));assertEquals(1,sender.comparisons);
        Object task=new Object();ticket.task=task;ticket.state.bind(42,"com.example.app");
        assertTrue(ticket.accepts(12,null,task));
        assertFalse("Task id equality is not native task identity",ticket.accepts(12,null,new Object()));
        assertTrue(ticket.accepts(12,sender,null));
    }
    @Test public void pureLaunchKeepsDispatchBindingAndRequestGenerationSeparate(){
        WindowNavigation.Launch state=new WindowNavigation.Launch(12,"com.example.publisher",null,0,100,"com.example.app");
        assertTrue(state.currentRequest(12,"com.example.publisher",0));assertFalse(state.currentRequest(13,"com.example.publisher",0));
        assertFalse(state.currentRequest(12,"com.example.other",0));assertFalse(state.currentRequest(12,"com.example.publisher",10));
        state.dispatched(11);assertFalse(state.dispatched);state.bind(42,"com.example.intermediate");
        assertEquals(42,state.taskId);assertFalse(state.dispatched);state.dispatched(12);state.dispatched(12);assertTrue(state.dispatched);
        state.bind(43,"com.example.target");assertEquals(43,state.taskId);assertEquals("com.example.target",state.targetPackage);
        assertFalse(state.accepts(13,true,true));assertTrue(state.accepts(12,false,true));
        assertTrue(state.targetAllowed("com.example.target",0,true));assertFalse(state.targetAllowed("invalid",0,false));
    }
    private static void set(Object target,String name,Object value)throws Exception{
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(a,"fields",new java.util.concurrent.ConcurrentHashMap<>());return a;
    }
    public static final class NativeTask {
        public int mTaskId=42,mUserId;public android.content.ComponentName realActivity;
        NativeTask(String pkg){realActivity=new android.content.ComponentName(pkg,pkg+".Main");}
        public int getActivityType(){return 1;}
        final Object display=new Object();public Object getDisplayContent(){return display;}
    }
    public static final class Activity {
        final Object task;Activity(Object task){this.task=task;}public Object getTask(){return task;}
    }
    @SuppressWarnings("unchecked")
    @Test public void engineBindsRepeatedNativeCallbacksAndClearsOnlyItsOwnGeneration()throws Exception{
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"lock",new Object());set(engine,"openGeneration",12L);
        java.lang.reflect.Field field=WindowEngine.class.getDeclaredField("pendingShortcutLaunch");field.setAccessible(true);
        var pending=(java.util.concurrent.atomic.AtomicReference<AndroidWindowLaunch>)field.get(engine);
        Binder sender=new Binder();AndroidWindowLaunch ticket=launch(12,"com.example.publisher",null,sender,null);pending.set(ticket);
        assertSame(ticket,engine.shortcutLaunchTicket(sender,null));assertNull(engine.shortcutLaunchTicket(null,null));
        NativeTask first=new NativeTask("com.example.intermediate");engine.shortcutActivityStarted(ticket,new Activity(first));
        assertSame(first,ticket.task);assertEquals("com.example.intermediate",ticket.state.targetPackage);
        NativeTask target=new NativeTask("com.example.target");engine.shortcutActivityStarted(ticket,new Activity(target));
        assertSame(target,ticket.task);assertEquals(42,ticket.state.taskId);assertEquals("com.example.target",ticket.state.targetPackage);
        assertSame(ticket,engine.shortcutLaunchTicket(null,new Activity(target)));
        assertNull("Same numeric id cannot forward this send",engine.shortcutLaunchTicket(null,new Activity(first)));
        NativeTask otherUser=new NativeTask("com.example.foreign");otherUser.mUserId=10;engine.shortcutActivityStarted(ticket,new Activity(otherUser));assertSame(target,ticket.task);
        AndroidWindowLaunch replacement=launch(13,"com.example.publisher",null,sender,null);pending.set(replacement);set(engine,"openGeneration",13L);
        engine.shortcutActivityStarted(ticket,new Activity(first));assertSame(target,ticket.task);
        java.lang.reflect.Method clear=WindowEngine.class.getDeclaredMethod("clearShortcutLaunch",long.class);clear.setAccessible(true);
        clear.invoke(engine,12L);assertSame(replacement,pending.get());clear.invoke(engine,13L);assertNull(pending.get());
    }
    @SuppressWarnings("unchecked")
    @Test public void engineComputesNativeForegroundFromBoundTaskIdentityNotNumericIdOrPackageHint()throws Exception{
        WindowEngine engine=new WindowEngine(access(),message->{});set(engine,"lock",new Object());set(engine,"openGeneration",12L);
        java.lang.reflect.Field field=WindowEngine.class.getDeclaredField("pendingShortcutLaunch");field.setAccessible(true);
        var pending=(java.util.concurrent.atomic.AtomicReference<AndroidWindowLaunch>)field.get(engine);
        NativeTask target=new NativeTask("com.example.chat");
        AndroidWindowLaunch ticket=launch(12,"com.example.publisher",null,new Binder(),"com.example.chat");
        ticket.foreground=new WindowEngine.NativeForeground(target,"com.example.chat",0,target.display,1,false,target);pending.set(ticket);
        engine.shortcutActivityStarted(ticket,new Activity(target));assertTrue(ticket.state.keepNativeForeground());
        NativeTask sameIdAndPackage=new NativeTask("com.example.chat");
        engine.shortcutActivityStarted(ticket,new Activity(sameIdAndPackage));assertFalse(ticket.state.keepNativeForeground());
        NativeTask floating=new NativeTask("com.example.floating");
        ticket.foreground=new WindowEngine.NativeForeground(floating,"com.example.floating",0,target.display,5,true,target);
        engine.shortcutActivityStarted(ticket,new Activity(target));assertTrue("Exact covered fullscreen task remains native",ticket.state.keepNativeForeground());
        NativeTask another=new NativeTask("com.example.other");
        engine.shortcutActivityStarted(ticket,new Activity(another));assertFalse(ticket.state.keepNativeForeground());
    }
    @Test public void gestureUnfoldsOneStepWhileExplicitNavigationOpensContent(){
        assertEquals("restore-mini",WindowNavigation.wakeForm("mini",true,true));
        assertEquals("normal",WindowNavigation.wakeForm("mini",false,true));
        assertEquals("normal",WindowNavigation.wakeForm("mini",true,false));
        assertEquals("normal",WindowNavigation.wakeForm("normal",false,true));
    }

    @Test public void rememberedDirectionSurvivesRestartWithoutLeakingToOtherPagesOrUsers()throws Exception{
        File file=new File(temporary.getRoot(),"orientation.json");
        WindowOrientation old=new WindowOrientation(file);
        old.remember(0,"com.example.media","Player",true);old.flush();
        WindowOrientation current=new WindowOrientation(file);current.load();
        assertEquals(Boolean.TRUE,current.get(0,"com.example.media","Player"));
        assertNull(current.get(0,"com.example.media","Home"));
        assertNull(current.get(10,"com.example.media","Player"));
        assertTrue(WindowPolicy.initialLandscape(-1,-1,-1,current.get(0,"com.example.media","Player")));
        assertFalse("A new page's explicit portrait request wins over saved landscape",WindowPolicy.initialLandscape(-1,-1,1,true));
        assertFalse("An app without memory still starts in portrait",WindowPolicy.initialLandscape(-1,-1,0,null));
        assertTrue("Landscape-only apps retain the original rule",WindowPolicy.initialLandscape(0,0,0,null));
    }
}
