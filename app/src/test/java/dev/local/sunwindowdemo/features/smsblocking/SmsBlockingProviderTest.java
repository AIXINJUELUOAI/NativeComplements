package dev.local.sunwindowdemo.features.smsblocking;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
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
@Config(sdk = 35, manifest = Config.NONE)
public class SmsBlockingProviderTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @After public void cleanup() { ShadowBinder.setCallingUid(android.os.Process.myUid()); }

    @Test public void missingPhoneDoesNotAuthorizeLegacyPhoneUid() {
        ShadowBinder.setCallingUid(1001);
        assertThrows(SecurityException.class, () -> provider().call("read", null, null));
    }

    @Test public void phoneMustBeActualSystemPackageInSameUser() {
        SmsBlockingProvider provider = provider();
        installPhone(23456, 0);
        ShadowBinder.setCallingUid(23456);
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
        installPhone(23456, ApplicationInfo.FLAG_SYSTEM);
        assertNotNull(provider.call("read", null, null));
        ShadowBinder.setCallingUid(1001);
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
        installPhone(123456, ApplicationInfo.FLAG_SYSTEM);
        ShadowBinder.setCallingUid(123456);
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
    }

    private SmsBlockingProvider provider() {
        SmsBlockingProvider provider = new SmsBlockingProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = SmsBlockingProvider.AUTHORITY;
        provider.attachInfo(context, info);
        return provider;
    }

    private void installPhone(int uid, int flags) {
        PackageInfo pkg = new PackageInfo(); pkg.packageName = "com.android.phone";
        pkg.applicationInfo = new ApplicationInfo(); pkg.applicationInfo.packageName = pkg.packageName;
        pkg.applicationInfo.uid = uid; pkg.applicationInfo.flags = flags;
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
    }
}
