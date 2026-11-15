package dev.local.sunwindowdemo.features.navbar;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
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
    private long failureLogAt;
    private void probeFailure(Throwable error) {
        long now = SystemClock.uptimeMillis();
        if (now - failureLogAt < 5000) return;
        failureLogAt = now;
        android.util.Log.w("NativeNavbar", "CONTAINER_REPORT_UNAVAILABLE " + error.getClass().getSimpleName(), error);
    }
    private final Runnable reload = this::readSettings;
    private static final Uri CONFIG = Uri.parse("content://" + NavbarStore.AUTHORITY);
    static final String PAGE = "dev.nativecomplements.module.NAVBAR_PAGE";
    /** Last reported page signature and "page draws its own navigation area" state per decor. */
    private final Map<View, Integer> containerModes = new WeakHashMap<>();
    private final Map<View, Boolean> active = new WeakHashMap<>();
    private final Map<View, Integer> navigationHeights = new WeakHashMap<>();
    private final Map<View, Integer> contentBottoms = new WeakHashMap<>();
    private final Map<View, Boolean> serverFloating = new WeakHashMap<>();
    private final Map<View, Long> serverSeen = new WeakHashMap<>();
    private boolean serverFloating(View decor) {
        return SystemClock.uptimeMillis() - serverSeen.getOrDefault(decor, 0L) <= NavbarStructure.MAX_AGE_MS
                && Boolean.TRUE.equals(serverFloating.get(decor));
    }
    private final Map<View, String> pageSignatures = new WeakHashMap<>();
    private final Map<View, Boolean> adaptedState = new WeakHashMap<>();
    private final Map<View, Runnable> layoutWatch = new WeakHashMap<>();

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
                if (next == null) {
                    Object result = chain.proceed();
                    main.post(() -> report(view));
                    return result;
                }
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
        if (raw == null || decor.getDisplay() == null
                || !raw.isVisible(WindowInsets.Type.navigationBars()) && !serverFloating(decor)) return null;
        boolean fullscreen = !activity.isInMultiWindowMode() && !activity.isInPictureInPictureMode()
                && (Integer) windowingMode.invoke(windowConfiguration.get(activity.getResources().getConfiguration())) == 1
                && attrs.width == WindowManager.LayoutParams.MATCH_PARENT
                && attrs.height == WindowManager.LayoutParams.MATCH_PARENT;
        if (!eligible(enabled, navigationMode == 2, fullscreen,
                (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                raw.isVisible(WindowInsets.Type.ime()), decor.getDisplay().getDisplayId(), attrs.type)) return null;
        String pkg = application.getPackageName(), name = activity.getComponentName().getClassName();
        String page = pageSignatures.getOrDefault(decor, "");
        NavbarRules.Choice selected = page.isEmpty() ? null : rules.page(pkg, name + "#" + page, version);
        if (selected == null) selected = rules.resolve(pkg, name, version, true);
        if (selected.mode() == NavbarStore.AUTO) {
            // System-side acceptance owns AUTO mode; do not race an unaccepted local suggestion.
            selected = new NavbarRules.Choice(serverFloating(decor) ? NavbarStore.FLOATING : NavbarStore.ORIGINAL,
                    null, "容器判断");
        }
        return selected.mode() == NavbarStore.FLOATING ? selected : null;
    }

    /**
     * The page is adapted when the window framework paints no navigation background and the content view
     * reaches the screen bottom: the app itself draws behind the gesture area (QQ home blur bar). Unadapted
     * pages leave DecorView's coloured navigation background there. Native adaptation is never inferred
     * while this module makes the window transparent; live container reports continue on layout/scroll/heartbeat.
     */
    private void report(View decor) {
        try {
            if (application == null || !installed || !enabled) return;
            var reference = activities.get(decor);
            Activity activity = reference == null ? null : reference.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || decor.getDisplay() == null || !decor.isShown()) return;
            Window window = activity.getWindow();
            WindowInsets insets = decor.getRootWindowInsets();
            if (window == null || insets == null || decor.getWindowToken() == null) return;
            int nav = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            if (nav > 0) navigationHeights.put(decor, nav);
            nav = navigationHeights.getOrDefault(decor, 0);
            if (nav <= 0) return;
            pageSignatures.put(decor, signature(decor));
            boolean floating = serverFloating(decor) || choice(decor, null) != null;
            int previous = containerModes.getOrDefault(decor, NavbarStore.ORIGINAL);
            Boolean adapted = adaptedState.get(decor);
            // Never interpret our own transparency / insets as native app adaptation.
            if (!floating) {
                boolean painted = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                        && (window.getNavigationBarColor() >>> 24) != 0;
                View content = decor.findViewById(android.R.id.content);
                if (content != null && content.getHeight() > 0) {
                    int[] at = new int[2]; content.getLocationInWindow(at);
                    adapted = !painted && at[1] + content.getHeight() >= decor.getHeight() - nav / 2;
                    adaptedState.put(decor, adapted);
                }
            }
            boolean secure = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            boolean safe = eligible(enabled, navigationMode == 2, !activity.isInMultiWindowMode() && !activity.isInPictureInPictureMode(),
                    secure, insets.isVisible(WindowInsets.Type.ime()), decor.getDisplay().getDisplayId(), window.getAttributes().type)
                    && activity.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int reserved = floating ? 0 : Math.max(0, decor.getHeight() - contentBottoms.getOrDefault(decor, decor.getHeight() - nav));
            NavbarContainer.Result result = safe ? NavbarContainer.inspect(decor, reserved, floating) : null;
            int suggestion = result == null || Boolean.TRUE.equals(adapted) ? NavbarStore.ORIGINAL : result.mode();
            containerModes.put(decor, suggestion);
            String page = pageSignatures.getOrDefault(decor, "");
            Bundle extras = new Bundle();
            extras.putBinder("window", windowClient(decor));
            extras.putLong("sampledAt", SystemClock.uptimeMillis());
            extras.putString("container", result == null ? "无结构信息" : Boolean.TRUE.equals(adapted) ? "应用自绘导航区" : result.kind());
            extras.putString("node", result == null ? "" : result.node());
            extras.putString("reason", result == null ? (secure ? "安全窗口不检查" : "键盘／横屏／窗口安全限制") : result.reason());
            extras.putString("bounds", result == null || result.bounds() == null ? "" : result.bounds().toShortString());
            extras.putInt("nodes", result == null ? 0 : result.nodes());
            extras.putLong("nanos", result == null ? 0 : result.nanos());
            extras.putInt("suggested", suggestion);
            Intent intent = new Intent(PAGE).setPackage("android")
                    .putExtra("activity", activity.getComponentName().getClassName()).putExtra("page", page).putExtras(extras);
            if (adapted != null) intent.putExtra("adapted", (boolean) adapted);
            application.sendBroadcast(intent, null,
                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            if (previous != suggestion) refresh(decor);
        } catch (ReflectiveOperationException | RuntimeException error) { probeFailure(error); }
    }

    /**
     * Identifies the page shown inside one Activity without relying on class names surviving obfuscation:
     * the chain of on-screen views covering most of the window (the visible Fragment containers and their
     * roots). Only full-size views are visited, so the walk stays short; list-like groups (several children
     * of one class: feeds, pagers) contribute the on-screen child's class but are not entered, so scrolling
     * content never changes the page. Stable for one app version; "" when nothing qualifies.
     */
    static String signature(View decor) {
        int width = decor.getWidth(), height = decor.getHeight();
        if (width <= 0 || height <= 0) return "";
        StringBuilder chain = new StringBuilder();
        int[] at = new int[2];
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(decor);
        int visited = 0, children = 0;
        while (!pending.isEmpty() && visited++ < 400) {
            View view = pending.removeFirst();
            if (!(view instanceof ViewGroup group)) continue;
            Map<Class<?>, Integer> classes = new HashMap<>();
            List<View> large = new ArrayList<>();
            for (int i = 0; i < group.getChildCount() && children++ < 400; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0.01f) continue;
                classes.merge(child.getClass(), 1, Integer::sum);
                if (child.getWidth() < width * 0.85f || child.getHeight() < height * 0.6f) continue;
                child.getLocationInWindow(at);
                if (Math.abs(at[0]) > width * 0.15f || at[1] > height * 0.3f) continue;   // off-screen pager page
                large.add(child);
            }
            boolean listLike = group.getChildCount() >= 3 && classes.values().stream().anyMatch(count -> count >= 3);
            for (View child : large) {
                chain.append(child.getClass().getName()).append(':').append(child.getId()).append(listLike ? ";" : ">");
                if (!listLike) pending.add(child);
            }
        }
        if (chain.length() == 0) return "";
        return String.format(Locale.ROOT, "%08x", chain.toString().hashCode());
    }

    /** Layout / scroll observations are coalesced; heartbeat expires at the receiver if this window stops. */
    private void watch(View decor) {
        if (layoutWatch.containsKey(decor)) return;
        long[] scheduledAt = {0};
        Runnable settle = new Runnable() {
            @Override public void run() {
                scheduledAt[0] = 0;
                if (!Boolean.TRUE.equals(active.get(decor))) return;
                report(decor);
                if (enabled) {
                    main.removeCallbacks(this);
                    scheduledAt[0] = SystemClock.uptimeMillis() + 750;
                    main.postDelayed(this, 750);
                }
            }
        };
        layoutWatch.put(decor, settle);
        Runnable changed = () -> {
            if (!enabled || !Boolean.TRUE.equals(active.get(decor))) return;
            // Throttle, not debounce: continuous scrolling/layout must not starve observations.
            long due = SystemClock.uptimeMillis() + 64;
            if (!main.hasCallbacks(settle) || scheduledAt[0] > due) {
                main.removeCallbacks(settle); scheduledAt[0] = due; main.postDelayed(settle, 64);
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(changed::run);
        decor.getViewTreeObserver().addOnScrollChangedListener(changed::run);
    }

    private static int navigationMode(Context context) {
        int mode = Settings.Secure.getInt(context.getContentResolver(), "navigation_mode", -1);
        if (mode >= 0 && mode <= 2) return mode;
        int id = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
        return id == 0 ? -1 : context.getResources().getInteger(id);
    }

    private static Method rootMethod;
    private static Field clientWindow;
    private static IBinder windowClient(View decor) throws ReflectiveOperationException {
        if (rootMethod == null) {
            rootMethod = View.class.getDeclaredMethod("getViewRootImpl"); rootMethod.setAccessible(true);
            clientWindow = Class.forName("android.view.ViewRootImpl").getDeclaredField("mWindow");
            clientWindow.setAccessible(true);
        }
        Object root = rootMethod.invoke(decor);
        return root == null ? null : ((IInterface) clientWindow.get(root)).asBinder();
    }

    void connect(Application app) {
        if (!installed || application != null) return;
        application = app;
        app.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 1000) return;
                try {
                    IBinder token = intent.getExtras() == null ? null : intent.getExtras().getBinder("window");
                    int height = intent.getIntExtra("height", 0);
                    if (token == null || height <= 0 || height > app.getResources().getDisplayMetrics().heightPixels / 3) return;
                    for (View decor : new ArrayList<>(activities.keySet())) {
                        if (!token.equals(windowClient(decor))) continue;
                        navigationHeights.put(decor, height);
                        int bottom = intent.getIntExtra("contentBottom", -1);
                        if (bottom > 0 && bottom <= decor.getHeight()) contentBottoms.put(decor, bottom);
                        boolean next = intent.getBooleanExtra("floating", false);
                        serverSeen.put(decor, SystemClock.uptimeMillis());
                        Boolean before = serverFloating.put(decor, next);
                        if (before == null || before != next) refresh(decor);
                        Runnable probe = layoutWatch.get(decor);
                        if (probe != null && !main.hasCallbacks(probe)) main.post(probe);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }, new android.content.IntentFilter(PAGE + ".WINDOW"), "android.permission.STATUS_BAR_SERVICE", main, Context.RECEIVER_EXPORTED);
        HandlerThread thread = new HandlerThread("NativeNavbarApp", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        reader = new Handler(thread.getLooper());
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private void track(Activity activity) {
                View decor = activity.getWindow().getDecorView();
                activities.put(decor, new java.lang.ref.WeakReference<>(activity));
                active.put(decor, true);
                refresh(decor);
                watch(decor);
                Runnable probe = layoutWatch.get(decor);
                main.removeCallbacks(probe); main.post(probe);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { track(activity); }
            @Override public void onActivityResumed(Activity activity) { track(activity); reader.post(reload); }
            @Override public void onActivityDestroyed(Activity activity) {
                View decor = activity.getWindow().peekDecorView();
                if (decor == null) return;
                Runnable probe = layoutWatch.remove(decor); if (probe != null) main.removeCallbacks(probe);
                active.remove(decor); activities.remove(decor); containerModes.remove(decor);
                adaptedState.remove(decor); navigationHeights.remove(decor); contentBottoms.remove(decor); serverFloating.remove(decor); serverSeen.remove(decor); pageSignatures.remove(decor);
            }
            @Override public void onActivityStarted(Activity activity) { track(activity); }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {
                View decor = activity.getWindow().peekDecorView();
                active.remove(decor);
                Runnable probe = layoutWatch.get(decor); if (probe != null) main.removeCallbacks(probe);
            }
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
            containerModes.clear();
            if (!enabled) { adaptedState.clear(); serverFloating.clear(); serverSeen.clear(); }
            for (View decor : new ArrayList<>(activities.keySet())) {
                refresh(decor);
                Runnable probe = layoutWatch.get(decor);
                if (probe != null) { main.removeCallbacks(probe); if (enabled) main.post(probe); }
            }
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
