package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WindowIconProviderTest {
    private Context context;
    private WindowIconProvider provider;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();IconLayerStore.prefs(context).edit().clear().commit();
        dev.local.sunwindowdemo.features.shade.ShadeStore.preferences(context).edit().clear().commit();
        provider=new WindowIconProvider();ProviderInfo info=new ProviderInfo();info.authority=IconLayerStore.URI.getAuthority();
        provider.attachInfo(context,info);ShadowBinder.setCallingUid(android.os.Process.myUid());
        install(IconLayerStore.LAUNCHER,10042);install(IconLayerStore.SYSTEM_UI,10043);
    }
    @After public void cleanup(){ShadowBinder.setCallingUid(android.os.Process.myUid());}
    private void install(String pkg,int uid){
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    @Test public void readIgnoresImportedPoliciesAndRetainsNativeMonetProjection(){
        IconLayerStore.prefs(context).edit().putBoolean("enabled",true).putString("policies","not json")
            .putString("generation","legacy-library").putBoolean("system_themed",true).commit();
        dev.local.sunwindowdemo.features.shade.ShadeStore.preferences(context).edit()
            .putBoolean("cover_monet",true).putBoolean("window_monet",true).commit();
        Bundle result=provider.call("read",null,null);
        assertFalse(result.getBoolean("enabled"));assertFalse(result.getBoolean("notifications"));
        assertEquals("{}",result.getString("policies"));assertEquals("",result.getString("generation"));
        assertNotNull(result.getStringArrayList("targets"));assertTrue(result.getStringArrayList("packages").isEmpty());
        assertTrue(result.getBoolean("systemThemed"));assertTrue(result.getBoolean("coverMonet"));assertTrue(result.getBoolean("windowMonet"));
        assertTrue(IconAssets.load(context,result,()->false));assertTrue(IconAssets.ready());
        assertTrue(IconAssets.snapshot().entries().isEmpty());assertTrue(IconAssets.snapshot().algorithms().isEmpty());
    }
    @Test public void onlyLauncherMayReportExactlyOneBoolean(){
        Bundle report=new Bundle();report.putBoolean("systemThemed",true);
        assertThrows(SecurityException.class,()->provider.call("report",null,report));
        ShadowBinder.setCallingUid(10042);provider.call("report",null,report);assertTrue(IconLayerStore.systemThemed(context));
        report.putString("status","fake");assertThrows(IllegalArgumentException.class,()->provider.call("report",null,report));
        report.clear();report.putString("systemThemed","true");assertThrows(IllegalArgumentException.class,()->provider.call("report",null,report));
        assertThrows(IllegalArgumentException.class,()->provider.call("report",null,null));
    }
    @Test public void readerAllowlistAndNoAssetsOrGeneration(){
        for(int uid:new int[]{android.os.Process.myUid(),10042,10043,1000}){
            ShadowBinder.setCallingUid(uid);assertNotNull(provider.call("read",null,null));
            assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(IconLayerStore.URI,"r"));
        }
        ShadowBinder.setCallingUid(10042);assertThrows(SecurityException.class,()->provider.call("started","com.example.any",null));
        ShadowBinder.setCallingUid(1000);assertSame(Bundle.EMPTY,provider.call("started","com.example.any",null));
        ShadowBinder.setCallingUid(12345);assertThrows(SecurityException.class,()->provider.call("read",null,null));
        assertThrows(SecurityException.class,()->provider.openFile(IconLayerStore.URI,"r"));
        install(IconLayerStore.LAUNCHER,110042);ShadowBinder.setCallingUid(110042);
        assertThrows(SecurityException.class,()->provider.call("read",null,null));
    }
}
