package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.WindowManager;
import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class CoverViewportTest {
    public static class Display {
        public int getRotation(){return 0;}
        public Rect getBounds(){return new Rect(0,0,1200,2670);}
    }
    public static class Task {
        Rect bounds=new Rect(0,0,1200,2133);
        public int getWindowingMode(){return 5;}
        public Rect getBounds(){return bounds;}
        public Display getDisplayContent(){return new Display();}
    }
    public static class Report {
        Configuration config;
        public Configuration getMergedConfiguration(){return config;}
    }
    public static class Window {
        public WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
        public Report mLastReportedConfiguration=new Report();
        Configuration config;
        public Configuration getConfiguration(){return config;}
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new ConcurrentHashMap<>());set(a,"fields",new ConcurrentHashMap<>());return a;
    }
    private static Configuration configuration(FrameworkAccess a,Rect bounds,int mode)throws Exception{
        Configuration c=new Configuration();c.densityDpi=489;c.screenWidthDp=393;c.screenHeightDp=650;c.orientation=1;
        Object wc=a.field(c,"windowConfiguration");
        a.call(wc,"setBounds",new Class<?>[]{Rect.class},bounds);
        a.call(wc,"setAppBounds",new Class<?>[]{Rect.class},new Rect(bounds));
        a.call(wc,"setWindowingMode",new Class<?>[]{int.class},mode);
        a.call(wc,"setRotation",new Class<?>[]{int.class},0);return c;
    }
    private static boolean matches(FrameworkAccess a,Task task,Window window)throws Exception{
        WindowEngine engine=new WindowEngine(a,message->{});
        Class<?> type=Class.forName(WindowEngine.class.getName()+"$Session");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object session=constructor.newInstance();
        set(session,"task",task);set(session,"placement",new Geometry.Placement(1200,2133,.75f,150,568));
        Method method=WindowEngine.class.getDeclaredMethod("coverConfigurationMatches",type,Object.class);method.setAccessible(true);
        return (Boolean)method.invoke(engine,session,window);
    }
    @Test public void nativeInsetViewportCanRevealWithoutChangingTheTaskOrConfiguration()throws Exception{
        FrameworkAccess a=access();Task task=new Task();Window window=new Window();
        window.config=configuration(a,new Rect(0,147,1200,2133),5);
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        assertTrue(matches(a,task,window));
        assertEquals(new Rect(0,0,1200,2133),task.bounds);
        assertEquals(new Rect(0,147,1200,2133),a.call(a.field(window.config,"windowConfiguration"),"getBounds"));
        window.config=configuration(a,new Rect(0,0,1200,2133),5);
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        assertTrue(matches(a,task,window));
    }
    @Test public void staleViewportWrongDensityRotationFacadeAndStartingSnapshotsStayRejected()throws Exception{
        FrameworkAccess a=access();Task task=new Task();Window window=new Window();
        window.config=configuration(a,new Rect(0,147,1200,2133),5);
        window.mLastReportedConfiguration.config=configuration(a,new Rect(0,0,1200,2133),5);
        assertFalse(matches(a,task,window));
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        window.mLastReportedConfiguration.config.densityDpi=480;assertFalse(matches(a,task,window));
        window.mLastReportedConfiguration.config=configuration(a,new Rect(0,147,1200,2133),1);
        assertFalse(matches(a,task,window));
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        a.call(a.field(window.mLastReportedConfiguration.config,"windowConfiguration"),"setRotation",new Class<?>[]{int.class},1);
        assertFalse(matches(a,task,window));
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        window.mAttrs.type=WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;assertFalse(matches(a,task,window));
    }
    @Test public void wrongTaskCanvasAndOutOfCanvasResolvedGeometryCannotReveal()throws Exception{
        FrameworkAccess a=access();Task task=new Task();Window window=new Window();
        window.config=configuration(a,new Rect(0,147,1200,2133),5);
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        task.bounds=new Rect(0,0,1200,2670);assertFalse(matches(a,task,window));
        task.bounds=new Rect(0,0,1200,2133);
        window.config=configuration(a,new Rect(0,147,1200,2670),5);
        window.mLastReportedConfiguration.config=new Configuration(window.config);
        assertFalse(matches(a,task,window));
    }
}
