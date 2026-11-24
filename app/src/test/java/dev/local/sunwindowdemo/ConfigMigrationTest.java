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
        assertEquals(Map.of("longBackWindow",false,"enabled",false),prefs.getAll());
        assertFalse(new Config(context).enabled());
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
        assertMigrated(false);assertTrue(new Config(context).enabled());
        assertEquals(Map.of("longBackWindow",false,"apps","com.example.old","enabled",true),prefs.getAll());
    }
    @Test public void enabledMigrationPreservesExplicitValuesAndLegacyEvidence()throws Exception{
        for(boolean enabled:new boolean[]{false,true}){
            assertTrue(prefs.edit().clear().putBoolean("enabled",enabled).commit());
            assertEquals(enabled,new Config(context).enabled());assertEquals(enabled,prefs.getBoolean("enabled",!enabled));
        }
        assertTrue(prefs.edit().clear().putBoolean("keyboard",false).commit());assertTrue(new Config(context).enabled());
        assertTrue(prefs.edit().clear().commit());installTimes(100,200);assertTrue(new Config(context).enabled());
        assertTrue(prefs.edit().clear().commit());installTimes(0,0);assertFalse(new Config(context).enabled());
        assertTrue(prefs.edit().clear().commit());installTimes(200,100);assertFalse(new Config(context).enabled());
    }
    @Test public void foreignHostWithoutSnapshotDoesNotMigrateManagerSettings(){
        Context host=new ContextWrapper(context){@Override public String getPackageName(){return "com.android.systemui";}};
        new Config(host);assertTrue(prefs.getAll().isEmpty());
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

}
