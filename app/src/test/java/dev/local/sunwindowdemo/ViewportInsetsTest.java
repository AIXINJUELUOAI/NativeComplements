package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.WindowInsets;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36,manifest=Config.NONE)
public class ViewportInsetsTest {
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(access,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        return access;
    }
    @Test public void initialAndRelayoutOutStateUseSameLocalViewportWithoutMutatingNativeCache()throws Exception{
        FrameworkAccess a=access();WindowEngine engine=new WindowEngine(a,message->{});set(engine,"lock",new Object());
        Class<?> sessionType=Class.forName(WindowEngine.class.getName()+"$Session");
        Constructor<?> constructor=sessionType.getDeclaredConstructor();constructor.setAccessible(true);Object session=constructor.newInstance();
        set(session,"frame","mini");set(session,"placement",new Geometry.Placement(600,1000,.5f,100,200));
        Class<?> stateType=Class.forName("android.view.InsetsState");Object nativeState=stateType.getConstructor().newInstance();
        a.call(nativeState,"setDisplayFrame",new Class<?>[]{Rect.class},new Rect(0,0,1200,2670));
        Object bar=a.call(nativeState,"getOrCreateSource",new Class<?>[]{int.class,int.class},42,WindowInsets.Type.statusBars());
        a.call(bar,"setFrame",new Class<?>[]{Rect.class},new Rect(0,0,1200,126));
        a.call(bar,"setVisible",new Class<?>[]{boolean.class},true);
        Object initial=stateType.getConstructor(stateType,boolean.class).newInstance(nativeState,true);
        Object relayout=stateType.getConstructor(stateType,boolean.class).newInstance(nativeState,true);
        ServerViewport.mapInsets(a,initial,insets->engine.ownerInsets(session,insets));
        ServerViewport.mapInsets(a,relayout,insets->engine.ownerInsets(session,insets));
        assertEquals(initial,relayout);
        assertEquals(new Rect(0,0,600,1000),a.call(initial,"getDisplayFrame"));
        Object localBar=a.call(initial,"sourceAt",new Class<?>[]{int.class},0);
        assertEquals(new Rect(),a.call(localBar,"getFrame"));assertFalse(a.bool(localBar,"isVisible"));
        assertEquals(new Rect(0,0,1200,2670),a.call(nativeState,"getDisplayFrame"));
        assertEquals(new Rect(0,0,1200,126),a.call(bar,"getFrame"));assertTrue(a.bool(bar,"isVisible"));
    }
    @Test public void unrelatedAndNullOutStatesKeepNativeIdentity()throws Exception{
        FrameworkAccess a=access();WindowEngine engine=new WindowEngine(a,message->{});
        Object state=Class.forName("android.view.InsetsState").getConstructor().newInstance();
        ServerViewport.mapInsets(a,state,insets->engine.ownerInsets(null,insets));
        assertSame(state,engine.ownerInsets(null,state));
        ServerViewport.mapInsets(a,null,insets->{throw new AssertionError("null response must not map");});
    }
}
