package dev.local.sunwindowdemo;

import static org.junit.Assert.*;

import com.android.quickstep.GestureState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35)
public class SwipeMiniTest {
    @Test public void resolvesExactQuickstepMembers()throws Exception{
        SwipeMini.Targets t=SwipeMini.resolve(getClass().getClassLoader());
        assertEquals("updateDisplacement",t.displacement().getName());
        assertEquals("calculateEndTarget",t.endTarget().getName());
        assertEquals(GestureState.GestureEndTarget.class,t.endTarget().getReturnType());
        assertSame(GestureState.GestureEndTarget.HOME,t.home());
        assertSame(GestureState.GestureEndTarget.RECENTS,t.recentsTarget());
        assertEquals("mRecentsView",t.recents().getName());
        assertEquals("mGestureState",t.gesture().getName());
        assertEquals("mRemoteTargetHandles",t.handles().getName());
        assertEquals("getCurrentRect",t.currentRect().getName());
        assertEquals("getCurrentCornerRadius",t.currentRadius().getName());
        assertEquals("setTargetAlpha",t.targetAlpha().getName());
        assertEquals("findWorkspaceView",t.workspaceView().getName());
    }

    @Test public void thresholdIsStrictlyMoreThanOneThird()throws Exception{
        assertEquals(1f/3f,SwipeMini.THRESHOLD,0f);
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        int height=context.getResources().getDisplayMetrics().heightPixels;
        var handler=new com.android.quickstep.AbsSwipeUpHandler(){};
        var targets=SwipeMini.resolve(getClass().getClassLoader());
        targets.context().set(handler,context);
        targets.recents().set(handler,new com.android.quickstep.views.RecentsView(context));
        var track=SwipeMini.class.getDeclaredMethod("track",SwipeMini.Targets.class,Object.class,float.class);track.setAccessible(true);
        var armed=SwipeMini.class.getDeclaredField("armed");armed.setAccessible(true);
        try{
            SwipeMini.configure(new android.os.Binder(),false);
            track.invoke(null,targets,handler,-height/3f);
            assertFalse(armed.getBoolean(null));
            track.invoke(null,targets,handler,-height/3f-1);
            assertTrue(armed.getBoolean(null));
            track.invoke(null,targets,handler,-height/3f+1);
            assertFalse(armed.getBoolean(null));
        }finally{SwipeMini.configure(null,true);}
    }

    @Test public void onlyAcceptedHandlerUsesNativeNoIconBranch(){
        Object handler=new Object(),other=new Object(),icon=new Object();
        try{
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.acceptHandler(handler);
            assertNull(SwipeMini.workspaceTarget(handler,icon));
            assertNull(SwipeMini.workspaceTarget(handler,null));
            assertSame(icon,SwipeMini.workspaceTarget(other,icon));
            SwipeMini.finishHandler(other);
            assertNull(SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.finishHandler(handler);
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.acceptHandler(handler);
            SwipeMini.configure(null,true);
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
        }finally{SwipeMini.finishHandler(handler);}
    }

    @Test public void pauseDetectorIsNotRequiredToResolve()throws Exception{
        ClassLoader withoutPause=new ClassLoader(getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.contains("MotionPauseDetector")||name.contains("ActiveGestureLog"))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        assertNotNull(SwipeMini.resolve(withoutPause));
    }

    @Test public void disablingHintDoesNotDisarmTheGesture()throws Exception{
        var armed=SwipeMini.class.getDeclaredField("armed");armed.setAccessible(true);
        try{
            SwipeMini.configure(new android.os.Binder(),true);
            armed.setBoolean(null,true);
            SwipeMini.configure(new android.os.Binder(),false);
            assertTrue(armed.getBoolean(null));
            SwipeMini.configure(null,false);
            assertFalse(armed.getBoolean(null));
        }finally{SwipeMini.configure(null,true);}
    }

    @Test public void missingQuickstepStaysNative(){
        ClassLoader empty=new ClassLoader(getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("com.android.quickstep."))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        assertThrows(ClassNotFoundException.class,()->SwipeMini.resolve(empty));
    }
}
