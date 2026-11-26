package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowShareRoutingTest {
    public static final class Task {
        public int mTaskId=42,mUserId;
        Object display=new Object();
        public Rect mLastNonFullscreenBounds=new Rect(10,20,610,1020);
        public ComponentName realActivity=new ComponentName("example.destination","example.destination.Main");
        int mode=5,modeReads,modeWrites,boundsWrites,compatVisits;
        boolean visible=true;
        Rect bounds=new Rect(0,0,1200,2133);
        public int getWindowingMode(){modeReads++;return mode;}
        public boolean isVisibleRequested(){return visible;}
        public Object getDisplayContent(){return display;}
        public int getActivityType(){return 1;}
        public Object topRunningActivity(){return null;}
        public Intent getBaseIntent(){return new Intent();}
        public void setWindowingMode(int value){modeWrites++;mode=value;}
        public void setBounds(Rect value){boundsWrites++;bounds=new Rect(value);mLastNonFullscreenBounds=new Rect(0,0,1200,2133);}
        public void forAllActivities(Consumer<Object> consumer){compatVisits++;}
    }
    public static final class Activity {
        public String packageName="example.destination";
        public Intent intent=new Intent(Intent.ACTION_SEND);
        final Object task;
        int uid=10042;
        boolean resolver;
        Activity(Object task){this.task=task;}
        public Object getTask(){return task;}
        public int getUid(){return uid;}
        public boolean isResolverOrDelegateActivity(){return resolver;}
    }
    public static final class Starter {
        public final Activity mLastStartActivityRecord;
        Starter(Object task){mLastStartActivityRecord=new Activity(task);}
    }
    public static final class Root {
        final Task target;
        int lookups;
        Root(Task target){this.target=target;}
        public Object anyTaskForId(int id,int mode){lookups++;assertEquals(target.mTaskId,id);assertEquals(0,mode);return target;}
    }
    private final Object lock=new Object();
    private final List<String> logs=new ArrayList<>();
    private FrameworkAccess access;
    private WindowEngine engine;
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private Object invoke(String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{synchronized(lock){return method.invoke(engine,args);}}
        catch(InvocationTargetException error){throw error.getCause();}
    }
    @Before public void setup()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        engine=new WindowEngine(access,logs::add);set(engine,"lock",lock);
        set(engine,"context",RuntimeEnvironment.getApplication());
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
    }
    private WindowEngine.Session own(Task task)throws Exception{
        WindowEngine.Session owner=new WindowEngine.Session();owner.task=task;owner.taskId=task.mTaskId;owner.pkg="example.source";
        set(engine,"sessions",List.of(owner));return owner;
    }
    private Root linkedSource(Task source,Task target)throws Exception{
        source.mTaskId=7;target.display=source.display;
        set(engine,"active",own(source));set(engine,"handler",new Handler(Looper.getMainLooper()));
        Root root=new Root(target);set(engine,"root",root);
        set(access,"lookup",Root.class.getDeclaredMethod("anyTaskForId",int.class,int.class));return root;
    }
    private static void assertUntouched(Task task){
        assertEquals(5,task.mode);assertEquals(0,task.modeWrites);assertEquals(0,task.boundsWrites);assertEquals(0,task.compatVisits);
    }
    @Test public void targetHistoryIsCopiedBeforeNativeLayoutAndNeverResampled()throws Exception{
        Object request=new Object();Task target=new Task();WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(request,null,null,new Object(),false);
        engine.shareLaunch.set(launch);
        synchronized(lock){engine.shareTarget(request,target);}
        Rect saved=launch.boundsHistory().get(target);assertEquals(target.mLastNonFullscreenBounds,saved);assertNotSame(target.mLastNonFullscreenBounds,saved);
        target.mLastNonFullscreenBounds.set(0,0,1200,2133);
        synchronized(lock){engine.shareTarget(request,target);}
        assertSame(saved,launch.boundsHistory().get(target));assertEquals(new Rect(10,20,610,1020),saved);
        assertUntouched(target);
    }
    @Test public void targetSamplingDistinguishesKnownNullAndSkipsSourceOwnedAndUnrelatedStarts()throws Exception{
        Object request=new Object(),source=new Object();Task target=new Task();target.mLastNonFullscreenBounds=null;
        WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(request,null,null,source,false);engine.shareLaunch.set(launch);
        synchronized(lock){engine.shareTarget(request,source);engine.shareTarget(new Object(),target);}
        assertTrue(launch.boundsHistory().isEmpty());
        own(target);synchronized(lock){engine.shareTarget(request,target);}assertTrue(launch.boundsHistory().isEmpty());
        set(engine,"sessions",List.of());synchronized(lock){engine.shareTarget(request,target);}
        assertTrue(launch.boundsHistory().containsKey(target));assertNull(launch.boundsHistory().get(target));
        WindowEngine.ShareLaunch nativeLaunch=new WindowEngine.ShareLaunch(request,null,null,source,true);engine.shareLaunch.set(nativeLaunch);
        synchronized(lock){engine.shareTarget(request,new Object());}assertTrue(nativeLaunch.boundsHistory().isEmpty());
    }
    @Test public void sourceAsDestinationNeverReadsLeaseOrAcquiresOrFallsBack()throws Throwable{
        Task source=new Task();Object request=new Object();
        // Null lease/request metadata would fail if the exact-source guard ran too late.
        WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(request,null,null,source,false);
        assertNull(invoke("startShare",new Class<?>[]{Object.class,Object.class,WindowEngine.ShareLaunch.class},request,new Activity(source),launch));
        engine.shareFinished(launch,new Starter(source));
        assertEquals(0,source.modeReads);assertUntouched(source);assertFalse(engine.owns(source));assertTrue(logs.isEmpty());
    }
    @Test public void linkedSnapshotFailureNeverMutatesUnownedNativeFreeform()throws Exception{
        Task source=new Task(),target=new Task();Root root=linkedSource(source,target);
        Rect sourceHistory=new Rect(source.mLastNonFullscreenBounds),sourceBounds=new Rect(source.bounds);
        Rect targetHistory=new Rect(target.mLastNonFullscreenBounds),targetBounds=new Rect(target.bounds);
        // Task intentionally lacks mLastSurfaceSize: real snapshot fails before lease binding/ownership.
        assertNull(engine.activityStarting(new Activity(source),new Activity(null),new Activity(target),0,10042));
        assertEquals(1,root.lookups);assertUntouched(target);assertFalse(engine.owns(target));
        assertEquals(targetHistory,target.mLastNonFullscreenBounds);assertEquals(targetBounds,target.bounds);
        assertUntouched(source);assertEquals(sourceHistory,source.mLastNonFullscreenBounds);assertEquals(sourceBounds,source.bounds);assertTrue(engine.owns(source));
        assertTrue(logs.toString(),logs.stream().anyMatch(line->line.startsWith("LINKED_START_FAILED")&&line.contains("mLastSurfaceSize")));
    }
    @Test public void linkedUnverifiedSourceUserOrDisplayNeverReleasesNativeFreeform()throws Exception{
        Task source=new Task(),target=new Task();Root root=linkedSource(source,target);Activity request=new Activity(null);
        assertNull(engine.activityStarting(new Activity(new Task()),request,new Activity(target),0,10042));
        target.mUserId=1;assertNull(engine.activityStarting(new Activity(source),request,new Activity(target),0,10042));
        target.mUserId=0;target.display=new Object();assertNull(engine.activityStarting(new Activity(source),request,new Activity(target),0,10042));
        assertEquals(0,root.lookups);assertUntouched(source);assertUntouched(target);
        assertEquals(new Rect(10,20,610,1020),target.mLastNonFullscreenBounds);assertTrue(logs.toString(),logs.isEmpty());
    }
    @Test public void nativeReleaseRestoresSavedHistoryAfterLayoutWithoutAliasingIt()throws Throwable{
        Task target=new Task();WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(new Object(),null,null,new Object(),false);
        Rect history=new Rect(target.mLastNonFullscreenBounds);launch.boundsHistory().put(target,history);
        invoke("releaseToFullscreen",new Class<?>[]{Object.class,WindowEngine.ShareLaunch.class},target,launch);
        assertEquals(1,target.mode);assertTrue(target.bounds.isEmpty());assertEquals(1,target.compatVisits);
        assertEquals(history,target.mLastNonFullscreenBounds);assertNotSame(history,target.mLastNonFullscreenBounds);
        target.mLastNonFullscreenBounds.setEmpty();assertEquals(new Rect(10,20,610,1020),history);
    }
    @Test public void nativeReleaseClearsKnownNullAndNewInheritedEndpointInsteadOfKeepingCanvas()throws Throwable{
        for(boolean sampled:new boolean[]{false,true}){
            Task target=new Task();WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(new Object(),null,null,new Object(),false);
            if(sampled)launch.boundsHistory().put(target,null);
            invoke("releaseToFullscreen",new Class<?>[]{Object.class,WindowEngine.ShareLaunch.class},target,launch);
            assertEquals(1,target.mode);assertTrue(target.bounds.isEmpty());assertNull(target.mLastNonFullscreenBounds);
        }
    }
    @Test public void finishedFallbackUsesSavedHistoryButSkipsOwnedAndMismatchedDestinations()throws Exception{
        Task target=new Task();Activity request=new Activity(null);
        WindowEngine.ShareLaunch launch=new WindowEngine.ShareLaunch(request,null,null,new Object(),false);
        Rect history=new Rect(5,6,605,1006);launch.boundsHistory().put(target,history);
        own(target);engine.shareFinished(launch,new Starter(target));assertUntouched(target);
        set(engine,"sessions",List.of());request.packageName="example.other";
        engine.shareFinished(launch,new Starter(target));assertUntouched(target);
        request.packageName="example.destination";engine.shareFinished(launch,new Starter(target));
        assertEquals(1,target.mode);assertTrue(target.bounds.isEmpty());assertEquals(history,target.mLastNonFullscreenBounds);
        assertNotSame(history,target.mLastNonFullscreenBounds);
        assertTrue(logs.toString(),logs.stream().anyMatch(line->line.startsWith("SHARE_WINDOW_NATIVE_FALLBACK")));
        assertFalse(logs.toString(),logs.stream().anyMatch(line->line.startsWith("SHARE_WINDOW_FALLBACK_FAILED")));
    }
    @Test public void resolverRoutesNativeFullscreenWithCopiedOptionsAndUnchangedIntent()throws Exception{
        Task source=new Task();own(source);Activity caller=new Activity(source),request=new Activity(null);request.resolver=true;
        request.intent=new Intent(Intent.ACTION_SEND).setDataAndType(Uri.parse("content://example/item/7"),"image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("request",73);
        Intent originalIntent=request.intent,before=new Intent(request.intent);ActivityOptions original=ActivityOptions.makeBasic().setLaunchBounds(new Rect(10,20,110,220));
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(original,5);
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(original,42);
        IBinder fragment=new Binder();ActivityOptions.class.getDeclaredMethod("setLaunchTaskFragmentToken",IBinder.class).invoke(original,fragment);
        WindowEngine.ShareLaunch launch=engine.prepareInstaller(request,caller,original,10042);
        assertNotNull(launch);assertTrue(launch.nativeFullscreen());assertSame(request,launch.request());assertSame(source,launch.sourceTask());assertNull(launch.lease());
        ActivityOptions next=launch.options();assertNotSame(original,next);assertNull(next.getLaunchBounds());
        assertEquals(1,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(next));
        assertEquals(-1,ActivityOptions.class.getDeclaredMethod("getLaunchTaskId").invoke(next));
        assertNull(ActivityOptions.class.getDeclaredMethod("getLaunchRootTask").invoke(next));assertNull(ActivityOptions.class.getDeclaredMethod("getLaunchTaskFragmentToken").invoke(next));
        assertEquals(5,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(original));
        assertEquals(42,ActivityOptions.class.getDeclaredMethod("getLaunchTaskId").invoke(original));
        assertSame(fragment,ActivityOptions.class.getDeclaredMethod("getLaunchTaskFragmentToken").invoke(original));assertEquals(new Rect(10,20,110,220),original.getLaunchBounds());
        assertSame(originalIntent,request.intent);assertTrue(before.filterEquals(request.intent));assertEquals(before.getFlags(),request.intent.getFlags());assertEquals(73,request.intent.getIntExtra("request",0));assertUntouched(source);
    }
    @Test public void resolverStillRequiresOwnedVisibleSameUserSourceAndUnlockedScreen()throws Exception{
        Task source=new Task();Activity caller=new Activity(source),request=new Activity(null);request.resolver=true;
        assertNull(engine.prepareInstaller(request,caller,null,10042));own(source);
        source.visible=false;assertNull(engine.prepareInstaller(request,caller,null,10042));source.visible=true;
        request.uid=110042;assertNull(engine.prepareInstaller(request,caller,null,10042));request.uid=10042;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        assertNull(engine.prepareInstaller(request,caller,null,10042));assertUntouched(source);
    }
}
