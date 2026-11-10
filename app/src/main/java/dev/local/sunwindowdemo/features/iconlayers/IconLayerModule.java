package dev.local.sunwindowdemo.features.iconlayers;

import android.app.Application;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import dev.local.sunwindowdemo.BuildConfig;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Separate entry: no system_server hooks, resource-ID marking or window-engine changes. */
public final class IconLayerModule extends XposedModule {
    private boolean eligible, installed;
    private record Icons(int mode, Map<String, Bitmap[]> imported) {}
    private volatile Icons icons = new Icons(0, Map.of());
    private String cachedGeneration = "", acknowledgedGeneration = null;
    private Map<String, Bitmap[]> cached = Map.of();
    private final AtomicInteger epoch = new AtomicInteger();
    private final AtomicBoolean fullRefresh = new AtomicBoolean();
    private int loadFailures;
    private volatile Context context;
    private volatile Resources resources;
    private Handler worker;
    private ClassLoader loader;
    private BroadcastReceiver receiver;
    private final AtomicInteger replacements = new AtomicInteger(), animations = new AtomicInteger();
    private String lastError = "";

    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        eligible = !p.isSystemServer() && IconLayerStore.LAUNCHER.equals(p.getProcessName());
    }

    @Override public synchronized void onPackageLoaded(PackageLoadedParam p) {
        if (!eligible || installed || !IconLayerStore.LAUNCHER.equals(p.getPackageName())) return;
        installed = true;
        loader = p.getDefaultClassLoader();
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            Class<?> provider = Class.forName("com.android.launcher3.icons.IconProvider", false, loader);
            Method icon = provider.getDeclaredMethod("getIcon", PackageItemInfo.class, ApplicationInfo.class, int.class);
            if (icon.getReturnType() != Drawable.class) throw new NoSuchMethodException("IconProvider.getIcon");
            handles.add(hook(icon).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                PackageItemInfo info = (PackageItemInfo) chain.getArg(0);
                ApplicationInfo app = (ApplicationInfo) chain.getArg(1);
                if (info != null && (app == null || UserHandle.getUserHandleForUid(app.uid).equals(android.os.Process.myUserHandle()))) {
                    Drawable replacement = replacement(info.packageName, "IconProvider");
                    if (replacement != null) return replacement;
                }
                return chain.proceed();
            }));
            for (String name : new String[]{"getIcon", "getBadgedIcon"}) {
                Method method = LauncherActivityInfo.class.getDeclaredMethod(name, int.class);
                handles.add(hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    LauncherActivityInfo info = (LauncherActivityInfo) chain.getThisObject();
                    if (info.getUser().equals(android.os.Process.myUserHandle())) {
                        Drawable replacement = replacement(info.getComponentName().getPackageName(), "LauncherActivityInfo");
                        if (replacement != null) return replacement;
                    }
                    return chain.proceed();
                }));
            }
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            handles.add(hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try { connect((Context) chain.getThisObject()); }
                catch (RuntimeException failure) { note("CONNECT_FAILED " + failure.getClass().getSimpleName()); }
                return result;
            }));
            try { installAnimationProbe(handles); }
            catch (Exception failure) { lastError = "动画观察入口：" + failure.getClass().getSimpleName(); note("ANIMATION_PROBE_UNAVAILABLE " + failure.getClass().getSimpleName()); }
            note("READY version=" + BuildConfig.VERSION_NAME + " hooks=" + handles.size() + " target=" + IconLayerStore.TARGET);
        } catch (Throwable failure) {
            for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (Throwable ignored) {}
            note("DISABLED " + failure.getClass().getSimpleName());
        }
    }

    private void installAnimationProbe(List<XposedInterface.HookHandle> handles) throws Exception {
        Class<?> view = Class.forName("com.android.launcher3.views.FloatingIconView", false, loader);
        Class<?> item = Class.forName("com.android.launcher3.model.data.ItemInfo", false, loader);
        Field loadResult = view.getDeclaredField("mIconLoadResult"); loadResult.setAccessible(true);
        Field info = loadResult.getType().getDeclaredField("itemInfo"); info.setAccessible(true);
        Method component = item.getDeclaredMethod("getTargetComponent"); component.setAccessible(true);
        Field user = item.getDeclaredField("user"); user.setAccessible(true);
        Field clip = view.getDeclaredField("mClipIconView"); clip.setAccessible(true);
        Field background = clip.getType().getDeclaredField("mBackground"); background.setAccessible(true);
        Field foreground = clip.getType().getDeclaredField("mForeground"); foreground.setAccessible(true);
        Method setIcon = view.getDeclaredMethod("setIcon", Drawable.class, Drawable.class, Supplier.class, int.class, boolean.class);
        handles.add(hook(setIcon).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            AdaptiveIconDrawable test = null;
            try {
                Object result = loadResult.get(chain.getThisObject());
                Object itemInfo = result == null ? null : info.get(result);
                ComponentName target = itemInfo == null ? null : (ComponentName) component.invoke(itemInfo);
                if (target != null && android.os.Process.myUserHandle().equals(user.get(itemInfo)))
                    test = replacement(target.getPackageName(), "FloatingIconView") instanceof AdaptiveIconDrawable icon ? icon : null;
            } catch (Exception failure) { note("ANIMATION_IDENTIFY " + failure.getClass().getSimpleName()); }
            Object result;
            if (test == null) return chain.proceed();
            Object[] args = chain.getArgs().toArray(); args[0] = test;
            result = chain.proceed(args);
            // This checks actual fields written by native setIcon, not just our return value.
            Object nativeClip = clip.get(chain.getThisObject());
            boolean separate = background.get(nativeClip) == test.getBackground() && foreground.get(nativeClip) == test.getForeground()
                && test.getBackground() != test.getForeground();
            if (separate) animations.incrementAndGet();
            note("ANIMATION_LAYERS separate=" + separate);
            report(separate ? "桌面动画已分别接收前景与背景" : "动画图层需要核对");
            return result;
        }));
    }

    private Drawable replacement(String pkg, String source) {
        if (pkg == null) return null;
        Icons snapshot = icons;
        Resources r = resources;
        Bitmap[] pair = snapshot.imported.get(pkg);
        boolean demo = IconLayerStore.TARGET.equals(pkg) && snapshot.mode != 0;
        if (r == null || (pair == null && !demo)) return null;
        int n = replacements.incrementAndGet();
        if (n <= 6) { note("REPLACED source=" + source + " package=" + pkg); report("已加载双层图标"); }
        try { return pair != null ? new ImportedIcon(r, pair[0], pair[1]) : new LayeredTestIcon(r, snapshot.mode); }
        catch (RuntimeException failure) { lastError = "素材：" + failure.getClass().getSimpleName(); note("ASSET_FAILED " + failure.getClass().getSimpleName()); return null; }
    }

    private synchronized void connect(Context app) {
        if (context != null) return;
        context = app;
        HandlerThread thread = new HandlerThread("NcIconLayers"); thread.start();
        worker = new Handler(thread.getLooper());
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getBooleanExtra("all", false)) fullRefresh.set(true);
                queueReload();
            }
        };
        app.registerReceiver(receiver, new IntentFilter(IconLayerStore.REFRESH), IconLayerStore.CONTROL, null, Context.RECEIVER_EXPORTED);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED); packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED); packages.addDataScheme("package");
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { queueReload(); }
        }, packages, Context.RECEIVER_NOT_EXPORTED);
        queueReload();
    }

    private void queueReload() { int request = epoch.incrementAndGet(); worker.post(() -> reload(request)); }

    private void reload(int request) {
        if (request != epoch.get()) return;
        try {
            Bundle config = context.getContentResolver().call(IconLayerStore.URI, "read", null, null);
            if (config == null) throw new IllegalStateException("no config");
            if (resources == null) resources = context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID);
            String generation = config.getString("generation", "");
            boolean enabled = config.getBoolean("enabled") && !generation.isEmpty();
            Set<String> packages = new HashSet<>(config.getStringArrayList("packages") == null ? List.of() : config.getStringArrayList("packages"));
            Set<String> installed = new HashSet<>(), allInstalled = new HashSet<>();
            for (var info : context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
                allInstalled.add(info.activityInfo.packageName);
            installed.addAll(allInstalled); installed.retainAll(packages);
            Map<String, Bitmap[]> decoded = new HashMap<>(); int failed = 0;
            // Bound total layer pixels to 48 MiB. Keep the full canvas; only downsample resolution.
            int side = Math.max(16, Math.min(256, (int)Math.sqrt((48L * 1024 * 1024) / (8.0 * Math.max(1, installed.size())))));
            if (enabled) for (String pkg : installed) {
                if (request != epoch.get()) return;
                Bitmap[] pair = generation.equals(cachedGeneration) ? cached.get(pkg) : null;
                if (pair == null || Math.max(Math.max(pair[0].getWidth(), pair[0].getHeight()), Math.max(pair[1].getWidth(), pair[1].getHeight())) > side) {
                    try { pair = new Bitmap[]{decode(generation, pkg, 0, side), decode(generation, pkg, 1, side)}; }
                    catch (Exception invalid) { failed++; note("LOAD_FAILED package=" + pkg); continue; }
                }
                decoded.put(pkg, pair);
            }
            if (request != epoch.get()) return;
            if (enabled) { cachedGeneration = generation; cached = Map.copyOf(decoded); }
            else { cachedGeneration = ""; cached = Map.of(); }
            Set<String> originals = new HashSet<>(config.getStringArrayList("originals") == null ? List.of() : config.getStringArrayList("originals"));
            decoded.keySet().removeAll(originals);
            Icons previous = icons;
            int selected = config.getInt("mode");
            icons = new Icons(selected >= 0 && selected <= 2 ? selected : 0, Map.copyOf(decoded));
            loadFailures = failed;
            acknowledgedGeneration = null;
            lastError = "";
            Set<String> changed = new HashSet<>(previous.imported.keySet());
            changed.addAll(decoded.keySet());
            // Also invalidate persisted launcher cache on process start, disable, and removal.
            changed.addAll(installed);
            if (config.getStringArrayList("restore") != null) changed.addAll(config.getStringArrayList("restore"));
            if (fullRefresh.getAndSet(false)) changed.addAll(allInstalled);
            if (previous.mode != 0 || icons.mode != 0) changed.add(IconLayerStore.TARGET);
            boolean refreshed = refreshTargets(changed, generation);
            if (refreshed) report(enabled ? "桌面已连接；图标库已加载" : "桌面已连接；导入图标已关闭");
        } catch (Exception failure) {
            lastError = failure.getClass().getSimpleName();
            note("CONFIG_FAILED " + lastError);
            report("图标设置读取失败");
        }
    }

    private Bitmap decode(String generation, String pkg, int layer, int side) throws Exception {
        Uri uri = IconLayerStore.URI.buildUpon().path("layers/" + generation + "/" + pkg + "/" + layer + ".png").build();
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 2048 || options.outHeight > 2048) throw new IllegalArgumentException("image bounds");
        options.inSampleSize = 1;
        while ((Math.max(options.outWidth, options.outHeight) + options.inSampleSize - 1) / options.inSampleSize > side) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IllegalStateException("image decode");
            return bitmap;
        }
    }

    private boolean refreshTargets(Set<String> packages, String generation) {
        if (packages.isEmpty()) { acknowledgedGeneration = generation; return true; }
        try {
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherAppState", false, loader);
            Object singleton = stateClass.getField("INSTANCE").get(null);
            Object state = singleton.getClass().getMethod("get", Context.class).invoke(singleton, context);
            Object model = stateClass.getField("model").get(state);
            Object cache = stateClass.getField("iconCache").get(state);
            Class<?> base = Class.forName("com.android.launcher3.icons.cache.BaseIconCache", false, loader);
            Field workerField = base.getDeclaredField("workerHandler"); workerField.setAccessible(true);
            Handler cacheWorker = (Handler) workerField.get(cache);
            Method remove = base.getDeclaredMethod("removeIconsForPkg", UserHandle.class, String.class); remove.setAccessible(true);
            Object callbacks = model.getClass().getMethod("newModelCallbacks").invoke(model);
            Method changed = callbacks.getClass().getMethod("onPackageChanged", String.class, UserHandle.class);
            cacheWorker.post(() -> {
                boolean success = true;
                for (String pkg : packages) try {
                    remove.invoke(cache, android.os.Process.myUserHandle(), pkg);
                    changed.invoke(callbacks, pkg, android.os.Process.myUserHandle());
                } catch (Exception failure) { success = false; note("PACKAGE_REFRESH_FAILED package=" + pkg); }
                final boolean ok = success;
                worker.post(() -> {
                    if (ok) { acknowledgedGeneration = generation; report("图标刷新已提交，桌面正在重绘"); }
                    else { lastError = "部分图标刷新失败"; report(lastError); }
                });
            });
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Class<?> floating = Class.forName("com.android.launcher3.views.FloatingIconView", false, loader);
                    Field cached = floating.getDeclaredField("sIconLoadResult"); cached.setAccessible(true); cached.set(null, null);
                } catch (Exception failure) { note("PREFETCH_RESET_UNAVAILABLE " + failure.getClass().getSimpleName()); }
            });
            note("REFRESH_QUEUED count=" + packages.size());
            return true;
        } catch (Exception failure) {
            lastError = "刷新：" + failure.getClass().getSimpleName();
            note("REFRESH_FAILED " + failure.getClass().getSimpleName());
            report("刷新失败，请重启桌面后查看");
            return false;
        }
    }

    private void report(String status) {
        if (worker == null || context == null) return;
        worker.post(() -> {
            try {
                Bundle b = new Bundle(); b.putString("version", BuildConfig.VERSION_NAME);
                b.putString("status", status); b.putString("error", lastError);
                b.putInt("replacements", replacements.get()); b.putInt("animations", animations.get());
                b.putInt("loaded", icons.imported.size()); b.putInt("loadFailures", loadFailures);
                if (acknowledgedGeneration != null) b.putString("loadedGeneration", acknowledgedGeneration);
                context.getContentResolver().call(IconLayerStore.URI, "report", null, b);
            } catch (RuntimeException ignored) { note("REPORT_UNAVAILABLE"); }
        });
    }
    private void note(String s) { try { log(4, "NcIconLayers", s); } catch (Throwable ignored) {} }
}
