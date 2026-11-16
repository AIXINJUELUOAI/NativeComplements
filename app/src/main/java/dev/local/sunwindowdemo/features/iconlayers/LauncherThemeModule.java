package dev.local.sunwindowdemo.features.iconlayers;

import io.github.libxposed.api.*;

/** Standalone product entry: only observes the native themed-icon state. */
public final class LauncherThemeModule extends XposedModule {
    private boolean eligible,attempted;
    @Override public void onModuleLoaded(ModuleLoadedParam p){
        eligible=!p.isSystemServer()
                &&dev.local.sunwindowdemo.core.LauncherTargets.isLauncherProcess(p.getProcessName());
    }
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||attempted||!dev.local.sunwindowdemo.core.LauncherTargets.isLauncher(p.getPackageName()))return;
        attempted=true;
        LauncherThemeReporter reporter=new LauncherThemeReporter(themed->{});
        try{reporter.install(this,p.getDefaultClassLoader());log(4,"LauncherTheme","READY");}
        catch(ReflectiveOperationException|RuntimeException failure){
            reporter.close();log(4,"LauncherTheme","UNAVAILABLE "+failure.getClass().getSimpleName());
        }
    }
}
