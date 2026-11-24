package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE)
public class SettingsIconProviderTest {
    private final Context context=RuntimeEnvironment.getApplication();
    @After public void cleanup(){ShadowBinder.setCallingUid(android.os.Process.myUid());}

    @Test public void onlyActualSameUserSystemSettingsCanRead(){
        IconLayerProvider provider=provider();
        ShadowBinder.setCallingUid(23456);
        assertThrows(SecurityException.class,()->provider.call("read",null,null));
        installSettings(23456,0);
        assertThrows(SecurityException.class,()->provider.call("read",null,null));
        installSettings(23456,ApplicationInfo.FLAG_SYSTEM);
        assertNotNull(provider.call("read",null,null));
        installSettings(23456,ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        assertNotNull(provider.call("read",null,null));
        ShadowBinder.setCallingUid(23457);
        assertThrows(SecurityException.class,()->provider.call("read",null,null));
        installSettings(123456,ApplicationInfo.FLAG_SYSTEM);
        ShadowBinder.setCallingUid(123456);
        assertThrows(SecurityException.class,()->provider.call("read",null,null));
    }
    @Test public void settingsReadDoesNotGrantReportOrGenerationRights()throws Exception{
        installSettings(23456,ApplicationInfo.FLAG_SYSTEM);
        IconLayerProvider provider=provider();
        Bitmap pixels=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);pixels.eraseColor(Color.RED);
        String hash=IconOverrides.store(context,pixels);
        IconPolicy policy=new IconPolicy();policy.background=hash;
        IconPolicy.save(context,"com.example.artwork",policy);
        var uri=IconLayerStore.URI.buildUpon().path("custom/"+hash+".png").build();
        ShadowBinder.setCallingUid(23456);
        try(var descriptor=provider.openFile(uri,"r")){assertNotNull(descriptor);}
        assertThrows(SecurityException.class,()->provider.openFile(uri,"rw"));
        assertThrows(IllegalArgumentException.class,()->provider.call("report",null,new Bundle()));
        assertThrows(SecurityException.class,()->provider.call("started","com.example.artwork",null));
        ShadowBinder.setCallingUid(23457);
        assertThrows(SecurityException.class,()->provider.openFile(uri,"r"));
    }
    @Test public void refreshIncludesSettingsWithoutGrantingItsSignaturePermission(){
        IconLayerStore.refresh(context);
        assertTrue(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
            .anyMatch(i->IconLayerStore.REFRESH.equals(i.getAction())&&IconLayerStore.SETTINGS.equals(i.getPackage())));
    }
    private IconLayerProvider provider(){
        IconLayerProvider provider=new IconLayerProvider();
        ProviderInfo info=new ProviderInfo();info.authority=IconLayerStore.URI.getAuthority();
        provider.attachInfo(context,info);return provider;
    }
    private void installSettings(int uid,int flags){
        PackageInfo info=new PackageInfo();info.packageName=IconLayerStore.SETTINGS;
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=info.packageName;
        info.applicationInfo.uid=uid;info.applicationInfo.flags=flags;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
}
