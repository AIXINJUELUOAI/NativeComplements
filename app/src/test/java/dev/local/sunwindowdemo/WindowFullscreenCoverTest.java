package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Executes the native readiness gate and real TaskCover timing, not a replacement cover policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowFullscreenCoverTest.SurfaceShadow.class,
        WindowFullscreenCoverTest.BuilderShadow.class,WindowFullscreenCoverTest.TransactionShadow.class,
        WindowFullscreenCoverTest.FramesShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowFullscreenCoverTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final java.util.List<String> logs=new java.util.ArrayList<>();
    private Context context;
    private FrameworkAccess access;
    private WindowEngine engine;
    private WindowEngine.Session session;
    private Task task;
    private Window main;
    private Handler handler;
    private TaskCover cover;

    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static <T>T allocate(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type));
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(engine,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static final class Animator { boolean leash;public boolean hasLeash(){return leash;} }
    public static final class Controller {
        boolean collecting,playing;
        public boolean inTransition(Task task){return collecting||playing;}
        public boolean inPlayingTransition(Task task){return playing;}
    }
    public static final class Display {
        public int mDisplayId;
        int rotation;
        final Rect bounds=new Rect(0,0,1200,2670);
        public Rect getBounds(){return bounds;}
        public int getRotation(){return rotation;}
        public int getDisplayId(){return mDisplayId;}
    }
    public static final class Task {
        public final SurfaceControl mSurfaceControl=Shadow.newInstanceOf(SurfaceControl.class);
        public final Animator mSurfaceAnimator=new Animator();
        public final Controller mTransitionController=new Controller();
        public int mTaskId=42,mUserId;
        final Display display=new Display();
        final Configuration configuration=new Configuration();
        final SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        final Rect bounds=new Rect(display.bounds);
        final Point position=new Point();
        Window main;
        boolean attached=true;
        int mode=1,rotation,focusWrites;
        public Window getTopVisibleAppMainWindow(){return main;}
        public boolean isFocusable(){return true;}
        public void setFocusable(boolean value){focusWrites++;}
        public Display getDisplayContent(){return display;}
        public Task getParent(){return this;}
        public Rect getBounds(){return bounds;}
        public boolean isAttached(){return attached;}
        public int getWindowingMode(){return mode;}
        public int getRelativeDisplayRotation(){return rotation;}
        public void getRelativePosition(Point value){value.set(position.x,position.y);}
        public Configuration getConfiguration(){return configuration;}
        public SurfaceControl.Transaction getPendingTransaction(){return transaction;}
    }
    public static final class Report {
        Configuration configuration;
        public Configuration getMergedConfiguration(){return configuration;}
    }
    public static final class Window {
        public final WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
        public final Report mLastReportedConfiguration=new Report();
        final Configuration configuration=new Configuration();
        boolean drawn=true,visible=true;
        public boolean isDrawn(){return drawn;}
        public boolean isVisible(){return visible;}
        public Configuration getConfiguration(){return configuration;}
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        float alpha=1;
        boolean released,hidden;
        int hideCalls;
        @Implementation protected boolean isValid(){return !released;}
        @Implementation protected void release(){released=true;}
    }
    @Implements(SurfaceControl.Builder.class)
    public static class BuilderShadow {
        @RealObject SurfaceControl.Builder real;
        @Implementation protected void __constructor__(){}
        @Implementation protected SurfaceControl.Builder setName(String value){return real;}
        @Implementation protected SurfaceControl.Builder setParent(SurfaceControl value){return real;}
        @Implementation protected SurfaceControl.Builder setHidden(boolean value){return real;}
        @Implementation protected SurfaceControl.Builder setContainerLayer(){return real;}
        @Implementation protected SurfaceControl.Builder setColorLayer(){return real;}
        @Implementation protected SurfaceControl.Builder setBufferSize(int width,int height){return real;}
        @Implementation protected SurfaceControl.Builder setFormat(int format){return real;}
        @Implementation protected SurfaceControl build(){return Shadow.newInstanceOf(SurfaceControl.class);}
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;
        @Implementation protected void __constructor__(){}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl surface,float value){((SurfaceShadow)Shadow.extract(surface)).alpha=value;return real;}
        @Implementation protected SurfaceControl.Transaction setRelativeLayer(SurfaceControl surface,SurfaceControl relative,int z){return real;}
        @Implementation protected SurfaceControl.Transaction setLayer(SurfaceControl surface,int z){return real;}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl surface,float x,float y){return real;}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl surface,float x,float y){return real;}
        @Implementation protected SurfaceControl.Transaction setColor(SurfaceControl surface,float[] color){return real;}
        @Implementation protected SurfaceControl.Transaction setWindowCrop(SurfaceControl surface,int width,int height){return real;}
        @Implementation protected SurfaceControl.Transaction setCornerRadius(SurfaceControl surface,float radius){return real;}
        @Implementation protected SurfaceControl.Transaction show(SurfaceControl surface){((SurfaceShadow)Shadow.extract(surface)).hidden=false;return real;}
        @Implementation protected SurfaceControl.Transaction hide(SurfaceControl surface){SurfaceShadow state=Shadow.extract(surface);state.hidden=true;state.hideCalls++;return real;}
        @Implementation protected SurfaceControl.Transaction remove(SurfaceControl surface){return real;}
        @Implementation protected void apply(){}
        @Implementation protected void close(){}
    }
    @Implements(Choreographer.class)
    public static class FramesShadow {
        final List<Choreographer.FrameCallback> pending=new ArrayList<>();
        @Implementation protected void postFrameCallback(Choreographer.FrameCallback callback){pending.add(callback);}
        void next(){assertFalse("pollCover did not schedule a frame",pending.isEmpty());pending.remove(0).doFrame(System.nanoTime());}
    }
    public static final class Wms { public final List<Object> overlayHidingWindows=new ArrayList<>(); }
    public static final class PriorityWindow {
        final Display display;
        PriorityWindow(Display display){this.display=display;}
        public Object getTask(){return null;}
        public int getOwningUid(){return 1000;}
        public Display getDisplayContent(){return display;}
        public boolean isVisible(){return true;}
    }
    private void configure(Configuration configuration,Rect bounds,int mode,int rotation)throws Exception{
        configuration.densityDpi=489;configuration.screenWidthDp=393;configuration.screenHeightDp=874;configuration.orientation=1;
        Object window=access.field(configuration,"windowConfiguration");
        access.call(window,"setBounds",new Class<?>[]{Rect.class},new Rect(bounds));
        access.call(window,"setAppBounds",new Class<?>[]{Rect.class},new Rect(bounds));
        access.call(window,"setWindowingMode",new Class<?>[]{int.class},mode);
        access.call(window,"setRotation",new Class<?>[]{int.class},rotation);
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();Settings.Global.putFloat(context.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        access=allocate(FrameworkAccess.class);set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"wc",Task.class);set(access,"playingTransition",Controller.class.getMethod("inPlayingTransition",Task.class));
        task=new Task();main=new Window();task.main=main;main.mAttrs.type=WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        configure(task.configuration,task.display.bounds,1,0);configure(main.configuration,task.display.bounds,1,0);
        main.mLastReportedConfiguration.configuration=new Configuration(main.configuration);
        engine=new WindowEngine(access,logs::add);handler=new Handler(Looper.getMainLooper());
        set(engine,"handler",handler);set(engine,"lock",new Object());
        AtomicFile journal=new AtomicFile(temporary.newFile("recovery.json"));journal.delete();set(engine,"journal",journal);
        set(engine,"historyFile",new AtomicFile(temporary.newFile("history.json")));
        session=new WindowEngine.Session();session.task=task;session.taskId=42;session.uid=10142;session.pkg="example.app";
        session.frame="mini";session.coverFullscreen=true;session.restoreForeground=true;session.nativeForegroundRestore=true;
        session.restoring=true;session.coverDrawn=true;session.bounds=new Rect();session.mode=1;
        session.placement=new Geometry.Placement(400,700,.25f,40,80);
        set(engine,"sessions",List.of());
    }
    private boolean ready()throws Throwable{
        return (Boolean)call("fullscreenCoverReady",new Class<?>[]{WindowEngine.Session.class,Object.class},session,main);
    }
    private TaskCover cover()throws Exception{
        cover=new TaskCover(access,context,task,task,session.pkg,new RectF(40,80,140,255),new RectF(task.display.bounds),20,0,1,false);
        session.cover=cover;return cover;
    }
    private float alpha(){return ((SurfaceShadow)Shadow.extract(task.mSurfaceControl)).alpha;}
    @After public void cleanup(){if(cover!=null)cover.close();handler.removeCallbacksAndMessages(null);}

    @Test public void nativeFullscreenNeedsDrawnConfigurationPositionAndAllTransitionsFinished()throws Throwable{
        assertTrue(ready());
        main.drawn=false;assertFalse(ready());main.drawn=true;
        main.visible=false;assertFalse(ready());main.visible=true;
        main.mAttrs.type=WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;assertFalse(ready());main.mAttrs.type=WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        task.mode=5;assertFalse(ready());task.mode=1;
        task.bounds.bottom=2133;assertFalse(ready());task.bounds.set(task.display.bounds);
        task.position.x=1;assertFalse(ready());task.position.x=0;
        task.rotation=1;assertFalse(ready());task.rotation=0;
        task.mSurfaceAnimator.leash=true;assertFalse(ready());task.mSurfaceAnimator.leash=false;
        task.mTransitionController.playing=true;assertFalse(ready());task.mTransitionController.playing=false;
        task.mTransitionController.collecting=true;assertFalse(ready());task.mTransitionController.collecting=false;
        main.mLastReportedConfiguration.configuration=new Configuration();assertFalse(ready());
        main.mLastReportedConfiguration.configuration=new Configuration(main.configuration);assertTrue(ready());
    }
    @Test public void equalSizedOffsetOrStaleClientConfigurationCannotReveal()throws Throwable{
        configure(main.mLastReportedConfiguration.configuration,new Rect(1,0,1201,2670),1,0);assertFalse(ready());
        main.mLastReportedConfiguration.configuration=new Configuration(main.configuration);
        main.mLastReportedConfiguration.configuration.densityDpi=480;assertFalse(ready());
        main.mLastReportedConfiguration.configuration=new Configuration(main.configuration);
        main.mLastReportedConfiguration.configuration.screenHeightDp--;assertFalse(ready());
        main.mLastReportedConfiguration.configuration=new Configuration(main.configuration);
        configure(main.configuration,task.display.bounds,5,0);assertFalse(ready());
        configure(main.configuration,task.display.bounds,1,1);assertFalse(ready());
        configure(main.configuration,task.display.bounds,1,0);assertTrue(ready());
    }
    @Test public void nativeRestorationMustForgetTheSessionBeforeRevealing()throws Throwable{
        set(engine,"sessions",List.of(session));assertFalse(ready());
        set(engine,"sessions",List.of());assertTrue(ready());
    }
    @Test public void realCoverHoldsAppUntilReadinessAndMotionThenFadesForExistingDuration()throws Throwable{
        TaskCover value=cover();long began=SystemClock.elapsedRealtime();
        assertEquals(0,alpha(),0);assertTrue(value.mask.holding());
        main.drawn=false;assertTrue(value.step(ready(),true,began+400));assertEquals(0,alpha(),0);assertTrue(value.mask.holding());
        main.drawn=true;assertTrue(value.step(ready(),true,began+401));assertEquals(1,alpha(),0);assertFalse(value.mask.holding());
        assertTrue(value.step(true,true,began+550));assertFalse(value.step(true,true,began+551));
    }
    @Test public void readinessAloneCannotRevealBeforeMiniToFullMotionCompletes()throws Throwable{
        TaskCover value=cover();long began=SystemClock.elapsedRealtime();assertTrue(ready());
        assertTrue(value.step(true,true,began+319));assertEquals(0,alpha(),0);assertTrue(value.mask.holding());
        assertTrue(value.step(true,true,began+320));assertEquals(1,alpha(),0);assertFalse(value.mask.holding());
    }
    @Test public void hiddenCoverCannotRevealAndItsMotionClockPauses()throws Throwable{
        TaskCover value=cover();long began=SystemClock.elapsedRealtime();
        assertTrue(value.step(true,false,began));assertTrue(value.step(true,false,began+1000));
        assertEquals(0,alpha(),0);assertTrue(value.mask.holding());
        assertTrue(value.step(true,true,began+1000));assertEquals(0,alpha(),0);assertTrue(value.mask.holding());
        assertTrue(value.step(true,true,began+1320));assertEquals(1,alpha(),0);assertFalse(value.mask.holding());
    }
    @SuppressWarnings("unchecked")
    @Test public void foregroundForgetRetainsCoverOwnershipForItsPendingReveal()throws Throwable{
        TaskCover value=cover();Map<Object,WindowEngine.Session> covers=(Map<Object,WindowEngine.Session>)get(engine,"coverTasks");
        covers.put(task,session);set(engine,"sessions",List.of(session));
        call("forget",new Class<?>[]{WindowEngine.Session.class},session);
        assertSame(session,covers.get(task));assertSame(value,session.cover);assertTrue(value.mask.holding());assertEquals(0,alpha(),0);
        assertFalse(engine.owns(task));assertTrue(ready());
    }
    @Test public void retiredPollNeverHidesNativeTaskAndClearedPriorityReleasesCurrentAlpha()throws Throwable{
        Settings.Global.putFloat(context.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0f);
        TaskCover value=cover();Choreographer frames=Shadow.newInstanceOf(Choreographer.class);
        FramesShadow callbacks=Shadow.extract(frames);Wms wms=new Wms();wms.overlayHidingWindows.add(new PriorityWindow(task.display));
        set(access,"overlayHidingWindows",Wms.class.getField("overlayHidingWindows"));
        set(engine,"context",context);set(engine,"wms",wms);set(engine,"frames",frames);
        org.robolectric.Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        org.robolectric.Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        call("pollCover",new Class<?>[]{WindowEngine.Session.class,TaskCover.class},session,value);
        callbacks.next();
        SurfaceShadow nativeSurface=Shadow.extract(task.mSurfaceControl);
        assertTrue(logs.toString(),session.prioritySuspended);assertTrue(logs.toString(),session.priorityHideTask);assertEquals(0,alpha(),0);
        assertEquals(0,nativeSurface.hideCalls);assertFalse(nativeSurface.hidden);assertEquals(0,task.focusWrites);assertTrue(value.mask.holding());
        wms.overlayHidingWindows.clear();callbacks.next();
        assertFalse(session.prioritySuspended);assertFalse(session.priorityHideTask);assertFalse(value.mask.holding());
        assertEquals(1,alpha(),0);assertEquals(0,nativeSurface.hideCalls);assertFalse(nativeSurface.hidden);assertEquals(0,task.focusWrites);
    }
    @SuppressWarnings("unchecked")
    @Test public void replacingRetiredSessionCoverTransfersMaskAndOldEndCannotRemoveNewOwner()throws Throwable{
        TaskCover old=cover();Map<Object,WindowEngine.Session> covers=(Map<Object,WindowEngine.Session>)get(engine,"coverTasks");
        covers.put(task,session);
        WindowEngine.Session next=new WindowEngine.Session();next.task=task;next.pkg=session.pkg;next.coverFullscreen=true;
        TaskCover replacement=new TaskCover(access,context,task,task,next.pkg,new RectF(40,80,140,255),new RectF(task.display.bounds),20,0,1,false);
        cover=replacement;next.cover=replacement;
        call("prepareCover",new Class<?>[]{WindowEngine.Session.class,TaskCover.class},next,replacement);
        assertNull(session.cover);assertSame(next,covers.get(task));assertSame(replacement,next.cover);
        assertFalse(old.mask.holding());assertTrue(replacement.mask.holding());assertEquals(0,alpha(),0);
        call("endCover",new Class<?>[]{WindowEngine.Session.class},session);
        assertSame(next,covers.get(task));assertSame(replacement,next.cover);assertTrue(replacement.mask.holding());assertEquals(0,alpha(),0);
    }
    @Test public void existingFiveSecondDeadlineDoesNotSilentlyRevealAnUnreadyTask()throws Throwable{
        TaskCover value=cover();long began=SystemClock.elapsedRealtime();
        assertThrows(IllegalStateException.class,()->value.step(false,true,began+5000));
        assertTrue(value.mask.holding());assertEquals(0,alpha(),0);
    }
}
