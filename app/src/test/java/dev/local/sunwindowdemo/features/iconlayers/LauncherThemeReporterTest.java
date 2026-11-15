package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class LauncherThemeReporterTest {
    public static class State { public Object themeController; }
    public static class Manager { public State iconState=new State(); }
    public static class Singleton {
        public Manager manager=new Manager();
        public Object get(Context context){return manager;}
    }
    public static class Reports extends ContentProvider {
        final List<Bundle> reports=new ArrayList<>();boolean reject;
        @Override public boolean onCreate(){return true;}
        @Override public Bundle call(String method,String arg,Bundle extras){
            assertEquals("report",method);if(reject)throw new SecurityException("temporarily unavailable");
            reports.add(new Bundle(extras));return Bundle.EMPTY;
        }
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
        @Override public String getType(Uri u){return null;}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }
    private LauncherThemeReporter reporter;
    private Singleton singleton;
    private final List<Boolean> changes=new ArrayList<>();
    private static void set(Object object,String name,Object value)throws Exception{
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private Object call(String name,Class<?>[] types,Object... args)throws Exception{
        Method method=LauncherThemeReporter.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(reporter,args);
    }
    @Before public void setup()throws Exception{
        reporter=new LauncherThemeReporter(changes::add);singleton=new Singleton();
        set(reporter,"singleton",singleton);set(reporter,"get",Singleton.class.getMethod("get",Context.class));
        set(reporter,"state",Manager.class.getField("iconState"));set(reporter,"controller",State.class.getField("themeController"));
    }
    @After public void cleanup(){reporter.close();}
    @Test public void exactThemeControllerStateAndUnknownFailClosed()throws Exception{
        set(reporter,"context",RuntimeEnvironment.getApplication());
        assertEquals(false,call("read",new Class<?>[]{Object.class},(Object)null));
        singleton.manager.iconState.themeController=new Object();
        assertEquals(true,call("read",new Class<?>[]{Object.class},(Object)null));
        Manager constructing=new Manager();
        assertEquals(false,call("read",new Class<?>[]{Object.class},constructing));
        singleton.manager=null;assertEquals(false,call("read",new Class<?>[]{Object.class},(Object)null));
    }
    @Test public void reportsOnlyBooleanDeduplicatesAndRetriesAfterFailure()throws Exception{
        Context context=RuntimeEnvironment.getApplication();set(reporter,"context",context);
        Reports provider=new Reports();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();
        info.authority=IconLayerStore.URI.getAuthority();provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(IconLayerStore.URI.getAuthority(),provider);
        call("report",new Class<?>[]{boolean.class},true);call("report",new Class<?>[]{boolean.class},true);
        assertEquals(1,provider.reports.size());assertEquals(1,provider.reports.get(0).size());assertTrue(provider.reports.get(0).getBoolean("systemThemed"));
        provider.reject=true;call("report",new Class<?>[]{boolean.class},false);assertEquals(1,provider.reports.size());
        provider.reject=false;call("report",new Class<?>[]{boolean.class},false);assertEquals(2,provider.reports.size());
        assertFalse(provider.reports.get(1).getBoolean("systemThemed"));
    }
    @Test public void themeObservationDeliversDrawerCallbackOnMainQueue()throws Exception{
        set(reporter,"context",RuntimeEnvironment.getApplication());set(reporter,"worker",new Handler(Looper.getMainLooper()));
        Manager manager=new Manager();manager.iconState.themeController=new Object();
        call("refresh",new Class<?>[]{Object.class},manager);assertTrue(changes.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(List.of(true),changes);
    }
    @Test public void initialConnectIsIdempotentAndCloseCancelsQueuedCallback()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        Context early=new ContextWrapper(app){@Override public Context getApplicationContext(){return null;}};
        reporter.connect(early);
        Field context=LauncherThemeReporter.class.getDeclaredField("context");context.setAccessible(true);assertSame(early,context.get(reporter));
        Field worker=LauncherThemeReporter.class.getDeclaredField("worker");worker.setAccessible(true);Object initialWorker=worker.get(reporter);
        reporter.connect(app);assertSame(initialWorker,worker.get(reporter));
        reporter.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(changes.isEmpty());assertNull(worker.get(reporter));
    }
}
