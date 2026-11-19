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

    @Test public void onlyMatchingCardIsHiddenAndOriginalTransitionAlphaIsRestored()throws Exception{
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        var recents=new com.android.quickstep.views.RecentsView(context);
        recents.taskId=42;recents.card=new com.android.quickstep.views.TaskView(context);
        var targets=SwipeMini.resolve(getClass().getClassLoader());
        Object handler=new Object();
        targets.setTransitionAlpha().invoke(recents.card,.7f);
        try{
            SwipeMini.hideCard(targets,recents,handler,99);
            assertEquals(.7f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            SwipeMini.hideCard(targets,recents,handler,42);
            assertEquals(0f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            recents.card.setAlpha(.5f); // Native card alpha must not unhide our separate channel.
            assertEquals(0f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            SwipeMini.restoreCard(new Object());
            assertEquals(0f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            SwipeMini.restoreCard(handler);
            assertEquals(.7f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            assertEquals(.5f,recents.card.getAlpha(),0);
            SwipeMini.hideCard(targets,recents,handler,42);
            SwipeMini.configure(null,true);
            assertEquals(.7f,(Float)targets.transitionAlpha().invoke(recents.card),0);
        }finally{SwipeMini.restoreCard(null);}
    }

    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void detachedCardAndLostCompletionBothRestore()throws Exception{
        var activity=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        var recents=new com.android.quickstep.views.RecentsView(activity);
        recents.taskId=42;recents.card=new com.android.quickstep.views.TaskView(activity);
        recents.addView(recents.card);activity.setContentView(recents);
        var targets=SwipeMini.resolve(getClass().getClassLoader());
        try{
            targets.setTransitionAlpha().invoke(recents.card,.8f);
            SwipeMini.hideCard(targets,recents,new Object(),42);
            recents.removeView(recents.card);
            assertEquals(.8f,(Float)targets.transitionAlpha().invoke(recents.card),0);
            recents.addView(recents.card);
            SwipeMini.hideCard(targets,recents,new Object(),42);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2001));
            assertEquals(.8f,(Float)targets.transitionAlpha().invoke(recents.card),0);
        }finally{SwipeMini.restoreCard(null);activity.finish();}
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
