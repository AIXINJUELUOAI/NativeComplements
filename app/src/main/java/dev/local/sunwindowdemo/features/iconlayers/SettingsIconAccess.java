package dev.local.sunwindowdemo.features.iconlayers;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.LruCache;
import android.util.SparseArray;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Settings' native unbadged artwork boundary, not a replacement badge renderer. */
final class SettingsIconAccess {
    final Method attach, factoryIcon, loadIcon;
    private final Method contextUser;
    private final Field factoryContext, factoryPm, pmContext, showUserIcon;
    private final int noUserIcon;

    record FactoryCall(Object pm, PackageItemInfo item, ApplicationInfo app, int user,
                       boolean trusted) {}

    SettingsIconAccess(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> factory = Class.forName("android.util.IconDrawableFactory", false, loader);
        Class<?> pm = Class.forName("android.app.ApplicationPackageManager", false, loader);
        if (!PackageManager.class.isAssignableFrom(pm)) throw new NoSuchFieldException("PackageManager type");
        factoryIcon = method(factory, "getBadgedIcon", Drawable.class,
                PackageItemInfo.class, ApplicationInfo.class, int.class);
        loadIcon = method(pm, "loadUnbadgedItemIcon", Drawable.class,
                PackageItemInfo.class, ApplicationInfo.class);
        attach = method(Application.class, "attach", void.class, Context.class);
        contextUser = method(Context.class, "getUserId", int.class);
        factoryContext = field(factory, "mContext", Context.class);
        factoryPm = field(factory, "mPm", PackageManager.class);
        pmContext = field(pm, "mContext", Context.class);
        showUserIcon = field(PackageItemInfo.class, "showUserIcon", int.class);
        Field absent = UserHandle.class.getDeclaredField("USER_NULL");
        if (absent.getType() != int.class || !Modifier.isStatic(absent.getModifiers()))
            throw new NoSuchFieldException("USER_NULL type");
        absent.setAccessible(true);
        noUserIcon = absent.getInt(null);
    }

    int user(Context context) throws ReflectiveOperationException {
        return (int) contextUser.invoke(context);
    }

    Context pmContext(Object pm) throws IllegalAccessException {
        return (Context) pmContext.get(pm);
    }

    FactoryCall factoryCall(Object factory, PackageItemInfo item, ApplicationInfo app, int user,
                            int processUser) throws ReflectiveOperationException {
        Context context = (Context) factoryContext.get(factory);
        return new FactoryCall(factoryPm.get(factory), item, app, user,
                context != null && user(context) == processUser && user == processUser);
    }

    boolean applicationIcon(PackageItemInfo item) throws IllegalAccessException {
        // A user avatar is not application artwork, even if it carries an application package.
        return item instanceof ApplicationInfo && showUserIcon.getInt(item) == noUserIcon;
    }

    static boolean eligible(boolean systemServer, String process, String pkg) {
        return !systemServer && IconLayerStore.SETTINGS.equals(process) && IconLayerStore.SETTINGS.equals(pkg);
    }

    static boolean accepts(PackageItemInfo item, ApplicationInfo app, int pmUser, int processUser,
                           Object pm, FactoryCall call) {
        if (!(item instanceof ApplicationInfo target) || app == null || processUser < 0
                || pmUser != processUser || app.uid < 0 || target.uid != app.uid
                || app.packageName == null || app.packageName.isEmpty()
                || !app.packageName.equals(target.packageName)
                || app.uid / 100000 != processUser
                || !UserHandle.getUserHandleForUid(app.uid).equals(android.os.Process.myUserHandle())) return false;
        // A rejected explicit factory user must not fall through to the direct PM route.
        return call == null || call.trusted && call.user == processUser && call.pm == pm
                && call.item == item && call.app == app;
    }

    static Drawable replacement(Context context, PackageItemInfo item, ApplicationInfo app,
                                int pmUser, int processUser, Object pm, FactoryCall call) {
        if (IconAssets.loadingOriginal() || context == null
                || !accepts(item, app, pmUser, processUser, pm, call)) return null;
        return IconAssets.colored(context, app.packageName);
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException(name + " type");
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!type.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers()))
            throw new NoSuchFieldException(name + " type");
        field.setAccessible(true);
        return field;
    }

    /** Optional native cache invalidation. It never creates an ApplicationsState or restarts Settings. */
    static final class NativeCache {
        private final Field instance, entriesLock, entries, info, icon, cacheInstance, drawables;
        private final Method cacheKey;

        NativeCache(ClassLoader loader) throws ReflectiveOperationException {
            this(Class.forName("com.android.settingslib.applications.ApplicationsState", false, loader),
                    Class.forName("com.android.settingslib.applications.ApplicationsState$AppEntry", false, loader),
                    Class.forName("com.android.settingslib.applications.AppIconCacheManager", false, loader));
        }

        NativeCache(Class<?> state, Class<?> entry, Class<?> cache) throws ReflectiveOperationException {
            instance = staticField(state, "sInstance", state);
            entriesLock = field(state, "mEntriesMap", SparseArray.class);
            entries = field(state, "mAppEntries", ArrayList.class);
            info = field(entry, "info", ApplicationInfo.class);
            icon = field(entry, "icon", Drawable.class);
            cacheInstance = staticField(cache, "sAppIconCacheManager", cache);
            drawables = field(cache, "mDrawableCache", LruCache.class);
            cacheKey = cache.getDeclaredMethod("getKey", String.class, int.class);
            if (cacheKey.getReturnType() != String.class || !Modifier.isStatic(cacheKey.getModifiers()))
                throw new NoSuchMethodException("AppIconCacheManager.getKey type");
            cacheKey.setAccessible(true);
        }

        @SuppressWarnings("unchecked")
        void invalidate(int processUser) throws ReflectiveOperationException {
            Object state = instance.get(null);
            if (state == null) return;
            Object manager = cacheInstance.get(null);
            LruCache<String, Drawable> cache = manager == null ? null
                    : (LruCache<String, Drawable>) drawables.get(manager);
            synchronized (entriesLock.get(state)) {
                for (Object entry : (ArrayList<?>) entries.get(state)) {
                    synchronized (entry) {
                        ApplicationInfo app = (ApplicationInfo) info.get(entry);
                        if (app == null || app.uid < 0 || app.uid / 100000 != processUser
                                || !UserHandle.getUserHandleForUid(app.uid).equals(android.os.Process.myUserHandle())) continue;
                        icon.set(entry, null);
                        if (cache != null) cache.remove((String) cacheKey.invoke(null, app.packageName, app.uid));
                    }
                }
            }
        }

        private static Field staticField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type || !Modifier.isStatic(field.getModifiers()))
                throw new NoSuchFieldException(name + " type");
            field.setAccessible(true);
            return field;
        }
    }
}
