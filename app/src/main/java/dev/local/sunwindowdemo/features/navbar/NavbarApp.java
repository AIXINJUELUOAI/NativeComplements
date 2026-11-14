package dev.local.sunwindowdemo.features.navbar;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Per-package application bridge. Never changes the application's stored window attributes. */
final class NavbarApp {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<View, java.lang.ref.WeakReference<Activity>> activities = new WeakHashMap<>();
    private final ThreadLocal<Layout> layout = new ThreadLocal<>();
    private record Layout(Window window, WindowManager.LayoutParams attributes) {}
    private NavbarRules rules = new NavbarRules(Bundle.EMPTY);
    private Application application;
    private Handler reader;
    private Method updateColors;
    private Field decorWindow, lastInsets, windowConfiguration;
    private Method windowingMode, requestedVisibleTypes;
    private long version;
    private int navigationMode = -1;
    private boolean installed, enabled;
    private final Runnable reload = this::readSettings;
    private static final Uri CONFIG = Uri.parse("content://" + NavbarStore.AUTHORITY);

    // LSPosed-only entry: exact ROM members are preflighted; unavailable APIs leave native behavior intact.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    void install(XposedModule module, ClassLoader loader) {
        if (installed) return;
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        try {
            Class<?> decor = Class.forName("com.android.internal.policy.DecorView", false, loader);
            decorWindow = decor.getDeclaredField("mWindow");
            lastInsets = decor.getDeclaredField("mLastInsets");
            decorWindow.setAccessible(true);
            lastInsets.setAccessible(true);
            updateColors = decor.getDeclaredMethod("updateColorViews", WindowInsets.class, boolean.class);
            updateColors.setAccessible(true);
            Method calculate = decor.getDeclaredMethod("calculateNavigationBarColor", int.class);
            Method applyInsets = decor.getDeclaredMethod("onApplyWindowInsets", WindowInsets.class);
            Method legacyBackground = decor.getDeclaredMethod("drawLegacyNavigationBarBackground",
                    Class.forName("android.graphics.RecordingCanvas", false, loader));
            Method attributes = Window.class.getDeclaredMethod("getAttributes");
            requestedVisibleTypes = WindowInsetsController.class.getMethod("getRequestedVisibleTypes");
            windowConfiguration = android.content.res.Configuration.class.getDeclaredField("windowConfiguration");
            windowConfiguration.setAccessible(true);
            windowingMode = windowConfiguration.getType().getDeclaredMethod("getWindowingMode");
            windowingMode.setAccessible(true);
            hooks.add(module.hook(attributes).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                Layout current = layout.get();
                return current != null && current.window() == chain.getThisObject()
                        ? current.attributes() : original;
            }));
            hooks.add(module.hook(updateColors).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                View view = (View) chain.getThisObject();
                WindowInsets incoming = (WindowInsets) chain.getArg(0);
                Layout next = null;
                try {
                    NavbarRules.Choice choice = choice(view, incoming);
                    if (choice != null) {
                        Window window = (Window) decorWindow.get(view);
                        next = new Layout(window, layoutAttributes(window.getAttributes()));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                if (next == null) return chain.proceed();
                Layout previous = layout.get();
                layout.set(next);
                try { return chain.proceed(new Object[]{incoming, false}); }
                finally {
                    if (previous == null) layout.remove();
                    else layout.set(previous);
                }
            }));
            hooks.add(module.hook(calculate).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                try { return choice((View) chain.getThisObject(), null) != null ? 0 : original; }
                catch (ReflectiveOperationException | RuntimeException ignored) { return original; }
            }));
            hooks.add(module.hook(applyInsets).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object original = chain.proceed();
                try {
                    NavbarRules.Choice choice = choice((View) chain.getThisObject(), (WindowInsets) chain.getArg(0));
                    if (choice != null && choice.consumesNavigationInsets() && original instanceof WindowInsets insets)
                        return consumeNavigation(insets);
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
                return original;
            }));
            hooks.add(module.hook(legacyBackground).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                try { if (choice((View) chain.getThisObject(), null) != null) return null; }
                catch (ReflectiveOperationException | RuntimeException ignored) {}
                return chain.proceed();
            }));
            installed = true;
            android.util.Log.i("NativeNavbar", "APP_HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) try { hook.unhook(); } catch (RuntimeException ignored) {}
            android.util.Log.w("NativeNavbar", "App preflight failed; keeping native", error);
        }
    }

    static WindowManager.LayoutParams layoutAttributes(WindowManager.LayoutParams original) {
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(original);
        copy.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        copy.systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        return copy;
    }

    static WindowInsets consumeNavigation(WindowInsets original) {
        return new WindowInsets.Builder(original)
                .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.NONE).build();
    }

    static boolean eligible(boolean enabled, boolean gestures, boolean fullscreen, boolean secure,
                            boolean keyboard, int display, int windowType) {
        return enabled && gestures && fullscreen && !secure && !keyboard && display == 0
                && windowType == WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
    }

    private NavbarRules.Choice choice(View decor, WindowInsets incoming) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper() || application == null) return null;
        var reference = activities.get(decor);
        Activity activity = reference == null ? null : reference.get();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()
                || activity.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return null;
        Window window = activity.getWindow();
        if (window == null || window.peekDecorView() != decor || decorWindow.get(decor) != window) return null;
        Layout current = layout.get();
        WindowManager.LayoutParams attrs = current != null && current.window() == window
                ? current.attributes() : window.getAttributes();
        if (((attrs.systemUiVisibility | decor.getWindowSystemUiVisibility()) & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0)
            return null;
        WindowInsetsController controller = decor.getWindowInsetsController();
        if (controller == null || ((Integer) requestedVisibleTypes.invoke(controller) & WindowInsets.Type.navigationBars()) == 0)
            return null;
        WindowInsets raw = incoming != null ? incoming : (WindowInsets) lastInsets.get(decor);
        if (raw == null) raw = decor.getRootWindowInsets();
        // Unknown IME state is not permission to extend application content beneath it.
        if (raw == null || decor.getDisplay() == null || !raw.isVisible(WindowInsets.Type.navigationBars())) return null;
        boolean fullscreen = !activity.isInMultiWindowMode() && !activity.isInPictureInPictureMode()
                && (Integer) windowingMode.invoke(windowConfiguration.get(activity.getResources().getConfiguration())) == 1
                && attrs.width == WindowManager.LayoutParams.MATCH_PARENT
                && attrs.height == WindowManager.LayoutParams.MATCH_PARENT;
        if (!eligible(enabled, navigationMode == 2, fullscreen,
                (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                raw.isVisible(WindowInsets.Type.ime()), decor.getDisplay().getDisplayId(), attrs.type)) return null;
        NavbarRules.Choice selected = rules.resolve(application.getPackageName(), activity.getComponentName().getClassName(), version, true);
        return selected.mode() == NavbarStore.FLOATING ? selected : null;
    }

    private static int navigationMode(Context context) {
        int mode = Settings.Secure.getInt(context.getContentResolver(), "navigation_mode", -1);
        if (mode >= 0 && mode <= 2) return mode;
        int id = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
        return id == 0 ? -1 : context.getResources().getInteger(id);
    }

    void connect(Application app) {
        if (!installed || application != null) return;
        application = app;
        HandlerThread thread = new HandlerThread("NativeNavbarApp", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        reader = new Handler(thread.getLooper());
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private void track(Activity activity) {
                View decor = activity.getWindow().getDecorView();
                activities.put(decor, new java.lang.ref.WeakReference<>(activity));
                refresh(decor);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { track(activity); }
            @Override public void onActivityResumed(Activity activity) { track(activity); reader.post(reload); }
            @Override public void onActivityDestroyed(Activity activity) { activities.values().removeIf(value -> value.get() == null || value.get() == activity); }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        });
        try {
            app.getContentResolver().registerContentObserver(CONFIG, true, new ContentObserver(reader) {
                @Override public void onChange(boolean selfChange) { reader.removeCallbacks(reload); reader.post(reload); }
            });
        } catch (RuntimeException unavailable) {
            android.util.Log.w("NativeNavbar", "App config observer unavailable; retrying on resume");
        }
        try {
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("navigation_mode"), false,
                    new ContentObserver(reader) {
                        @Override public void onChange(boolean selfChange) { reader.removeCallbacks(reload); reader.post(reload); }
                    });
        } catch (RuntimeException unavailable) {
            android.util.Log.w("NativeNavbar", "Navigation mode observer unavailable; retrying on resume");
        }
        reader.post(reload);
    }

    private void readSettings() {
        reader.removeCallbacks(reload);
        Bundle snapshot = Bundle.EMPTY;
        long packageVersion = -1;
        int currentNavigationMode = -1;
        try {
            currentNavigationMode = navigationMode(application);
            packageVersion = application.getPackageManager().getPackageInfo(application.getPackageName(), 0).getLongVersionCode();
            Bundle result = application.getContentResolver().call(CONFIG, "read", application.getPackageName(), null);
            if (result != null) snapshot = result;
            else reader.postDelayed(reload, 5000);
        } catch (Exception unavailable) {
            // No cross-package fallback, stale enable, or privileged "system" read.
            reader.postDelayed(reload, 5000);
        }
        NavbarRules next = new NavbarRules(snapshot);
        long nextVersion = packageVersion;
        int nextNavigationMode = currentNavigationMode;
        boolean nextEnabled = snapshot.getBoolean("enabled", false);
        main.post(() -> {
            version = nextVersion;
            navigationMode = nextNavigationMode;
            enabled = nextEnabled;
            rules = next;
            for (View decor : new ArrayList<>(activities.keySet())) refresh(decor);
        });
    }

    private void refresh(View decor) {
        try {
            updateColors.invoke(decor, lastInsets.get(decor), false);
            decor.requestApplyInsets();
            decor.requestLayout();
            decor.invalidate();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            decor.requestApplyInsets();
        }
    }
}
