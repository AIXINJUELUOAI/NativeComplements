package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.view.ViewParent;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import io.github.libxposed.api.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Drawer-only themed flag; all material, geometry, delegates and caching belong to Launcher.
 * Drawer AppInfo icons are normally fetched without the cache's theme flag, which strips the
 * monochrome layer, so only icons previously loaded themed could render Monet. While enabled the
 * drawer lookups request themed entries and already-loaded drawer icons are upgraded once.
 */
final class DrawerMonet {
    private static final int THEMED_LOOKUP = 8;
    private final Map<Object, Boolean> upgraded = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean userEnabled, systemThemed;
    private Object iconCacheRef;
    private Method updateInBackground, updateMask;
    private Field bitmapField, themedField;
    private final Map<View, java.lang.ref.WeakReference<View>> gridIcons = new WeakHashMap<>();
    private final ThreadLocal<Boolean> binding = new ThreadLocal<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean enabled;
    private Context context;
    private Handler worker;
    private HostSnapshot settings;
    private Class<?> recycler, searchRecycler, appInfo, predictionRow;
    private Method standard;

    void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> bubble = Class.forName("com.android.launcher3.BubbleTextView", false, loader);
        Class<?> adapter = Class.forName("com.android.launcher3.allapps.BaseAllAppsAdapter", false, loader);
        Class<?> item = Class.forName("com.android.launcher3.model.data.ItemInfoWithIcon", false, loader);
        Class<?> bitmap = Class.forName("com.android.launcher3.icons.BitmapInfo", false, loader);
        Class<?> shape = Class.forName("com.android.launcher3.icons.IconShape", false, loader);
        recycler = Class.forName("com.android.launcher3.allapps.AllAppsRecyclerView", false, loader);
        searchRecycler = Class.forName("com.android.launcher3.allapps.SearchRecyclerView", false, loader);
        appInfo = Class.forName("com.android.launcher3.model.data.AppInfo", false, loader);
        predictionRow = Class.forName("com.android.launcher3.appprediction.PredictionRowView", false, loader);
        Method factory = adapter.getDeclaredMethod("getIconOnCreateSetup", android.view.ViewGroup.class);
        standard = bubble.getDeclaredMethod("setStandardIcon", item);
        standard.setAccessible(true);
        Method create = bitmap.getDeclaredMethod("newIcon", Context.class, int.class, shape);
        Class<?> lookup = Class.forName("com.android.launcher3.icons.cache.CacheLookupFlag", false, loader);
        updateMask = lookup.getDeclaredMethod("updateMask", int.class, boolean.class);
        Class<?> cache = Class.forName("com.android.launcher3.icons.IconCache", false, loader);
        Class<?> receiver = Class.forName("com.android.launcher3.icons.IconCache$ItemInfoUpdateReceiver", false, loader);
        updateInBackground = cache.getDeclaredMethod("updateIconInBackground", java.util.concurrent.Executor.class, receiver, item, lookup);
        Method titleAndIcon = cache.getDeclaredMethod("getTitleAndIcon", item, lookup, java.util.function.Supplier.class);
        Class<?> request = Class.forName("com.android.launcher3.model.data.IconRequestInfo", false, loader);
        java.lang.reflect.Constructor<?> requestInit = request.getDeclaredConstructor(item,
                android.content.pm.LauncherActivityInfo.class, byte[].class, boolean.class, lookup);
        bitmapField = item.getField("bitmap");
        themedField = bitmap.getField("themedBitmap");
        module.deoptimize(bubble.getDeclaredMethod("applyIconAndLabel", item));
        module.deoptimize(bubble.getDeclaredMethod("applyFromWorkspaceItem",
                Class.forName("com.android.launcher3.model.data.WorkspaceItemInfo", false, loader)));
        module.deoptimize(itemNewIcon(item));
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        try {
            hooks.add(module.hook(factory).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                View view = (View) chain.proceed();
                synchronized (gridIcons) {
                    gridIcons.put(view, new java.lang.ref.WeakReference<>((View) chain.getArg(0)));
                }
                return view;
            }));
            hooks.add(module.hook(standard).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                View view = (View) chain.getThisObject();
                View predictions = predictionParent(view);
                if (predictions != null) synchronized (gridIcons) {
                    gridIcons.put(view, new java.lang.ref.WeakReference<>(predictions));
                }
                Object info = chain.getArg(0);
                boolean active = enabled && eligibleItem(view, info);
                Object result = inScope(binding, active, chain::proceed);
                if (active && appInfo.isInstance(info)) upgrade(view, info);
                return result;
            }));
            // New drawer lookups keep the monochrome layer while enabled (bulk loader and single loads).
            hooks.add(module.hook(requestInit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || !appInfo.isInstance(chain.getArg(0))) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[4] = themedLookup(args[4]);
                return chain.proceed(args);
            }));
            hooks.add(module.hook(titleAndIcon).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || !appInfo.isInstance(chain.getArg(0))) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = themedLookup(args[1]);
                return chain.proceed(args);
            }));
            hooks.add(module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || !Boolean.TRUE.equals(binding.get())) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = themedFlags((Integer) args[1]);
                // Same BitmapInfo and native rendering as desktop: no async copies or rescaling.
                return chain.proceed(args);
            }));
        } catch (Throwable failure) {
            for (var hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            throw failure;
        }
    }

    static int themedFlags(int flags) { return flags | 1; }

    private Object themedLookup(Object flag) throws ReflectiveOperationException {
        return flag == null ? null : updateMask.invoke(flag, THEMED_LOOKUP, true);
    }

    /** One background reload per drawer item whose BitmapInfo was cached without its themed layer. */
    private void upgrade(View view, Object info) {
        try {
            Object bitmap = bitmapField.get(info);
            if (bitmap == null || themedField.get(bitmap) != null || upgraded.put(info, Boolean.TRUE) != null) return;
            Object cache = iconCache();
            if (cache == null || !updateInBackground.getParameterTypes()[1].isInstance(view)) return;
            Object flag = bitmap.getClass().getMethod("getMatchingLookupFlag").invoke(bitmap);
            updateInBackground.invoke(cache, (java.util.concurrent.Executor) main::post, view, info, themedLookup(flag));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            android.util.Log.w("NcDrawerMonet", "themed reload failed", failure);
        }
    }

    private Object iconCache() throws ReflectiveOperationException {
        if (iconCacheRef == null) {
            ClassLoader loader = recycler.getClassLoader();
            Class<?> state = Class.forName("com.android.launcher3.LauncherAppState", false, loader);
            Object singleton = state.getField("INSTANCE").get(null);
            Object app = singleton.getClass().getMethod("get", Context.class).invoke(singleton, context);
            iconCacheRef = state.getField("iconCache").get(app);
        }
        return iconCacheRef;
    }

    void systemThemed(boolean themed) { systemThemed = themed; apply(); }

    private void apply() {
        boolean next = IconRenderPolicy.themed(userEnabled,systemThemed);
        if (enabled == next) return;
        enabled = next;
        android.util.Log.i("NcDrawerMonet", "enabled=" + next + " user=" + userEnabled + " systemThemed=" + systemThemed);
        if (!next) upgraded.clear();
        rebind();
    }

    static Method itemNewIcon(Class<?> item) throws NoSuchMethodException {
        try { return item.getDeclaredMethod("newIcon", Context.class, int.class); }
        catch (NoSuchMethodException currentUnavailable) {
            return item.getDeclaredMethod("newIcon", int.class, Context.class);
        }
    }

    @FunctionalInterface interface ScopedCall { Object run() throws Throwable; }
    static Object inScope(ThreadLocal<Boolean> scope, boolean active, ScopedCall call) throws Throwable {
        Boolean previous = scope.get();
        scope.set(active);
        try { return call.run(); }
        finally { if (previous == null) scope.remove(); else scope.set(previous); }
    }

    private void rebind() {
        List<View> views;
        synchronized (gridIcons) { views = new ArrayList<>(gridIcons.keySet()); }
        for (View view : views) {
            if (!eligibleItem(view, view.getTag())) continue;
            try { standard.invoke(view, view.getTag()); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                android.util.Log.w("NcDrawerMonet", "native drawer rebind failed", failure);
            }
        }
    }

    private View predictionParent(View view) {
        if (predictionRow == null) return null;
        View row = null;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (searchRecycler.isInstance(parent)) return null;
            if (predictionRow.isInstance(parent)) row = (View) parent;
        }
        return row;
    }

    private boolean eligibleItem(View view, Object item) {
        return item != null && isDrawer(view)
                && (appInfo.isInstance(item) || predictionParent(view) != null);
    }

    private boolean isDrawer(View view) {
        View createdFor;
        synchronized (gridIcons) {
            var parent = gridIcons.get(view);
            if (parent == null) return false;
            createdFor = parent.get();
        }
        if (view.getParent() == null)
            return recycler.isInstance(createdFor) && !searchRecycler.isInstance(createdFor);
        boolean drawer = false;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (searchRecycler.isInstance(parent)) return false;
            if (recycler.isInstance(parent) || (predictionRow != null && predictionRow.isInstance(parent))) drawer = true;
        }
        return drawer;
    }

    static Context attachedContext(Context app) {
        Context application = app.getApplicationContext();
        // Application.attach runs before LoadedApk publishes its Application context.
        return application != null ? application : app;
    }

    void connect(Context app) {
        context = attachedContext(app);
        HandlerThread thread = new HandlerThread("NcDrawerMonet"); thread.start();
        worker = new Handler(thread.getLooper());
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { worker.post(DrawerMonet.this::reload); }
        }, new IntentFilter(ShadeStore.CHANGED), IconLayerStore.CONTROL, null, Context.RECEIVER_EXPORTED);
        worker.post(this::reload);
    }

    void artworkChanged() { main.post(this::rebind); }

    private void reload() {
        if (settings == null) settings = HostSnapshot.open(context, "drawer_monet");
        var read = settings.read(() -> context.getContentResolver().call(
                Uri.parse("content://" + ShadeStore.AUTHORITY), "drawer", null, null));
        // A failed opt-in read must leave the native renderer untouched.
        boolean next = read.live() && read.config() != null && read.config().getBoolean("drawerMonet", false);
        main.post(() -> { userEnabled = next; apply(); });
        if (!read.live()) worker.postDelayed(this::reload, settings.retryDelay(3000));
    }
}
