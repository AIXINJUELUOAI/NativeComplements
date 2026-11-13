package dev.local.sunwindowdemo.features.navbar;

import android.content.pm.ProviderInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarProviderTest {
    private NavbarProvider provider() {
        NavbarProvider provider = new NavbarProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = NavbarStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        return provider;
    }
    @Test(expected = SecurityException.class) public void ordinaryUidCannotReadSystemSnapshot() {
        ShadowBinder.setCallingUid(99999);
        provider().call("system", null, null);
    }
    @Test public void systemUidCanReadTemplateSnapshot() {
        ShadowBinder.setCallingUid(1000);
        assertTrue(provider().call("system", null, null).getString("templates").contains("HyperNavBar Rules"));
    }
}
