package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.LruCache;
import android.util.SparseArray;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsIconTest {
    private static final String PKG = "com.example.artwork";
    private final Object pm = new Object();
    private Context context;
    private ApplicationInfo app;
    private IconAssets.Snapshot previous;
    private boolean previousReady, previousCover;
    private ThreadLocal<Boolean> original;

    @SuppressWarnings("unchecked")
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        app = app(PKG, 10001);
        previous = IconAssets.snapshot();
        previousReady = (boolean) assetField("ready").get(null);
        previousCover = (boolean) assetField("coverMonet").get(null);
        Field field = IconAssets.class.getDeclaredField("originalLoad"); field.setAccessible(true);
        original = (ThreadLocal<Boolean>) field.get(null);
        original.set(false);
        snapshot(new IconAssets.Snapshot(Map.of(), Map.of(), "", 0, 0, Set.of()));
    }

    @After public void cleanup() throws Exception {
        original.remove(); snapshot(previous);
        assetField("ready").set(null, previousReady); assetField("coverMonet").set(null, previousCover);
        State.sInstance = null; Cache.sAppIconCacheManager = null;
    }

    @Test public void realFrameworkPreflightAndPackageManagerContextAreUsable() throws Exception {
        SettingsIconAccess access = new SettingsIconAccess(context.getClassLoader());
        assertEquals("loadUnbadgedItemIcon", access.loadIcon.getName());
        Context pmContext = access.pmContext(context.getPackageManager());
        assertNotNull(pmContext);
        assertEquals(android.os.Process.myUid() / 100000, access.user(pmContext));
        assertTrue(access.applicationIcon(app));
    }

    @Test public void onlySettingsMainProcessAndPackageAreEligible() {
        assertTrue(SettingsIconAccess.eligible(false, "com.android.settings", "com.android.settings"));
        assertFalse(SettingsIconAccess.eligible(true, "com.android.settings", "com.android.settings"));
        assertFalse(SettingsIconAccess.eligible(false, "com.android.settings:remote", "com.android.settings"));
        assertFalse(SettingsIconAccess.eligible(false, "com.android.settings", "com.example.settings"));
    }

    @Test public void uidPackageAndBothUserContextsMustAgree() {
        assertTrue(accept(app, app, 0, null));
        assertFalse(accept(app, app, 10, null));
        assertFalse(accept(app, app(PKG, 1010001), 0, null));
        assertFalse(accept(app(PKG, 10002), app, 0, null));
        assertFalse(accept(app("com.example.other", 10001), app, 0, null));
        assertFalse(accept(new PackageItemInfo(), app, 0, null));
        assertFalse(accept(app, null, 0, null));
        var valid = new SettingsIconAccess.FactoryCall(pm, app, app, 0, true);
        assertTrue(accept(app, app, 0, valid));
        assertFalse(accept(app, app, 0, new SettingsIconAccess.FactoryCall(pm, app, app, 10, true)));
        assertFalse(accept(app, app, 0, new SettingsIconAccess.FactoryCall(pm, app, app, 0, false)));
        assertFalse(accept(app, app, 0, new SettingsIconAccess.FactoryCall(new Object(), app, app, 0, true)));
        assertFalse(accept(app, app, 0, new SettingsIconAccess.FactoryCall(pm, new ApplicationInfo(app), app, 0, true)));
    }

    @Test public void disabledNativeMissingAndOriginalLoadAlwaysKeepNative() throws Exception {
        assertNull(replacement()); // initial asynchronous snapshot is not loaded yet
        Bitmap bg = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); bg.eraseColor(Color.BLUE);
        Bitmap fg = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        snapshot(new IconAssets.Snapshot(Map.of(PKG, new IconAssets.Entry(bg, fg, null, null, true, false, false)), Map.of(), "", 0, 0, Set.of()));
        assertTrue(replacement() instanceof ImportedIcon);
        original.set(true);
        try { assertNull(replacement()); } finally { original.set(false); }
        assertNotNull(replacement());
        snapshot(new IconAssets.Snapshot(Map.of(PKG, new IconAssets.Entry(bg, fg, null, null, false, true, true)), Map.of(), "", 0, 0, Set.of()));
        assertNull(replacement()); // mono-only / native-color choices do not replace the base
        IconAssets.load(context, new Bundle(), () -> false);
        assertNull(replacement()); // disabled snapshot restores the native loader
    }

    @Test public void replacementIsOnlyBaseArtworkSoNativeBadgeRemainsOnTop() throws Exception {
        Bitmap bg = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); bg.eraseColor(Color.BLUE);
        Bitmap fg = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        snapshot(new IconAssets.Snapshot(Map.of(PKG, new IconAssets.Entry(bg, fg, null, null, true, false, false)), Map.of(), "", 0, 0, Set.of()));
        Drawable base = replacement(); assertTrue(base instanceof ImportedIcon);
        // The production factory always proceeds: its later badge wraps this base, not vice versa.
        LayerDrawable nativeResult = new LayerDrawable(new Drawable[]{base, new ColorDrawable(Color.RED)});
        nativeResult.setLayerInset(1, 48, 48, 0, 0);
        Bitmap output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        nativeResult.setBounds(0, 0, 64, 64); nativeResult.draw(new Canvas(output));
        assertEquals(Color.BLUE, output.getPixel(32, 32));
        assertEquals(Color.RED, output.getPixel(60, 60));
    }

    @Test public void nativeCacheInvalidationTouchesOnlyExistingCurrentUserEntries() throws Exception {
        SettingsIconAccess.NativeCache access = new SettingsIconAccess.NativeCache(State.class, Entry.class, Cache.class);
        access.invalidate(0); assertNull(State.sInstance); assertNull(Cache.sAppIconCacheManager);
        State.sInstance = new State(); Cache.sAppIconCacheManager = new Cache();
        Entry own = new Entry(app), other = new Entry(app(PKG, 1010001));
        State.sInstance.mAppEntries.add(own); State.sInstance.mAppEntries.add(other);
        Cache.sAppIconCacheManager.mDrawableCache.put(Cache.getKey(PKG, own.info.uid), own.icon);
        Cache.sAppIconCacheManager.mDrawableCache.put(Cache.getKey(PKG, other.info.uid), other.icon);
        Drawable otherIcon = other.icon;
        access.invalidate(0);
        assertNull(own.icon); assertSame(otherIcon, other.icon);
        assertNull(Cache.sAppIconCacheManager.mDrawableCache.get(Cache.getKey(PKG, own.info.uid)));
        assertSame(otherIcon, Cache.sAppIconCacheManager.mDrawableCache.get(Cache.getKey(PKG, other.info.uid)));
    }

    private boolean accept(PackageItemInfo item, ApplicationInfo info, int user, SettingsIconAccess.FactoryCall call) {
        return SettingsIconAccess.accepts(item, info, user, 0, pm, call);
    }
    private Drawable replacement() { return SettingsIconAccess.replacement(context, app, app, 0, 0, pm, null); }
    private static ApplicationInfo app(String pkg, int uid) {
        ApplicationInfo app = new ApplicationInfo(); app.packageName = pkg; app.uid = uid; return app;
    }
    private static void snapshot(IconAssets.Snapshot value) throws Exception { assetField("snapshot").set(null, value); }
    private static Field assetField(String name) throws Exception {
        Field field = IconAssets.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static final class State {
        private static State sInstance;
        private final SparseArray<Object> mEntriesMap = new SparseArray<>();
        private final ArrayList<Entry> mAppEntries = new ArrayList<>();
    }
    private static final class Entry {
        private final ApplicationInfo info;
        private Drawable icon = new ColorDrawable(Color.GREEN);
        Entry(ApplicationInfo info) { this.info = info; }
    }
    private static final class Cache {
        private static Cache sAppIconCacheManager;
        private final LruCache<String, Drawable> mDrawableCache = new LruCache<>(10);
        private static String getKey(String pkg, int uid) { return pkg + ":" + uid / 100000; }
    }
}
