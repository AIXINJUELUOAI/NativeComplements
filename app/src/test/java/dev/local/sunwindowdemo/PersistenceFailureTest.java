package dev.local.sunwindowdemo;

import android.app.Activity;
import android.app.Notification;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.WindowManager;
import dev.local.sunwindowdemo.features.barrage.BarrageProvider;
import dev.local.sunwindowdemo.features.barrage.BarrageStore;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public class PersistenceFailureTest {
    private static void invoke(Object target,String method)throws Exception {
        var call=target.getClass().getDeclaredMethod(method);call.setAccessible(true);call.invoke(target);
    }
    private static void drain(Object host)throws Exception {
        ExecutorService worker=ReflectionHelpers.getField(host,"worker");
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static Bundle entry(){
        Bundle entry=new Bundle();entry.putString("package","example.sender");entry.putString("text","message");return entry;
    }
    private static void register(String authority,ContentProvider provider,Context context){
        ProviderInfo info=new ProviderInfo();info.authority=authority;
        provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(authority,provider);
    }
    private abstract static class Provider extends ContentProvider {
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
        @Override public String getType(Uri u){return null;}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }

    @Test public void historyCommitResultReachesProviderSavedFlag(){
        Context app=RuntimeEnvironment.getApplication();
        SharedPreferences real=BarrageStore.prefs(app);real.edit().clear().commit();
        AtomicBoolean committed=new AtomicBoolean(false);
        AtomicInteger commits=new AtomicInteger();
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(!method.getName().equals("edit"))return method.invoke(real,args);
            SharedPreferences.Editor editor=real.edit();
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{
                if(m.getName().equals("commit")){commits.incrementAndGet();return committed.get()&&editor.commit();}
                Object result=m.invoke(editor,a);return result==editor?p:result;
            });
        });
        Context storage=new ContextWrapper(app){
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        };
        PackageInfo systemUi=new PackageInfo();systemUi.packageName="com.android.systemui";
        systemUi.applicationInfo=new ApplicationInfo();systemUi.applicationInfo.uid=1000;
        Shadows.shadowOf(app.getPackageManager()).installPackage(systemUi);
        BarrageProvider provider=new BarrageProvider();register(BarrageStore.AUTHORITY,provider,storage);
        try{
            ShadowBinder.setCallingUid(1000);
            assertFalse(BarrageStore.addHistory(storage,entry()));
            assertFalse(provider.call("addHistory",null,entry()).getBoolean("saved"));
            assertTrue(BarrageStore.readHistory(storage).isEmpty());
            committed.set(true);
            assertTrue(provider.call("addHistory",null,entry()).getBoolean("saved"));
            assertEquals(1,BarrageStore.readHistory(storage).size());
            assertEquals(3,commits.get());
        }finally{ShadowBinder.reset();}
    }

    public static class Listener extends NotificationListenerService {}

    @Test public void barrageKeepsOriginalNotificationWhenHistoryIsNotSaved()throws Exception {
        try(var activity=Robolectric.buildActivity(Activity.class).setup()){
            Context app=RuntimeEnvironment.getApplication();
            AtomicBoolean saved=new AtomicBoolean(false);AtomicInteger writes=new AtomicInteger();
            Bundle config=new Bundle();config.putBoolean("enabled",true);
            config.putStringArrayList("apps",new java.util.ArrayList<>(java.util.List.of("example.foreground")));
            register(BarrageStore.AUTHORITY,new Provider(){
                @Override public Bundle call(String method,String arg,Bundle extras){
                    if(method.equals("read"))return config;
                    assertEquals("addHistory",method);writes.incrementAndGet();
                    Bundle result=new Bundle();result.putBoolean("saved",saved.get());return result;
                }
            },app);
            WindowManager real=activity.get().getSystemService(WindowManager.class);
            WindowManager windows=(WindowManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WindowManager.class},(p,m,a)->{
                if(m.getName().equals("addView")||m.getName().equals("removeViewImmediate"))return null;
                return m.invoke(real,a);
            });
            Context context=new ContextWrapper(app){
                @Override public Object getSystemService(String name){return WINDOW_SERVICE.equals(name)?windows:super.getSystemService(name);}
            };
            BarrageHost host=new BarrageHost(context);
            try{
                drain(host);host.environment("example.foreground",false);host.environment("example.foreground",false);
                NotificationListenerService listener=Robolectric.buildService(Listener.class).create().get();
                Notification notification=new Notification();notification.extras.putString(Notification.EXTRA_TEXT,"message");
                StatusBarNotification sbn=new StatusBarNotification("example.sender","example.sender",1,null,12345,0,0,notification,android.os.Process.myUserHandle(),System.currentTimeMillis());
                Shadows.shadowOf(listener).addActiveNotification(sbn);
                host.notification(listener,sbn);drain(host);
                assertEquals(1,writes.get());assertNotNull(ReflectionHelpers.getField(host,"view"));
                assertEquals(1,listener.getActiveNotifications().length);assertNull(ReflectionHelpers.getField(host,"ignoredRemovalKey"));
                saved.set(true);host.notification(listener,sbn);drain(host);
                assertEquals(2,writes.get());assertEquals(0,listener.getActiveNotifications().length);
                assertEquals(sbn.getKey(),ReflectionHelpers.getField(host,"ignoredRemovalKey"));
            }finally{host.close();}
        }
    }

    @Test public void settingsReadFailureReleasesAwakeAndClearsOffer()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        for(boolean throwsException:new boolean[]{false,true}){
            register(SuggestionStore.AUTHORITY,new Provider(){
                @Override public Bundle call(String method,String arg,Bundle extras){
                    if(throwsException)throw new IllegalStateException("settings unavailable");
                    return null;
                }
            },context);
            SuggestionHost host=new SuggestionHost(context);
            // Isolate settings reload from unrelated display/camera initialization.
            Handler main=ReflectionHelpers.getField(host,"main");main.removeCallbacksAndMessages(null);
            PowerManager.WakeLock lock=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"test:awake");
            lock.acquire();ReflectionHelpers.setField(host,"awakeLock",lock);
            ReflectionHelpers.setField(host,"awakeUntil",SystemClock.elapsedRealtime()+60000);
            Bundle settings=new Bundle();settings.putBoolean("enabled",true);settings.putBoolean("awake",true);
            ReflectionHelpers.setField(host,"settings",settings);
            try{
                invoke(host,"loadSettings");drain(host);
                assertFalse(lock.isHeld());assertEquals(0L,(long)ReflectionHelpers.getField(host,"awakeUntil"));
                assertFalse(((Bundle)ReflectionHelpers.getField(host,"settings")).getBoolean("enabled"));
            }finally{host.close();}
        }
    }
}
