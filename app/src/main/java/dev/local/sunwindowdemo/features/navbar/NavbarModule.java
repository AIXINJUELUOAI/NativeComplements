package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.app.Instrumentation;
import io.github.libxposed.api.*;

/** Navigation adaptation runs exclusively in the existing system hosts. */
public final class NavbarModule extends XposedModule {
    private boolean systemServer, systemUi, launcher, installed;
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemServer = param.isSystemServer();
        systemUi = "com.android.systemui".equals(param.getProcessName());
        launcher = "com.google.android.apps.nexuslauncher".equals(param.getProcessName());
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (systemServer) NavbarSystem.install(this, param.getClassLoader());
    }
    @Override public synchronized void onPackageLoaded(PackageLoadedParam param) {
        boolean target = systemUi && "com.android.systemui".equals(param.getPackageName())
                || launcher && "com.google.android.apps.nexuslauncher".equals(param.getPackageName());
        if (!target || installed) return;
        installed = true;
        if (launcher) NavbarTaskbarHost.install(this, param.getDefaultClassLoader());
        else NavbarHost.install(this, param.getDefaultClassLoader());
        try {
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        if (launcher) NavbarTaskbarHost.connect((Application) chain.getArg(0));
                        else NavbarHost.connect((Application) chain.getArg(0));
                        return result;
                    });
        } catch (ReflectiveOperationException error) { android.util.Log.e("NativeNavbar", "SystemUI connect unavailable", error); }
    }
}
