package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WindowBehaviorTest {
    private static void set(Object owner,String name,Object value)throws Exception{
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    private static Object allocate(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
    }
    public static class Task {
        public SurfaceControl mSurfaceControl;
        public Task parent;public Transaction pending=new Transaction();
        public Task getParent(){return parent;}
        public Transaction getPendingTransaction(){return pending;}
    }
    public static class Transaction {
        SurfaceControl hidden,shown,anchor,parent;float alpha;
        public void reparent(SurfaceControl s,SurfaceControl target){parent=target;}
        public void hide(SurfaceControl s){hidden=s;}
        public void show(SurfaceControl s){shown=s;}
        public void setRelativeLayer(SurfaceControl s,SurfaceControl target,int z){anchor=target;assertEquals(-1,z);}
        public void setPosition(SurfaceControl s,float x,float y){}
        public void setWindowCrop(SurfaceControl s,int w,int h){}
        public void setAlpha(SurfaceControl s,float value){alpha=value;}
    }
    @Test public void undrawnReplacementMainHidesHandleButKeepsEstablishedDim()throws Exception{
        FrameworkAccess access=(FrameworkAccess)allocate(FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        TaskDecoration decoration=new TaskDecoration(access);
        SurfaceControl dim=(SurfaceControl)allocate(SurfaceControl.class),bar=(SurfaceControl)allocate(SurfaceControl.class);
        set(decoration,"dim",dim);set(decoration,"bar",bar);set(decoration,"dimReady",true);
        set(decoration,"parentBounds",new Rect(0,0,1200,2600));
        Transaction tx=new Transaction();Task task=new Task();task.mSurfaceControl=(SurfaceControl)allocate(SurfaceControl.class);
        assertFalse(decoration.update(task,null,new Geometry.Placement(900,1600,.7f,20,80),1,false,1,1,false,tx));
        assertSame(bar,tx.hidden);assertSame(dim,tx.shown);assertSame(task.mSurfaceControl,tx.anchor);
        assertEquals(dev.local.sunwindowdemo.Config.DEFAULT_WINDOW_DIM/100f,tx.alpha,.001f);
        tx.shown=null;decoration.update(task,null,new Geometry.Placement(900,1600,.7f,20,80),1,false,0,0,false,tx);
        assertSame(dim,tx.hidden);assertNull(tx.shown);
    }
    @Test public void linkedNormalTransferReparentsAndReanchorsOneBackdrop()throws Exception{
        FrameworkAccess access=(FrameworkAccess)allocate(FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        TaskDecoration from=new TaskDecoration(access),to=new TaskDecoration(access);
        SurfaceControl dim=(SurfaceControl)allocate(SurfaceControl.class);set(from,"dim",dim);set(from,"dimReady",true);
        Task task=new Task();task.parent=new Task();task.parent.mSurfaceControl=(SurfaceControl)allocate(SurfaceControl.class);
        task.mSurfaceControl=(SurfaceControl)allocate(SurfaceControl.class);
        from.transferDimTo(to,task);
        assertSame(task.parent.mSurfaceControl,task.pending.parent);assertSame(task.mSurfaceControl,task.pending.anchor);
        assertSame(dim,task.pending.shown);
        Field f=TaskDecoration.class.getDeclaredField("dim");f.setAccessible(true);assertNull(f.get(from));assertSame(dim,f.get(to));
    }
    @Test public void newDefaultOnlyMinimizesADifferentGroupWhenMiniSlotIsEmpty(){
        assertTrue(WindowPolicy.autoMini(true,false,"normal",false));
        assertFalse(WindowPolicy.autoMini(false,false,"normal",false));
        assertFalse(WindowPolicy.autoMini(true,true,"normal",false));
        assertFalse(WindowPolicy.autoMini(true,false,"normal",true));
        assertFalse(WindowPolicy.autoMini(true,false,"mini",false));
    }
    @Test public void fullscreenExceptionRequiresExactPrelaunchTaskUserDisplayAndOwnership(){
        Object task=new Object(),display=new Object();
        assertTrue(WindowPolicy.nativeForeground(task,task,"target","target",0,0,display,display,1,false));
        assertFalse(WindowPolicy.nativeForeground(task,new Object(),"target","target",0,0,display,display,1,false));
        assertFalse(WindowPolicy.nativeForeground(task,task,"target","other",0,0,display,display,1,false));
        assertFalse(WindowPolicy.nativeForeground(task,task,"target","target",0,10,display,display,1,false));
        assertFalse(WindowPolicy.nativeForeground(task,task,"target","target",0,0,display,new Object(),1,false));
        assertFalse(WindowPolicy.nativeForeground(task,task,"target","target",0,0,display,display,5,false));
        assertFalse(WindowPolicy.nativeForeground(task,task,"target","target",0,0,display,display,1,true));
        assertFalse(WindowPolicy.nativeForeground(null,task,"target","target",0,0,display,display,1,false));
    }
}
