package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.List;

/** Settings main process only; native factories still own all shadow, sizing and badge work. */
public final class SettingsIconModule extends XposedModule {
    private boolean eligible, installed;
    private volatile Context context;
    private final ThreadLocal<SettingsIconAccess.FactoryCall> calls = new ThreadLocal<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        eligible = SettingsIconAccess.eligible(param.isSystemServer(), param.getProcessName(), IconLayerStore.SETTINGS);
    }

    @Override public synchronized void onPackageLoaded(PackageLoadedParam param) {
        if (!eligible || installed || !IconLayerStore.SETTINGS.equals(param.getPackageName())) return;
        installed = true;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            SettingsIconAccess access = new SettingsIconAccess(param.getDefaultClassLoader());
            int processUser = android.os.Process.myUid() / 100000;
            SettingsIconAccess.NativeCache nativeCache = nativeCache(param.getDefaultClassLoader());
            // This hook records the explicit user, but ALWAYS executes the native factory body.
            handles.add(hook(access.factoryIcon).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (IconAssets.loadingOriginal()) return chain.proceed();
                PackageItemInfo item = (PackageItemInfo) chain.getArg(0);
                ApplicationInfo app = (ApplicationInfo) chain.getArg(1);
                SettingsIconAccess.FactoryCall call;
                try {
                    call = access.factoryCall(chain.getThisObject(), item, app, (int) chain.getArg(2), processUser);
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    call = new SettingsIconAccess.FactoryCall(null, item, app, -1, false);
                }
                SettingsIconAccess.FactoryCall previous = calls.get();
                calls.set(call);
                try { return chain.proceed(); }
                finally { if (previous == null) calls.remove(); else calls.set(previous); }
            }));
            handles.add(hook(access.loadIcon).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (IconAssets.loadingOriginal()) return chain.proceed();
                android.graphics.drawable.Drawable replacement = null;
                try {
                    PackageItemInfo item = (PackageItemInfo) chain.getArg(0);
                    ApplicationInfo app = (ApplicationInfo) chain.getArg(1);
                    Object pm = chain.getThisObject();
                    Context c = access.pmContext(pm);
                    if (context != null && c != null && access.applicationIcon(item))
                        replacement = SettingsIconAccess.replacement(c, item, app, access.user(c), processUser, pm, calls.get());
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    // Unknown identity or unavailable artwork keeps the original loader and fallback.
                }
                return replacement != null ? replacement : chain.proceed();
            }));
            handles.add(hook(access.attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Context app = (Context) chain.getThisObject();
                if (context == null && IconLayerStore.SETTINGS.equals(app.getPackageName()) && access.user(app) == processUser) {
                    context = app;
                    if (nativeCache != null) IconAssets.onReload(() -> {
                        try { nativeCache.invalidate(processUser); }
                        catch (ReflectiveOperationException | RuntimeException unavailable) { note("CACHE_INVALIDATION_UNAVAILABLE"); }
                    });
                    IconAssets.connect(app);
                }
                return result;
            }));
            note("READY");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            for (XposedInterface.HookHandle handle : handles) try { handle.unhook(); } catch (Throwable ignored) {}
            note("UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    private SettingsIconAccess.NativeCache nativeCache(ClassLoader loader) {
        try { return new SettingsIconAccess.NativeCache(loader); }
        catch (ReflectiveOperationException | RuntimeException unavailable) {
            // ponytail: no forced rebind/restart; native detail artwork reloads when the user reopens it.
            note("CACHE_INVALIDATION_UNAVAILABLE");
            return null;
        }
    }

    private void note(String message) { try { log(4, "NcSettingsIcons", message); } catch (Throwable ignored) {} }
}
