package dev.local.sunwindowdemo.features.edgetouch;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.*;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class EdgeInputFilterTest {
    public static class Manager {
        public Context mContext=RuntimeEnvironment.getApplication();
        public Object mInputFilterLock=new Object(),mInputFilter;
        public Native mNative=new Native(mInputFilterLock);
    }
    public static class Native {
        private final Object lock;
        boolean enabled;
        Native(Object lock){this.lock=lock;}
        public void setInputFilterEnabled(boolean enabled){assertTrue(Thread.holdsLock(lock));this.enabled=enabled;}
        public int injectInputEvent(InputEvent event,boolean targeted,int uid,int mode,int timeout,int flags){return 0;}
    }
    private EdgeInputFilter host(Manager manager)throws Exception{
        Constructor<?> constructor=EdgeInputFilter.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
        return (EdgeInputFilter)constructor.newInstance(manager,Manager.class.getField("mContext"),Manager.class.getField("mNative"),
            Manager.class.getField("mInputFilterLock"),Manager.class.getField("mInputFilter"),
            Native.class.getMethod("setInputFilterEnabled",boolean.class),
            Native.class.getMethod("injectInputEvent",InputEvent.class,boolean.class,int.class,int.class,int.class,int.class),
            (Consumer<String>)ignored->{});
    }
    private void sync(EdgeInputFilter host)throws Exception{
        Method sync=EdgeInputFilter.class.getDeclaredMethod("sync");sync.setAccessible(true);sync.invoke(host);
    }
    private MotionEvent finger(int action){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=5;coords.y=100;coords.pressure=1;
        return MotionEvent.obtain(1,2,action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coords},
            0,0,1,1,7,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    }
    @Test public void disablingModuleRetainsAnExistingAccessibilityFilter()throws Exception{
        Manager manager=new Manager();EdgeInputFilter host=host(manager);
        ReflectionHelpers.setField(host,"regions",new int[]{0,0,20,1000});sync(host);assertTrue(manager.mNative.enabled);
        manager.mInputFilter=new Object();ReflectionHelpers.setField(host,"regions",new int[0]);sync(host);
        assertTrue(manager.mNative.enabled);assertFalse(ReflectionHelpers.getField(host,"active"));
        manager.mInputFilter=null;sync(host);assertFalse(manager.mNative.enabled);
    }
    @Test public void expiryReleasesRegionsWhileFinishingTheSuppressedPointerStream()throws Exception{
        Manager manager=new Manager();EdgeInputFilter host=host(manager);
        EdgeInputPolicy policy=ReflectionHelpers.getField(host,"policy");
        int[] rectangles={0,0,20,1000};policy.regions(rectangles);ReflectionHelpers.setField(host,"regions",rectangles);
        MotionEvent event=finger(MotionEvent.ACTION_DOWN);
        assertEquals(0,policy.accepted(event,0));event.recycle();sync(host);
        ReflectionHelpers.setField(host,"seen",SystemClock.uptimeMillis()-3000);
        ReflectionHelpers.<Runnable>getField(host,"expire").run();
        assertEquals(0,ReflectionHelpers.<int[]>getField(host,"regions").length);
        assertTrue(manager.mNative.enabled);
        event=finger(MotionEvent.ACTION_UP);
        assertEquals(0,policy.accepted(event,0));event.recycle();sync(host);
        assertFalse(manager.mNative.enabled);
    }
    @Test public void failedBackendStaysDisabledAndLeavesNativeAccessibilityAvailable()throws Exception{
        Manager manager=new Manager();EdgeInputFilter host=host(manager);manager.mInputFilter=new Object();
        Method fail=EdgeInputFilter.class.getDeclaredMethod("disableAfterFailure",String.class);fail.setAccessible(true);fail.invoke(host,"test");
        ReflectionHelpers.setField(host,"regions",new int[]{0,0,20,1000});sync(host);
        assertTrue(manager.mNative.enabled);assertFalse(ReflectionHelpers.getField(host,"active"));
        manager.mInputFilter=null;sync(host);assertFalse(manager.mNative.enabled);
    }
}
