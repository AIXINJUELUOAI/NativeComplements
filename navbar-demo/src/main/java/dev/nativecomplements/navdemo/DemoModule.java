package dev.nativecomplements.navdemo;

import android.content.Context;
import android.view.SurfaceControl;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Observation only: no app hooks, transactions, Insets changes or navigation color writes. */
public final class DemoModule extends XposedModule {
    private boolean systemServer;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemServer = param.isSystemServer();
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!systemServer) return;
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        DemoCapture backend = null;
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> manager = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
            Class<?> policy = Class.forName("com.android.server.wm.DisplayPolicy", false, loader);
            Method ready = manager.getDeclaredMethod("systemReady");
            Method focus = manager.getDeclaredMethod("updateFocusedWindowLocked", int.class, boolean.class);
            Method draw = window.getDeclaredMethod("finishDrawing", SurfaceControl.Transaction.class, int.class);
            Method layout = policy.getDeclaredMethod("finishPostLayoutPolicyLw");
            backend = new DemoCapture(loader);
            DemoCapture capture = backend;
            hooks.add(hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object service = chain.getThisObject();
                    capture.connect((Context) DemoCapture.field(service, "mContext"), service,
                            DemoCapture.field(service, "mGlobalLock"));
                } catch (ReflectiveOperationException | RuntimeException error) {
                    capture.stop();
                    android.util.Log.w("NavbarDemo", "Observer connect unavailable", error);
                }
                return result;
            }));
            for (Method event : new Method[]{focus, draw, layout}) {
                hooks.add(hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    capture.request();
                    return result;
                }));
            }
            android.util.Log.i("NavbarDemo", "Read-only observer hooks ready");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) try { hook.unhook(); } catch (RuntimeException ignored) {}
            if (backend != null) backend.stop();
            android.util.Log.w("NavbarDemo", "Observer preflight failed; native behavior unchanged", error);
        }
    }
}
