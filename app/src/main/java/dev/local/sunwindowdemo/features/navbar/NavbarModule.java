package dev.local.sunwindowdemo.features.navbar;

import io.github.libxposed.api.*;

/** Only the two verified native navigation owners; never ordinary applications or system_server. */
public final class NavbarModule extends XposedModule {
    private boolean systemUi, launcher, installed;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemUi = !param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
        launcher = !param.isSystemServer()
                && dev.local.sunwindowdemo.core.LauncherTargets.isLauncherProcess(param.getProcessName());
        if (systemUi || launcher) NavbarHost.log(this, "IMMERSIVE_MODULE_LOADED version="
                + dev.local.sunwindowdemo.BuildConfig.VERSION_NAME, null);
    }

    @Override public synchronized void onPackageLoaded(PackageLoadedParam param) {
        boolean target = systemUi && "com.android.systemui".equals(param.getPackageName())
                || launcher && dev.local.sunwindowdemo.core.LauncherTargets.isLauncher(param.getPackageName());
        if (!target || installed) return;
        installed = true;
        NavbarHost.log(this, "IMMERSIVE_PACKAGE_LOADED first=" + param.isFirstPackage(), null);
        if (launcher) NavbarTaskbarHost.install(this, param.getDefaultClassLoader());
        else {
            NavbarHost.install(this, param.getDefaultClassLoader());
            dev.local.sunwindowdemo.features.darkaod.DarkAodHost.install(this, param.getDefaultClassLoader());
        }
    }
}
