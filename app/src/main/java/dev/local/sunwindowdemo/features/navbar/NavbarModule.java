package dev.local.sunwindowdemo.features.navbar;

import io.github.libxposed.api.*;

/** Navigation insets belong to SystemUI; no application or system_server hooks. */
public final class NavbarModule extends XposedModule {
    private boolean systemUi, installed;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemUi = !param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
    }

    @Override public synchronized void onPackageLoaded(PackageLoadedParam param) {
        if (!systemUi || installed || !"com.android.systemui".equals(param.getPackageName())) return;
        installed = true;
        NavbarHost.install(this, param.getDefaultClassLoader());
    }
}
