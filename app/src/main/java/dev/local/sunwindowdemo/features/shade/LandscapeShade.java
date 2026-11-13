package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Optional legacy-shade helper, installed by the existing SystemUI entry only. */
public final class LandscapeShade {
    private static final List<WeakReference<Object>> panels = new ArrayList<>();
    private static final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private static volatile boolean ready, enabled;
    private static boolean attempted;
    private static Context context;
    private static Handler main;
    private static ExecutorService worker;
    private static Method updateResources;
    private static Consumer<String> logger = ignored -> {};
    private static String lastDecision = "";
    private LandscapeShade() {}

    public static synchronized void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        if (attempted) return;
        attempted = true;
        logger = log;
        try {
            Class<?> controller = Class.forName("com.android.systemui.statusbar.policy.SplitShadeStateControllerImpl", false, loader);
            Method shouldUse;
            try { shouldUse=controller.getDeclaredMethod("shouldUseSplitNotificationShade",Resources.class); }
            catch (NoSuchMethodException sceneRepository) {
                main=new Handler(Looper.getMainLooper());
                ComposeShadeLayout.install(module,loader,hooks,value->ready&&forceSplit(enabled,value.getConfiguration()));
                ready=true;
                note("SHADE_READY hydrated repository");
                return;
            }
            Class<?> panel = Class.forName("com.android.systemui.shade.NotificationPanelViewController", false, loader);
            updateResources = panel.getDeclaredMethod("updateResources");
            Field owner = panel.getDeclaredField("mSplitShadeStateController");
            Field resources = panel.getDeclaredField("mResources");
            if (shouldUse.getReturnType() != boolean.class || Modifier.isStatic(shouldUse.getModifiers())
                    || updateResources.getReturnType() != void.class || Modifier.isStatic(updateResources.getModifiers())
                    || owner.getType() != controller || resources.getType() != Resources.class)
                throw new NoSuchMethodException("legacy shade signatures");
            // API37 PixelOS R8 removes isEnabled() entirely. Exact legacy panel/controller
            // signatures are the fallback; never write a flag or install a Scene hook.
            try {
                Method sceneEnabled = Class.forName("com.android.systemui.scene.shared.flag.SceneContainerFlag", false, loader)
                        .getDeclaredMethod("isEnabled");
                if (sceneEnabled.getReturnType() != boolean.class || !Modifier.isStatic(sceneEnabled.getModifiers()))
                    throw new IllegalStateException("scene flag signature");
                sceneEnabled.setAccessible(true);
                if ((Boolean) sceneEnabled.invoke(null)) throw new IllegalStateException("SceneContainer enabled");
            } catch (ClassNotFoundException | NoSuchMethodException inlined) {
                // Verified device has only isUnexpectedlyInLegacyMode(), not a readable getter.
            }
            updateResources.setAccessible(true);
            main = new Handler(Looper.getMainLooper());
            hooks.add(module.hook(updateResources).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                remember(chain.getThisObject());
                return result;
            }));
            for (Constructor<?> constructor : panel.getDeclaredConstructors()) {
                hooks.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    Object instance = chain.getThisObject();
                    // Constructor work must finish before a queued refresh can run.
                    main.post(() -> { remember(instance); if (enabled) refresh(); });
                    return result;
                }));
            }
            hooks.add(module.hook(shouldUse).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                // Always run native code first; disabled/portrait/small configurations keep its result.
                Object original = chain.proceed();
                Resources value = (Resources) chain.getArg(0);
                Configuration config = value.getConfiguration();
                boolean force = ready && forceSplit(enabled, config);
                String decision = enabled + "/" + config.orientation + "/" + config.screenWidthDp + "/" + force;
                if (!decision.equals(lastDecision)) { lastDecision = decision; note("SHADE_DECISION " + decision); }
                return force ? true : original;
            }));
            // Install the shared getter before dropping compiled copies in all consumers.
            if (!module.deoptimize(updateResources)) throw new IllegalStateException("shade update deoptimize");
            deoptimizeCallers(module, loader);
            ComposeShadeLayout.install(module, loader, hooks,
                    value -> ready && forceSplit(enabled, value.getConfiguration()));
            ready = true;
            note("SHADE_READY");
        } catch (Throwable failure) {
            ready = false;
            enabled = false;
            for (XposedInterface.HookHandle hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            hooks.clear();
            note("SHADE_UNAVAILABLE " + failure);
        }
    }

    // Direct DEX callers in the verified API37 SystemUI; each can contain its own
    // ART-inlined copy. Deoptimizing only the main panel leaves QS/layout single-column.
    static final String[][] CALLERS = {
        {"shade.QuickSettingsControllerImpl", "updateResources"},
        {"shade.NotificationsQSContainerController", "updateResources"},
        {"media.controls.ui.controller.KeyguardMediaController", "updateResources$5"},
        {"media.controls.ui.controller.MediaHierarchyManager", "updateConfiguration$3"},
        {"shade.transition.LargeScreenShadeInterpolatorImpl$2", "onConfigChanged"},
        {"statusbar.AbstractLockscreenShadeTransitionController$1", "onConfigChanged"},
        {"statusbar.LockscreenShadeTransitionController", "updateResources$6"},
        {"statusbar.notification.stack.NotificationStackScrollLayout", "updateSplitNotificationShade"},
        {"statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor$special$$inlined$map$1$2", "emit"},
        {"statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder$$ExternalSyntheticLambda0", "invoke"},
        {"statusbar.policy.RemoteInputQuickSettingsDisabler", "onConfigChanged"}
    };
    static final String[] CONSTRUCTORS = {
        "shade.NotificationPanelViewController", "shade.QuickSettingsControllerImpl",
        "shade.transition.LargeScreenShadeInterpolatorImpl",
        "statusbar.AbstractLockscreenShadeTransitionController", "statusbar.policy.RemoteInputQuickSettingsDisabler"
    };
    private static void deoptimizeCallers(XposedModule module, ClassLoader loader) throws Exception {
        for (int i = 0; i < CALLERS.length; i++) {
            String[] caller = CALLERS[i];
            try {
                Class<?> owner = Class.forName("com.android.systemui." + caller[0], false, loader);
                Class<?>[] params = switch (caller[1]) {
                    case "onConfigChanged" -> new Class<?>[]{Configuration.class};
                    case "emit" -> new Class<?>[]{Object.class, Class.forName("kotlin.coroutines.Continuation", false, loader)};
                    case "invoke" -> new Class<?>[]{Object.class, Object.class};
                    default -> new Class<?>[0];
                };
                if (!module.deoptimize(owner.getDeclaredMethod(caller[1], params)))
                    throw new IllegalStateException("deoptimize returned false");
            } catch (Exception failure) {
                if (i < 2) throw failure; // QS and container must agree with the main panel.
                note("SHADE_CALLER_UNAVAILABLE " + caller[0] + " " + failure.getClass().getSimpleName());
            }
        }
        for (String name : CONSTRUCTORS) try {
            for (Constructor<?> constructor : Class.forName("com.android.systemui." + name, false, loader).getDeclaredConstructors())
                if (!module.deoptimize(constructor)) note("SHADE_CONSTRUCTOR_DEOPT_SKIPPED " + name);
        } catch (Exception failure) {
            // A queued native updateResources after construction corrects the initial state.
            note("SHADE_CONSTRUCTOR_DEOPT_SKIPPED " + name);
        }
    }

    static boolean forceSplit(boolean userEnabled, Configuration config) {
        return userEnabled && config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600;
    }

    /** Called after Application.onCreate regardless of whether SystemUiHost connected. */
    public static void connect(Application app) {
        if (!ready || !ShadeStore.SYSTEM_UI.equals(app.getPackageName())) return;
        main.post(() -> {
            if (context != null) return;
            context = app;
            note("SHADE_CONNECTED");
            worker = Executors.newSingleThreadExecutor();
            try {
                app.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        if (!ShadeStore.CHANGED.equals(intent.getAction())) return;
                        try {
                            int moduleUid = c.getPackageManager().getApplicationInfo("dev.nativecomplements.module", 0).uid;
                            if (getSentFromUid() == moduleUid) load();
                        } catch (Exception failure) { note("SHADE_SENDER_REJECTED"); }
                    }
                }, new IntentFilter(ShadeStore.CHANGED), null, main, Context.RECEIVER_EXPORTED);
                load();
            } catch (Exception failure) {
                enabled = false;
                refresh();
                note("SHADE_CONNECT_FAILED " + failure.getClass().getSimpleName());
            }
        });
    }

    private static void load() {
        if (!ready) return;
        worker.execute(() -> {
            boolean value = false;
            boolean retry = false;
            try {
                Bundle result = context.getContentResolver().call(Uri.parse("content://" + ShadeStore.AUTHORITY), "read", null, null);
                if (result == null) throw new IllegalStateException("empty shade settings");
                value = result.getBoolean("enabled", false);
            } catch (Exception failure) {
                retry = true;
                note("SHADE_SETTINGS_FAILED " + failure.getClass().getSimpleName());
            }
            boolean next = value, failed = retry;
            main.post(() -> {
                if (!ready) return;
                if (enabled != next) { enabled = next; refresh(); }
                note("SHADE_CONFIG enabled=" + enabled + " panels=" + panels.size());
                main.removeCallbacks(retryLoad);
                if (failed) main.postDelayed(retryLoad, 3000);
            });
        });
    }
    private static final Runnable retryLoad = LandscapeShade::load;

    private static void remember(Object panel) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> remember(panel)); return; }
        for (Iterator<WeakReference<Object>> it = panels.iterator(); it.hasNext();) {
            Object old = it.next().get();
            if (old == null) it.remove();
            else if (old == panel) return;
        }
        panels.add(new WeakReference<>(panel));
    }

    private static void refresh() {
        // Main-thread only. Use native reconfiguration, never fabricate an orientation change.
        List<Object> targets = new ArrayList<>();
        for (Iterator<WeakReference<Object>> it = panels.iterator(); it.hasNext();) {
            Object panel = it.next().get();
            if (panel == null) it.remove(); else targets.add(panel);
        }
        try {
            if (context != null) ComposeShadeLayout.refresh(context.getResources().getConfiguration());
        } catch (Exception failure) {
            enabled = false;
            ready = false;
            try { ComposeShadeLayout.refresh(context.getResources().getConfiguration()); } catch (Exception ignored) {}
            note("SHADE_COMPOSE_REFRESH_FAILED " + failure.getClass().getSimpleName());
        }
        for (Object panel : targets) try { updateResources.invoke(panel); }
        catch (Exception failure) {
            enabled = false;
            ready = false;
            // Restore the Compose flow and every panel after a partially applied override.
            try { if (context != null) ComposeShadeLayout.refresh(context.getResources().getConfiguration()); } catch (Exception ignored) {}
            for (Object target : targets) try { updateResources.invoke(target); } catch (Exception ignored) {}
            note("SHADE_REFRESH_FAILED restart SystemUI manually: " + failure.getClass().getSimpleName());
            break;
        }
    }

    private static void note(String message) { try { logger.accept(message); } catch (Throwable ignored) {} }
}
