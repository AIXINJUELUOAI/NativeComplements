package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.app.Instrumentation;
import io.github.libxposed.api.*;

/** System hosts own sampling; explicitly scoped applications own their Decor transparency. */
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
        boolean application = !systemServer && !systemUi && !launcher
                && android.os.Process.myUid() % 100000 >= 10000
                && !"dev.nativecomplements.module".equals(param.getPackageName());
        if ((!target && !application) || installed) return;
        installed = true;
        NavbarApp appBackend = application ? new NavbarApp() : null;
        if (appBackend != null) appBackend.install(this, param.getDefaultClassLoader());
        else if (launcher) NavbarTaskbarHost.install(this, param.getDefaultClassLoader());
        else NavbarHost.install(this, param.getDefaultClassLoader());
        try {
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (appBackend != null) appBackend.connect((Application) chain.getArg(0));
                        Object result = chain.proceed();
                        if (appBackend == null) {
                            if (launcher) NavbarTaskbarHost.connect((Application) chain.getArg(0));
                            else {
                                NavbarHost.connect((Application) chain.getArg(0));
                                NavbarLabelHost.connect((Application) chain.getArg(0));
                            }
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException error) { android.util.Log.e("NativeNavbar", "SystemUI connect unavailable", error); }
    }
}
