package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={TaskBackdropTest.SurfaceShadow.class,TaskBackdropTest.BuilderShadow.class,TaskBackdropTest.TransactionShadow.class})
public class TaskBackdropTest {
    public static final class Task {
        public SurfaceControl mSurfaceControl=surface();
        final SurfaceControl.Transaction pending=new SurfaceControl.Transaction();
        public SurfaceControl.Transaction getPendingTransaction(){return pending;}
    }
    private static SurfaceControl surface(){return ReflectionHelpers.callConstructor(SurfaceControl.class);}
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(a,"fields",new java.util.concurrent.ConcurrentHashMap<>());return a;
    }
    private static TransactionShadow tx(Task task){return Shadow.extract(task.pending);}
    @Test public void whiteChildIsBehindContentAndInheritsTaskPresentationWithoutInputOrSiblingChanges()throws Exception{
        Task task=new Task();TaskDecoration decoration=new TaskDecoration(access());decoration.updateBacking(task,1200,2133,true);
        SurfaceControl backing=(SurfaceControl)get(decoration,"backing");SurfaceShadow layer=Shadow.extract(backing);TransactionShadow tx=tx(task);
        assertSame(task.mSurfaceControl,layer.parent);assertTrue(layer.colorLayer);assertTrue(layer.hiddenAtBuild);
        assertEquals(Integer.MIN_VALUE,(int)tx.layers.get(backing));assertArrayEquals(new float[]{1,1,1},tx.colors.get(backing),0);
        assertEquals(new Rect(0,0,1200,2133),tx.crops.get(backing));assertTrue(tx.shown.contains(backing));
        assertEquals(1,tx.layers.size());assertEquals(1,tx.colors.size());assertEquals(0,tx.relativeCalls+tx.positionCalls+tx.scaleCalls+tx.alphaCalls+tx.radiusCalls+tx.inputCalls);
        assertFalse(tx.shown.contains(task.mSurfaceControl));
        int operations=tx.operations;decoration.updateBacking(task,1200,2133,true);assertEquals(operations,tx.operations);
    }
    @Test public void resizeUsesLogicalCanvasAndHideRestoresSameBackingOnNextApply()throws Exception{
        Task task=new Task();TaskDecoration decoration=new TaskDecoration(access());decoration.updateBacking(task,1200,2133,true);
        SurfaceControl backing=(SurfaceControl)get(decoration,"backing");decoration.hideHandle(task);assertTrue(tx(task).shown.contains(backing));
        decoration.hide(task);assertTrue(tx(task).shown.contains(backing));
        decoration.updateBacking(task,1200,2133,false);assertFalse(tx(task).shown.contains(backing));
        decoration.updateBacking(task,2133,1200,true);assertSame(backing,get(decoration,"backing"));assertTrue(tx(task).shown.contains(backing));
        assertEquals(new Rect(0,0,2133,1200),tx(task).crops.get(backing));assertEquals(1,tx(task).colors.size());
    }
    @Test public void changedTaskSurfaceRebuildsOnlyOwnedChildAndReleasesNoTaskHandle()throws Exception{
        Task task=new Task();TaskDecoration decoration=new TaskDecoration(access());decoration.updateBacking(task,600,1000,true);
        SurfaceControl oldTask=task.mSurfaceControl,oldBacking=(SurfaceControl)get(decoration,"backing");task.mSurfaceControl=surface();
        decoration.updateBacking(task,600,1000,true);SurfaceControl replacement=(SurfaceControl)get(decoration,"backing");
        assertNotSame(oldBacking,replacement);assertTrue(tx(task).removed.contains(oldBacking));assertFalse(oldBacking.isValid());
        assertTrue(oldTask.isValid());assertTrue(task.mSurfaceControl.isValid());assertSame(task.mSurfaceControl,((SurfaceShadow)Shadow.extract(replacement)).parent);
        decoration.remove(task);assertTrue(tx(task).removed.contains(replacement));assertFalse(replacement.isValid());assertNull(get(decoration,"backing"));
        assertTrue(task.mSurfaceControl.isValid());
    }
    @Test public void disposalAndInvalidGeometryNeverLeaveAnOwnedChildOrReleaseParent()throws Exception{
        Task task=new Task();TaskDecoration decoration=new TaskDecoration(access());decoration.updateBacking(task,600,1000,true);
        SurfaceControl backing=(SurfaceControl)get(decoration,"backing");decoration.dispose();assertNull(get(decoration,"backing"));assertFalse(backing.isValid());
        assertTrue(task.mSurfaceControl.isValid());decoration.dispose();
        decoration.updateBacking(task,600,1000,true);backing=(SurfaceControl)get(decoration,"backing");
        decoration.updateBacking(task,0,1000,true);assertNull(get(decoration,"backing"));assertTrue(tx(task).removed.contains(backing));
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        boolean released,colorLayer,hiddenAtBuild;SurfaceControl parent;
        @Implementation protected boolean isValid(){return !released;}
        @Implementation protected void release(){released=true;}
    }
    @Implements(className="android.view.SurfaceControl$Builder",looseSignatures=true)
    public static class BuilderShadow {
        @RealObject Object real;
        SurfaceControl parent;boolean colorLayer,hidden;
        @Implementation protected void __constructor__(){}
        @Implementation protected Object setName(String name){return real;}
        @Implementation protected Object setParent(SurfaceControl value){parent=value;return real;}
        @Implementation protected Object setColorLayer(){colorLayer=true;return real;}
        @Implementation protected Object setHidden(boolean value){hidden=value;return real;}
        @Implementation protected SurfaceControl build(){SurfaceControl child=surface();SurfaceShadow s=Shadow.extract(child);s.parent=parent;s.colorLayer=colorLayer;s.hiddenAtBuild=hidden;return child;}
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;
        final Map<SurfaceControl,Integer> layers=new IdentityHashMap<>();final Map<SurfaceControl,float[]> colors=new IdentityHashMap<>();
        final Map<SurfaceControl,Rect> crops=new IdentityHashMap<>();final Set<SurfaceControl> shown=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<SurfaceControl> removed=Collections.newSetFromMap(new IdentityHashMap<>());
        int operations,relativeCalls,positionCalls,scaleCalls,alphaCalls,radiusCalls,inputCalls;
        @Implementation protected void __constructor__(){}
        @Implementation protected void apply(){}
        @Implementation protected void close(){}
        @Implementation protected SurfaceControl.Transaction setLayer(SurfaceControl s,int layer){layers.put(s,layer);operations++;return real;}
        @Implementation protected SurfaceControl.Transaction setColor(SurfaceControl s,float[] color){colors.put(s,color.clone());operations++;return real;}
        @Implementation protected SurfaceControl.Transaction setWindowCrop(SurfaceControl s,int width,int height){crops.put(s,new Rect(0,0,width,height));operations++;return real;}
        @Implementation protected SurfaceControl.Transaction show(SurfaceControl s){shown.add(s);operations++;return real;}
        @Implementation protected SurfaceControl.Transaction hide(SurfaceControl s){shown.remove(s);operations++;return real;}
        @Implementation protected SurfaceControl.Transaction remove(SurfaceControl s){removed.add(s);operations++;return real;}
        @Implementation protected SurfaceControl.Transaction setRelativeLayer(SurfaceControl s,SurfaceControl relative,int layer){relativeCalls++;return real;}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl s,float x,float y){positionCalls++;return real;}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl s,float x,float y){scaleCalls++;return real;}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl s,float alpha){alphaCalls++;return real;}
        @Implementation protected SurfaceControl.Transaction setCornerRadius(SurfaceControl s,float radius){radiusCalls++;return real;}
    }
}
