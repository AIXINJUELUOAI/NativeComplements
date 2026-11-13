package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows={SurfaceFrameTest.TransactionShadow.class,SurfaceFrameTest.SurfaceShadow.class})
public class SurfaceFrameTest {
    private static Object requiredLock;
    private static TransactionShadow lastApplied;
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static <T> T allocate(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static FrameworkAccess access()throws Exception{
        FrameworkAccess access=allocate(FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"finishLayers",SurfaceFrameTest.class.getDeclaredMethod("assignLayers",Object.class,SurfaceControl.Transaction.class));
        return access;
    }
    public static void assignLayers(Object parent,SurfaceControl.Transaction transaction){}
    @After public void reset(){requiredLock=null;lastApplied=null;}

    @Test public void pendingIconSnapshotDoesNotReadOrUploadApplicationOriginal()throws Exception{
        Class<?> assets=dev.local.sunwindowdemo.features.iconlayers.IconAssets.class;
        Field ready=assets.getDeclaredField("ready");ready.setAccessible(true);boolean before=ready.getBoolean(null);
        try{
            ready.setBoolean(null,false);TaskCover cover=allocate(TaskCover.class);
            Method draw=TaskCover.class.getDeclaredMethod("drawIcon");draw.setAccessible(true);
            // No context or surface exists: touching either fallback would fail this pending-cache path.
            draw.invoke(cover);
            Field drawn=TaskCover.class.getDeclaredField("iconDrawn");drawn.setAccessible(true);assertFalse(drawn.getBoolean(cover));
        }finally{ready.setBoolean(null,before);}
    }

    @Test public void frameCreationAndCommitHoldTheWindowManagerLock()throws Exception{
        WindowEngine engine=new WindowEngine(null,message->{});
        requiredLock=new Object();set(engine,"lock",requiredLock);set(engine,"a",access());
        Class<?> session=Class.forName(WindowEngine.class.getName()+"$Session");
        Constructor<?> constructor=session.getDeclaredConstructor();constructor.setAccessible(true);
        Method render=WindowEngine.class.getDeclaredMethod("renderFrame",session);render.setAccessible(true);
        // An unmanaged session returns before touching a task but still opens and commits a frame.
        render.invoke(engine,constructor.newInstance());
        assertFalse(Thread.holdsLock(requiredLock));
    }

    public static final class Controller {
        public boolean mBuildingTransitionLayers;
        public boolean isPlaying(){return false;}
        public boolean isCollecting(){return false;}
    }
    public static final class Window {
        public boolean isDrawn(){return true;}
        public boolean isVisible(){return true;}
    }
    public static final class Task {
        public final SurfaceControl mSurfaceControl=ReflectionHelpers.callConstructor(SurfaceControl.class);
        public final Controller mTransitionController=new Controller();
        public final SurfaceControl.Transaction pending=new SurfaceControl.Transaction();
        public SurfaceControl.Transaction getPendingTransaction(){return pending;}
        public Window getTopVisibleAppMainWindow(){return new Window();}
        public Rect getBounds(){return new Rect(0,0,1200,2600);}
    }
    @Test public void backdropStaysBelowTheBrightTaskAndDisappearsWithTheHandle()throws Exception{
        Task task=new Task(),parent=new Task();TaskDecoration decoration=new TaskDecoration(access());
        decoration.dimAmount(40);
        SurfaceControl bar=ReflectionHelpers.callConstructor(SurfaceControl.class);
        SurfaceControl dim=ReflectionHelpers.callConstructor(SurfaceControl.class);
        set(decoration,"bar",bar);set(decoration,"dim",dim);
        TransactionShadow transaction=Shadow.extract(task.pending);
        Geometry.Placement placement=new Geometry.Placement(900,1600,.7f,200,300);
        assertTrue(decoration.update(task,parent,placement,1,false,1,true));
        assertEquals(0xfff0f2f2,decoration.color());
        assertArrayEquals(new float[]{240/255f,242/255f,242/255f},transaction.colors.get(bar),0);
        assertEquals(Integer.valueOf(1),transaction.layers.get(bar));
        assertEquals(Integer.valueOf(-1),transaction.layers.get(dim));
        assertSame(task.mSurfaceControl,transaction.relativeTo.get(dim));
        assertEquals(.4f,transaction.alpha.get(dim),0);
        assertFalse("Dimming must never change the task's own alpha",transaction.alpha.containsKey(task.mSurfaceControl));
        assertEquals(new Rect(0,0,1200,2600),transaction.crops.get(dim));
        assertTrue(decoration.update(task,parent,placement,1,true,.5f,false));
        assertEquals(.2f,transaction.alpha.get(dim),0);
        assertTrue(decoration.update(task,parent,placement,1,false,0,false));
        assertFalse(transaction.shown.contains(bar));assertFalse(transaction.shown.contains(dim));
        decoration.update(task,parent,placement,1,false,1,false);
        decoration.hide(task);assertFalse(transaction.shown.contains(dim));
        decoration.remove(task);
        assertTrue(transaction.removed.contains(bar));assertTrue(transaction.removed.contains(dim));
        SurfaceShadow surface=Shadow.extract(dim);assertTrue(surface.released);
    }

    @Test public void handleAndBackdropCanUseIndependentOpacityInTheSameTransaction()throws Exception{
        Task task=new Task(),parent=new Task();TaskDecoration decoration=new TaskDecoration(access());
        decoration.dimAmount(40);
        SurfaceControl bar=ReflectionHelpers.callConstructor(SurfaceControl.class),dim=ReflectionHelpers.callConstructor(SurfaceControl.class);
        set(decoration,"bar",bar);set(decoration,"dim",dim);
        SurfaceControl.Transaction frame=new SurfaceControl.Transaction();TransactionShadow transaction=Shadow.extract(frame);
        assertTrue(decoration.update(task,parent,new Geometry.Placement(900,1600,.7f,200,300),1,false,0,.5f,true,frame));
        assertFalse("Zero-opacity handle stays hidden while the backdrop is visible",transaction.shown.contains(bar));
        assertEquals(.2f,transaction.alpha.get(dim),0);
        assertFalse(((TransactionShadow)Shadow.extract(task.pending)).alpha.containsKey(dim));
    }
    @Test public void backdropStrengthDefaultsToTwentyPercentAndClampsWithoutDimmingTheTask()throws Exception{
        Task task=new Task(),parent=new Task();TaskDecoration decoration=new TaskDecoration(access());
        SurfaceControl bar=ReflectionHelpers.callConstructor(SurfaceControl.class),dim=ReflectionHelpers.callConstructor(SurfaceControl.class);
        set(decoration,"bar",bar);set(decoration,"dim",dim);TransactionShadow transaction=Shadow.extract(task.pending);
        Geometry.Placement placement=new Geometry.Placement(900,1600,.7f,200,300);
        decoration.update(task,parent,placement,1,false,1,true);assertEquals(.2f,transaction.alpha.get(dim),0);
        for(int percent:new int[]{0,20,35,100,-10,120}){
            decoration.dimAmount(percent);decoration.update(task,parent,placement,1,false,.5f,false);
            assertEquals(Math.max(0,Math.min(100,percent))/200f,transaction.alpha.get(dim),.0001f);
            assertEquals(.5f,transaction.alpha.get(bar),0);assertFalse(transaction.alpha.containsKey(task.mSurfaceControl));
        }
    }
    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void slowAppStillGetsContinuousDecorationEntryBeforeCoverRemoval()throws Exception{
        FrameworkAccess access=access();Task task=new Task(),parent=new Task();
        TaskDecoration decoration=new TaskDecoration(access);
        decoration.dimAmount(40);
        SurfaceControl bar=ReflectionHelpers.callConstructor(SurfaceControl.class),dim=ReflectionHelpers.callConstructor(SurfaceControl.class);
        set(decoration,"bar",bar);set(decoration,"dim",dim);
        TaskCover cover=allocate(TaskCover.class);
        set(cover,"iconDrawn",true); // This fixture exercises decoration surfaces, not bitmap uploads.
        set(cover,"a",access);set(cover,"task",task);set(cover,"parent",parent);
        set(cover,"background",ReflectionHelpers.callConstructor(SurfaceControl.class));set(cover,"icon",ReflectionHelpers.callConstructor(SurfaceControl.class));
        RectF bounds=new RectF(200,300,830,1420);set(cover,"from",new RectF(bounds));set(cover,"to",new RectF(bounds));set(cover,"decorationBounds",new RectF(bounds));
        set(cover,"color",new float[]{.94f,.94f,.94f});set(cover,"iconSize",100);set(cover,"splashPx",162f);
        set(cover,"duration",320L);set(cover,"fadeDuration",150L);set(cover,"began",android.os.SystemClock.elapsedRealtime());
        set(cover,"fadeIn",true);set(cover,"entryAt",-1L);set(cover,"fadeAt",-1L);set(cover,"hiddenAt",-1L);set(cover,"decorationAt",-1L);
        set(cover,"presentationVisible",true);set(cover,"exitAlpha",1f);set(cover,"ownerAlpha",1f);
        TaskVisibilityGuard mask=new TaskVisibilityGuard(access,task);set(mask,"finished",true);set(cover,"mask",mask);
        cover.entryDecorations(true);
        var looper=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        assertTrue(cover.step(false,true));looper.idleFor(java.time.Duration.ofMillis(600));
        assertTrue(cover.step(false,true));
        assertTrue(cover.decorate(decoration,new Geometry.Placement(900,1600,.7f,200,300),1,false,1,true));
        assertTrue(cover.step(true,true));assertFalse(lastApplied.shown.contains(dim));assertFalse(lastApplied.shown.contains(bar));
        looper.idleFor(java.time.Duration.ofMillis(100));assertTrue(cover.step(true,true));
        assertTrue(lastApplied.alpha.get(dim)>0&&lastApplied.alpha.get(dim)<.4f);
        assertTrue(lastApplied.alpha.get(bar)<lastApplied.alpha.get(dim)/.4f);
        looper.idleFor(java.time.Duration.ofMillis(220));assertTrue(cover.step(true,true));
        assertEquals(.4f,lastApplied.alpha.get(dim),.0001f);assertEquals(1,lastApplied.alpha.get(bar),0);
        looper.idleFor(java.time.Duration.ofMillis(150));assertFalse(cover.step(true,true));
        decoration.update(task,parent,new Geometry.Placement(900,1600,.7f,200,300),1,false,1,true);
        assertEquals(.4f,((TransactionShadow)Shadow.extract(task.pending)).alpha.get(dim),.0001f);
    }
    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void coveredPresentationIsReusedOnlyWhileOpaqueAndRefreshedBeforeReveal()throws Exception{
        FrameworkAccess access=access();Task task=new Task(),parent=new Task();
        TaskCover cover=allocate(TaskCover.class);
        set(cover,"a",access);set(cover,"task",task);set(cover,"parent",parent);set(cover,"iconDrawn",true);
        set(cover,"background",ReflectionHelpers.callConstructor(SurfaceControl.class));
        set(cover,"icon",ReflectionHelpers.callConstructor(SurfaceControl.class));
        set(cover,"from",new RectF(200,300,830,1420));set(cover,"to",new RectF(300,300,930,1420));
        set(cover,"decorationBounds",new RectF());set(cover,"color",new float[]{.94f,.94f,.94f});
        set(cover,"iconSize",100);set(cover,"splashPx",162f);
        set(cover,"duration",320L);set(cover,"fadeDuration",150L);
        set(cover,"began",android.os.SystemClock.elapsedRealtime());set(cover,"fadeAt",-1L);
        set(cover,"entryAt",-1L);set(cover,"hiddenAt",-1L);set(cover,"ownerAlpha",1f);
        TaskVisibilityGuard mask=new TaskVisibilityGuard(access,task);set(cover,"mask",mask);
        Geometry.Placement pose=new Geometry.Placement(900,1600,.7f,200,300);
        cover.prepared(pose,2.5f);
        assertFalse(cover.reusesPresentation(pose));
        assertTrue(cover.canRetarget());
        assertTrue(cover.step(false,true));assertTrue(cover.reusesPresentation(pose));
        long clock=android.os.SystemClock.elapsedRealtime();
        cover.step(false,true,clock+8);
        float first=((RectF)ReflectionHelpers.getField(cover,"decorationBounds")).left;
        cover.step(false,true,clock+16);
        assertTrue(((RectF)ReflectionHelpers.getField(cover,"decorationBounds")).left>first);
        assertEquals(2.5f,cover.preparedDensity(),0);
        assertFalse(cover.reusesPresentation(new Geometry.Placement(1000,1600,.7f,200,300)));
        cover.step(false,false);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));
        assertTrue(cover.step(false,true));assertTrue(mask.holding());
        int[] reveals={0};
        cover.beforeReveal(()->{
            assertTrue(mask.holding());assertFalse(cover.reusesPresentation(pose));reveals[0]++;
        });
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(321));
        assertTrue(cover.step(false,true));assertEquals(0,reveals[0]);
        assertTrue(cover.step(true,true));assertEquals(1,reveals[0]);
        assertFalse(mask.holding());assertFalse(cover.canRetarget());assertFalse(cover.reusesPresentation(pose));
        cover.step(true,true);assertEquals(1,reveals[0]);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(151));
        assertFalse(cover.step(true,true));assertFalse(cover.canRetarget());
    }

    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        boolean released;
        @Implementation protected boolean isValid(){return !released;}
        @Implementation protected void release(){released=true;}
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;
        final Map<SurfaceControl,Float> alpha=new IdentityHashMap<>();
        final Map<SurfaceControl,Integer> layers=new IdentityHashMap<>();
        final Map<SurfaceControl,SurfaceControl> relativeTo=new IdentityHashMap<>();
        final Map<SurfaceControl,float[]> colors=new IdentityHashMap<>();
        final Map<SurfaceControl,Rect> crops=new IdentityHashMap<>();
        final Set<SurfaceControl> shown=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<SurfaceControl> removed=Collections.newSetFromMap(new IdentityHashMap<>());
        private static void checkLock(){if(requiredLock!=null)assertTrue("Frame transactions must hold the WM lock",Thread.holdsLock(requiredLock));}
        @Implementation protected void __constructor__(){checkLock();}
        @Implementation protected void apply(){checkLock();lastApplied=this;}
        @Implementation protected void close(){checkLock();}
        @Implementation protected SurfaceControl.Transaction setRelativeLayer(SurfaceControl s,SurfaceControl relative,int layer){layers.put(s,layer);relativeTo.put(s,relative);return real;}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl s,float x,float y){return real;}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl s,float x,float y){return real;}
        @Implementation protected SurfaceControl.Transaction setWindowCrop(SurfaceControl s,int w,int h){crops.put(s,new Rect(0,0,w,h));return real;}
        @Implementation protected SurfaceControl.Transaction setCornerRadius(SurfaceControl s,float radius){return real;}
        @Implementation protected SurfaceControl.Transaction setColor(SurfaceControl s,float[] color){colors.put(s,color.clone());return real;}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl s,float value){alpha.put(s,value);return real;}
        @Implementation protected SurfaceControl.Transaction show(SurfaceControl s){shown.add(s);return real;}
        @Implementation protected SurfaceControl.Transaction hide(SurfaceControl s){shown.remove(s);return real;}
        @Implementation protected SurfaceControl.Transaction remove(SurfaceControl s){removed.add(s);return real;}
    }
}
