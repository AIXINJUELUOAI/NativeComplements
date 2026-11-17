package dev.local.sunwindowdemo.features.smsblocking;

import android.content.pm.ApplicationInfo;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class SmsBlockingModuleTest {
    @Test public void onlyExactPhoneMainProcessIsEligible() throws Exception {
        for (String name : new String[]{null, "com.android.phone:remote", "com.android.phone.fake", "system"}) {
            SmsBlockingModule module = new SmsBlockingModule();
            module.onModuleLoaded(process(name, false));
            assertFalse(flag(module, "eligible"));
        }
        SmsBlockingModule module = new SmsBlockingModule();
        module.onModuleLoaded(process("com.android.phone", true));
        assertFalse(flag(module, "eligible"));
        module.onModuleLoaded(process("com.android.phone", false));
        assertTrue(flag(module, "eligible"));
        module.onPackageLoaded(new XposedModuleInterface.PackageLoadedParam() {
            public String getPackageName() { return "other.package"; }
            public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }
            public boolean isFirstPackage() { return false; }
            public ClassLoader getDefaultClassLoader() { throw new AssertionError("Foreign package must not install"); }
        });
        assertFalse(flag(module, "attempted"));
    }

    private boolean flag(SmsBlockingModule module, String name) throws Exception {
        Field field = SmsBlockingModule.class.getDeclaredField(name); field.setAccessible(true);
        return field.getBoolean(module);
    }
    private XposedModuleInterface.ModuleLoadedParam process(String name, boolean server) {
        return new XposedModuleInterface.ModuleLoadedParam() {
            public boolean isSystemServer() { return server; }
            public String getProcessName() { return name; }
        };
    }
}
