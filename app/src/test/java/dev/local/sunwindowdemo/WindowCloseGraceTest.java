package dev.local.sunwindowdemo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.assertEquals;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=WindowCloseGraceTest.ActivityManagerShadow.class)
public class WindowCloseGraceTest {
    private static final String PKG="example.window";
    private static final int UID=10042;

    @Implements(ActivityManager.class)
    public static class ActivityManagerShadow extends ShadowActivityManager {
        final List<String> stopped=new ArrayList<>();
        @Implementation protected void forceStopPackageAsUser(String pkg,int user){stopped.add(pkg+":"+user);}
    }
    public static final class Root {
        public Object focused(){return null;}
        public void forAllTasks(Consumer<Object> callback){}
    }
    public static final class Activity {
        public final String packageName=PKG;
        public static Activity forTokenLocked(IBinder token){return new Activity();}
        public Task getTask(){return new Task();}
        public int getUid(){return UID;}
    }
    public static final class Task {
        public boolean isVisibleRequested(){return true;}
    }

    private WindowEngine engine;
    private List<String> stopped;
    private final Object lock=new Object();

    @Before public void setUp()throws Exception{
        var factory=SurfaceFrameTest.class.getDeclaredMethod("access");factory.setAccessible(true);
        var access=(FrameworkAccess)factory.invoke(null);
        ReflectionHelpers.setField(access,"focused",Root.class.getMethod("focused"));
        ReflectionHelpers.setField(access,"activity",Activity.class);
        engine=new WindowEngine(access,message->{});
        Context context=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setField(engine,"handler",new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(engine,"context",context);
        ReflectionHelpers.setField(engine,"root",new Root());
        ReflectionHelpers.setField(engine,"lock",lock);
        var info=new PackageInfo();info.packageName=PKG;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=PKG;info.applicationInfo.uid=UID;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        ActivityManagerShadow shadow=org.robolectric.shadow.api.Shadow.extract(context.getSystemService(ActivityManager.class));
        stopped=shadow.stopped;
        setEndOnClose(true);
    }

    @Test public void aNewCloseGetsItsOwnFullMinute(){
        close();
        idle(59_999);
        assertEquals(List.of(),stopped);
        close();
        idle(59_999);
        assertEquals(List.of(),stopped);
        idle(1);
        assertEquals(List.of(PKG+":0"),stopped);
    }

    @Test public void reopeningAndDisablingCancelPendingEnd(){
        close();
        idle(30_000);
        engine.appResumed(new android.os.Binder());
        idle(60_000);
        assertEquals(List.of(),stopped);
        close();
        setEndOnClose(false);
        setEndOnClose(true);
        idle(60_000);
        assertEquals(List.of(),stopped);
    }

    private void close(){
        var session=new WindowEngine.Session();session.endOnClose=true;session.pkg=PKG;session.uid=UID;
        synchronized(lock){ReflectionHelpers.callInstanceMethod(engine,"scheduleClosedApplications",from(List.class,List.of(session)));}
    }
    private void setEndOnClose(boolean value){
        ReflectionHelpers.callInstanceMethod(engine,"updateEndOnClose",from(boolean.class,value));
    }
    private static void idle(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
}
