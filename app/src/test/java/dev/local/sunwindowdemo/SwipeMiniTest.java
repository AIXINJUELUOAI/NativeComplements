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
    }

    @Test public void thresholdIsTwoFifthsOfDisplayHeight(){
        assertEquals(0.4f,SwipeMini.THRESHOLD,0f);
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
