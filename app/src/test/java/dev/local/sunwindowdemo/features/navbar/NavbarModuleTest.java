package dev.local.sunwindowdemo.features.navbar;

import android.content.pm.ApplicationInfo;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarModuleTest {
    private final List<String> events = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    private NavbarModule module() {
        NavbarModule module = new NavbarModule();
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!"log".equals(method.getName())) throw new AssertionError("Unexpected framework call: " + method);
                    assertEquals("NativeNavbar", args[1]);
                    events.add((String) args[2]);
                    if (args.length == 4) failures.add((Throwable) args[3]);
                    return null;
                }));
        return module;
    }

    private XposedModuleInterface.ModuleLoadedParam process(String name, boolean server) {
        return new XposedModuleInterface.ModuleLoadedParam() {
            public boolean isSystemServer() { return server; }
            public String getProcessName() { return name; }
        };
    }

    private XposedModuleInterface.PackageLoadedParam target() {
        return new XposedModuleInterface.PackageLoadedParam() {
            public String getPackageName() { return "com.android.systemui"; }
            public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }
            public boolean isFirstPackage() { return true; }
            public ClassLoader getDefaultClassLoader() {
                return new ClassLoader(null) {
                    @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                        throw new ClassNotFoundException("test missing host: " + name);
                    }
                };
            }
        };
    }

    @Test public void systemUiStartupAndPreflightFailureReachFrameworkLogOnce() {
        NavbarModule module = module();
        module.onModuleLoaded(process("com.android.systemui", false));
        module.onPackageLoaded(target());
        module.onPackageLoaded(target());
        assertEquals(3, events.size());
        assertTrue(events.get(0).startsWith("IMMERSIVE_MODULE_LOADED version="));
        assertEquals("IMMERSIVE_PACKAGE_LOADED first=true", events.get(1));
        assertEquals("IMMERSIVE_INSTALL_FAILED rolledBack=0", events.get(2));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof ClassNotFoundException);
    }

    @Test public void ordinaryProcessesAndSystemServerDoNotInstallNavigationHooks() {
        for (XposedModuleInterface.ModuleLoadedParam param : new XposedModuleInterface.ModuleLoadedParam[]{
                process("app.example", false), process("com.android.systemui", true), process("system", true)}) {
            NavbarModule module = module();
            module.onModuleLoaded(param);
            module.onPackageLoaded(target());
        }
        assertTrue(events.isEmpty());
    }

    @Test public void loggerKeepsOriginalFailureWithoutDependingOnLogcat() {
        NavbarModule module = module();
        IllegalStateException error = new IllegalStateException("deopt refused");
        NavbarHost.log(module, "IMMERSIVE_CONFIG_UNAVAILABLE", error);
        assertEquals(List.of("IMMERSIVE_CONFIG_UNAVAILABLE"), events);
        assertSame(error, failures.get(0));
    }
}
