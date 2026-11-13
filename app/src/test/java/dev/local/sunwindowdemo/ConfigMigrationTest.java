package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35)
public class ConfigMigrationTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();Config.hostPreferences=null;
        prefs=context.getSharedPreferences("corner",Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().commit());installTimes(100,100);
        ShadowBinder.setCallingUid(Process.myUid());
    }
    @After public void cleanup(){Config.hostPreferences=null;ShadowBinder.setCallingUid(Process.myUid());}
    private void installTimes(long first,long updated)throws Exception {
        PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        info.firstInstallTime=first;info.lastUpdateTime=updated;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    private void assertMigrated(boolean expected){
        assertEquals(expected,new Config(context).longBackWindow());
        assertTrue(prefs.contains("longBackWindow"));
        assertEquals(expected,prefs.getBoolean("longBackWindow",!expected));
    }

    @Test public void freshEmptyInstallDefaultsOffAndStaysOffAfterOtherSettingsAppear(){
        assertMigrated(false);
        assertEquals(Map.of("longBackWindow",false),prefs.getAll());
        assertTrue(prefs.edit().putBoolean("enabled",true).commit());
        assertMigrated(false);
    }
    @Test public void anyLegacyCornerPreferencePreservesPreviouslyEnabledGesture(){
        // An unrelated/false legacy setting still identifies an existing configuration.
        assertTrue(prefs.edit().putBoolean("enabled",false).commit());
        assertMigrated(true);assertFalse(prefs.getBoolean("enabled",true));
        assertMigrated(true);
    }
    @Test public void upgradedInstallWithEmptyPreferencesPreservesGesture()throws Exception {
        installTimes(100,200);assertTrue(prefs.getAll().isEmpty());assertMigrated(true);
    }
    @Test public void explicitFalseSurvivesLegacyPreferencesAndUpgrade()throws Exception {
        installTimes(100,200);
        assertTrue(prefs.edit().putBoolean("longBackWindow",false).putString("apps","com.example.old").commit());
        Map<String,?> before=prefs.getAll();assertMigrated(false);assertEquals(before,prefs.getAll());
    }
    @Test public void hostSnapshotIsReadOnlyAndMissingKeyDefaultsOff()throws Exception {
        installTimes(100,200);
        SharedPreferences host=context.getSharedPreferences("migration-host",0);
        assertTrue(host.edit().clear().putBoolean("enabled",true).commit());
        Map<String,?> before=host.getAll();
        Config.hostPreferences=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("edit"))throw new AssertionError("host snapshot must not be migrated");
                return method.invoke(host,args);
            });
        assertFalse(new Config(context).longBackWindow());assertEquals(before,host.getAll());
        assertFalse(host.contains("longBackWindow"));assertTrue(prefs.getAll().isEmpty());
        assertTrue(host.edit().putBoolean("longBackWindow",true).commit());
        assertTrue(new Config(context).longBackWindow());
        assertTrue(host.edit().putBoolean("longBackWindow",false).commit());
        assertFalse(new Config(context).longBackWindow());
    }
    @Test public void providerFirstReadMigratesAndCopiesCompleteSnapshotToHost()throws Exception {
        ConfigProvider provider=Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        SharedPreferences destination=context.getSharedPreferences("migration-snapshot",0);
        Context host=new ContextWrapper(context){@Override public String getPackageName(){return "com.android.systemui";}};
        // Each row starts before any Config construction: fresh, old preferences, empty upgrade, explicit off.
        for(int row=0;row<4;row++){
            Config.hostPreferences=null;assertTrue(prefs.edit().clear().commit());
            installTimes(100,row>=2?200:100);
            if(row==1)assertTrue(prefs.edit().putString("apps","com.example.old").commit());
            if(row==3)assertTrue(prefs.edit().putBoolean("longBackWindow",false).commit());
            boolean expected=row==1||row==2;
            assertTrue(destination.edit().putString("stale","remove me").commit());
            Bundle snapshot=provider.call("read",null,null);
            assertNotNull(snapshot);assertTrue(snapshot.containsKey("longBackWindow"));
            assertEquals(expected,snapshot.getBoolean("longBackWindow",!expected));
            assertEquals(expected,prefs.getBoolean("longBackWindow",!expected));
            ConfigProvider.copy(snapshot,destination);assertFalse(destination.contains("stale"));
            Map<String,?> before=destination.getAll();Config.hostPreferences=destination;
            assertEquals(expected,new Config(host).longBackWindow());assertEquals(before,destination.getAll());
        }
    }

    private static java.lang.reflect.Field field(Class<?> owner,String name)throws Exception{
        var field=owner.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private boolean backCommit(SystemUiHost host,String token)throws Exception{
        android.os.Parcel data=android.os.Parcel.obtain(),reply=android.os.Parcel.obtain();
        try{
            data.writeInterfaceToken("dev.nativecomplements.module.backLong.v1");
            data.writeInt(42);data.writeString("example.app");data.writeString(token);
            assertTrue(((android.os.Binder)field(SystemUiHost.class,"dropChannel").get(host))
                .transact(android.os.IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            reply.readException();return reply.readBoolean();
        }finally{data.recycle();reply.recycle();}
    }
    @Test public void disabledLongSwipeRejectsAdmissionAndCancelsAnAlreadyPreparedGesture()throws Exception{
        PackageInfo info=new PackageInfo();info.packageName="com.android.systemui";
        info.applicationInfo=new android.content.pm.ApplicationInfo();
        info.applicationInfo.packageName=info.packageName;info.applicationInfo.uid=Process.myUid();
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        var constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        SystemUiHost host=constructor.newInstance(context);
        Config config=new Config(context);
        prefs.edit().putBoolean("enabled",true).putBoolean("longBackWindow",false)
            .putBoolean("landscape",false).putBoolean("keyboard",false).commit();
        field(SystemUiHost.class,"config").set(host,config);
        field(SystemUiHost.class,"settingsReady").setBoolean(host,true);
        field(SystemUiHost.class,"backendReady").setBoolean(host,true);
        field(SystemUiHost.class,"backendState").set(host,"idle");
        Shadows.shadowOf(context.getSystemService(android.os.PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        var endpoint=field(Wire.class,"endpoint");Object previous=endpoint.get(null);
        java.util.List<String> cancelled=new java.util.ArrayList<>();
        endpoint.set(null,new android.os.Binder(){
            @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags){
                assertEquals(Wire.CANCEL_BACK,code);data.enforceInterface(Wire.CHANNEL);
                cancelled.add(data.readString());return true;
            }
        });
        try{
            assertFalse(backCommit(host,Wire.token()));
            prefs.edit().putBoolean("longBackWindow",true).commit();String token=Wire.token();
            assertTrue(backCommit(host,token));prefs.edit().putBoolean("longBackWindow",false).commit();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.List.of(token),cancelled);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)field(SystemUiHost.class,"backPending").get(host)).get());
        }finally{
            endpoint.set(null,previous);
            ((android.os.Handler)field(SystemUiHost.class,"main").get(host)).removeCallbacksAndMessages(null);
        }
    }
}
