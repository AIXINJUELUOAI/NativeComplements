package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.view.ViewParent;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.*;

/** Opt-in native themed rendering at the drawer grid's binding boundary only. */
final class DrawerMonet {
    private final Map<View, java.lang.ref.WeakReference<View>> gridIcons = new WeakHashMap<>();
    private final ThreadLocal<Boolean> binding = new ThreadLocal<>();
    private final ThreadLocal<Object> boundItem = new ThreadLocal<>();
    // ponytail: at most 128 native-sized private copies; eldest eviction retries only on later binding.
    private static final class MaterialKey {
        private final Object bitmap, item;
        MaterialKey(Object bitmap, Object item) { this.bitmap = bitmap; this.item = item; }
        Object bitmap() { return bitmap; }
        @Override public boolean equals(Object other) {
            return other instanceof MaterialKey key && bitmap == key.bitmap && item == key.item;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(bitmap) + System.identityHashCode(item); }
    }
    private final Map<MaterialKey, Object> copies = new LinkedHashMap<>();
    private DrawerMonetAssets assets;
    private java.lang.reflect.Field itemBitmap, themedBitmap;
    private Object unsupported;
    private int generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean enabled;
    private Context context;
    private Handler worker;
    private HostSnapshot settings;
    private Class<?> recycler, searchRecycler, appInfo;
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
        Method factory = adapter.getDeclaredMethod("getIconOnCreateSetup", android.view.ViewGroup.class);
        standard = bubble.getDeclaredMethod("setStandardIcon", item);
        standard.setAccessible(true);
        Method create = bitmap.getDeclaredMethod("newIcon", Context.class, int.class, shape);
        Method lowRes = bitmap.getDeclaredMethod("isLowRes");
        itemBitmap = item.getField("bitmap");
        themedBitmap = bitmap.getField("themedBitmap");
        unsupported = Class.forName("com.android.launcher3.icons.ThemedBitmap", false, loader).getField("NOT_SUPPORTED").get(null);
        assets = new DrawerMonetAssets(loader);
        module.deoptimize(bubble.getDeclaredMethod("applyIconAndLabel", item));
        module.deoptimize(item.getDeclaredMethod("newIcon", int.class, Context.class));
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
                Object previousItem = boundItem.get();
                boundItem.set(chain.getArg(0));
                try {
                    return inScope(binding, enabled && appInfo.isInstance(chain.getArg(0))
                            && isDrawer((View) chain.getThisObject()), chain::proceed);
                } finally { if (previousItem == null) boundItem.remove(); else boundItem.set(previousItem); }
            }));
            hooks.add(module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || !Boolean.TRUE.equals(binding.get())) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = ((Integer) args[1]) | 1;
                Object original = chain.getThisObject();
                if (Boolean.TRUE.equals(lowRes.invoke(original))) return chain.proceed();
                MaterialKey key = new MaterialKey(original, boundItem.get());
                Object nativeTheme = themedBitmap.get(original);
                if (nativeTheme != null && nativeTheme != unsupported) return chain.proceed(args);
                if (worker == null) return chain.proceed(args);
                Object copy;
                synchronized (copies) {
                    if (!copies.containsKey(key)) {
                        if (copies.size() >= 128) copies.remove(copies.keySet().iterator().next());
                        copies.put(key, null);
                        Object currentItem = boundItem.get();
                        int token = generation;
                        worker.post(() -> loadMaterial(currentItem, key, token));
                    }
                    copy = copies.get(key);
                }
                if (copy != null) {
                    Boolean previous = binding.get();
                    binding.set(false);
                    try { return create.invoke(copy, args); }
                    catch (ReflectiveOperationException | RuntimeException failure) { return chain.proceed(); }
                    finally { if (previous == null) binding.remove(); else binding.set(previous); }
                }
                return chain.proceed(args);
            }));
        } catch (Throwable failure) {
            for (var hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            throw failure;
        }
    }

    @FunctionalInterface interface ScopedCall { Object run() throws Throwable; }
    static Object inScope(ThreadLocal<Boolean> scope, boolean active, ScopedCall call) throws Throwable {
        Boolean previous = scope.get();
        scope.set(active);
        try { return call.run(); }
        finally { if (previous == null) scope.remove(); else scope.set(previous); }
    }

    private void loadMaterial(Object item, MaterialKey key, int token) {
        Object copy = null;
        try { if (enabled && item != null) copy = assets.build(context, item, key.bitmap()); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            android.util.Log.w("NcDrawerMonet", "native monochrome material unavailable", failure);
        }
        synchronized (copies) { if (generation != token || !copies.containsKey(key)) return; copies.put(key, copy); }
        if (copy != null) main.post(() -> rebind(key.bitmap()));
    }

    private void rebind(Object onlyBitmap) {
        List<View> views;
        synchronized (gridIcons) { views = new ArrayList<>(gridIcons.keySet()); }
        for (View view : views) {
            if (!isDrawer(view) || !appInfo.isInstance(view.getTag())) continue;
            try {
                if (onlyBitmap == null || itemBitmap.get(view.getTag()) == onlyBitmap)
                    standard.invoke(view, view.getTag());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                android.util.Log.w("NcDrawerMonet", "native drawer rebind failed", failure);
            }
        }
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
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (searchRecycler.isInstance(parent)) return false;
            if (recycler.isInstance(parent)) return true;
        }
        return false;
    }

    void connect(Context app) {
        context = app.getApplicationContext();
        HandlerThread thread = new HandlerThread("NcDrawerMonet"); thread.start();
        worker = new Handler(thread.getLooper());
        settings = HostSnapshot.open(context, "drawer_monet");
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { worker.post(DrawerMonet.this::reload); }
        }, new IntentFilter(ShadeStore.CHANGED), IconLayerStore.CONTROL, null, Context.RECEIVER_EXPORTED);
        worker.post(this::reload);
    }

    void artworkChanged() {
        main.post(() -> { synchronized (copies) { generation++; copies.clear(); } rebind(null); });
    }

    private void reload() {
        var read = settings.read(() -> context.getContentResolver().call(
                Uri.parse("content://" + ShadeStore.AUTHORITY), "drawer", null, null));
        // Unlike artwork snapshots, a failed opt-in read must leave the native renderer untouched.
        boolean next = read.live() && read.config() != null && read.config().getBoolean("drawerMonet", false);
        main.post(() -> {
            if (enabled == next) return;
            enabled = next;
            synchronized (copies) { generation++; copies.clear(); }
            rebind(null);
        });
        if (!read.live()) worker.postDelayed(this::reload, settings.retryDelay(3000));
    }
}
