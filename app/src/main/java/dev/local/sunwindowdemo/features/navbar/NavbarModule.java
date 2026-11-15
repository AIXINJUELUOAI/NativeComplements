package dev.local.sunwindowdemo.features.navbar;

import io.github.libxposed.api.*;

/** Navigation insets belong to SystemUI; no application or system_server hooks. */
public final class NavbarModule extends XposedModule {
    private boolean systemUi, installed;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemUi = !param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
        if (systemUi) NavbarHost.log(this, "IMMERSIVE_MODULE_LOADED version="
                + dev.local.sunwindowdemo.BuildConfig.VERSION_NAME, null);
    }

    @Override public synchronized void onPackageLoaded(PackageLoadedParam param) {
        if (!systemUi || installed || !"com.android.systemui".equals(param.getPackageName())) return;
        installed = true;
        NavbarHost.log(this, "IMMERSIVE_PACKAGE_LOADED first=" + param.isFirstPackage(), null);
        NavbarHost.install(this, param.getDefaultClassLoader());
    }
}
