package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.Process;
import dev.local.sunwindowdemo.features.shade.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DrawerMonetTest {
    private final Context context = RuntimeEnvironment.getApplication();
    @Before public void clear() { ShadeStore.preferences(context).edit().clear().commit(); }
    @After public void uid() { ShadowBinder.setCallingUid(Process.myUid()); }

    @Test public void defaultOffTogglePersistsAndDoesNotEnableOtherFeatures() {
        assertFalse(ShadeStore.drawerMonet(context));
        ShadeStore.setDrawerMonet(context, true);
        assertTrue(ShadeStore.drawerMonet(context.createDeviceProtectedStorageContext()));
        assertTrue(ShadeStore.read(context).getBoolean("drawerMonet"));
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        ShadeStore.setDrawerMonet(context, false);
        assertFalse(ShadeStore.drawerMonet(context));
        var intents = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        assertEquals(ShadeStore.CHANGED, intents.get(intents.size()-1).getAction());
        assertEquals(IconLayerStore.LAUNCHER, intents.get(intents.size()-1).getPackage());
    }

    @Test public void launcherGetsOnlyProjectionAndUnknownUidCannotReadOrWrite() {
        PackageInfo launcher = new PackageInfo();
        launcher.packageName = IconLayerStore.LAUNCHER;
        launcher.applicationInfo = new ApplicationInfo();
        launcher.applicationInfo.packageName = launcher.packageName;
        launcher.applicationInfo.uid = 10042;
        Shadows.shadowOf(context.getPackageManager()).installPackage(launcher);
        ShadeProvider provider = new ShadeProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = ShadeStore.AUTHORITY;
        provider.attachInfo(context, info);
        ShadeStore.setDrawerMonet(context, true);
        ShadowBinder.setCallingUid(10042);
        var result = provider.call("drawer", null, null);
        assertEquals(1, result.size()); assertTrue(result.getBoolean("drawerMonet"));
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
        assertThrows(SecurityException.class, () -> provider.call("write", null, null));
        ShadowBinder.setCallingUid(12345);
        assertThrows(SecurityException.class, () -> provider.call("drawer", null, null));
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        assertTrue(provider.call("drawer", null, null).getBoolean("drawerMonet"));
        assertThrows(IllegalArgumentException.class, () -> provider.call("write", null, null));
    }

    @Test public void nestedScopesAndExceptionsRestorePriorBinding() throws Throwable {
        ThreadLocal<Boolean> scope = new ThreadLocal<>();
        assertNull(scope.get());
        DrawerMonet.inScope(scope, true, () -> {
            assertTrue(scope.get());
            try {
                DrawerMonet.inScope(scope, false, () -> { assertFalse(scope.get()); throw new IllegalStateException(); });
                fail();
            } catch (IllegalStateException expected) { assertTrue(scope.get()); }
            return null;
        });
        assertNull(scope.get());
        scope.set(false);
        try { DrawerMonet.inScope(scope, true, () -> { throw new IllegalStateException(); }); }
        catch (IllegalStateException expected) { assertFalse(scope.get()); }
    }

    static class CurrentItem { public void newIcon(Context context, int flags) {} }
    static class ArchivedItem { public void newIcon(int flags, Context context) {} }
    @Test public void currentAndArchivedItemIconArgumentOrdersAreSupported() throws Exception {
        assertArrayEquals(new Class<?>[]{Context.class, int.class},
                DrawerMonet.itemNewIcon(CurrentItem.class).getParameterTypes());
        assertArrayEquals(new Class<?>[]{int.class, Context.class},
                DrawerMonet.itemNewIcon(ArchivedItem.class).getParameterTypes());
        assertThrows(NoSuchMethodException.class, () -> DrawerMonet.itemNewIcon(Object.class));
    }

    @Test public void attachBeforeApplicationPublicationUsesAttachedContext() {
        Context early = new android.content.ContextWrapper(context) {
            @Override public Context getApplicationContext() { return null; }
        };
        assertSame(early, DrawerMonet.attachedContext(early));
        assertNotNull(dev.local.sunwindowdemo.core.HostSnapshot.open(
                DrawerMonet.attachedContext(early), "drawer_monet"));
        assertSame(context, DrawerMonet.attachedContext(context));
    }

    public static class Grid extends android.widget.FrameLayout {
        public Grid(Context c) { super(c); }
    }
    public static class SearchGrid extends Grid {
        public SearchGrid(Context c) { super(c); }
    }
    @Test public void onlyRegisteredGridViewsQualifyBeforeAndAfterAttachment() throws Exception {
        DrawerMonet drawer = new DrawerMonet();
        var recycler = DrawerMonet.class.getDeclaredField("recycler"); recycler.setAccessible(true);
        recycler.set(drawer, Grid.class);
        var search = DrawerMonet.class.getDeclaredField("searchRecycler"); search.setAccessible(true);
        search.set(drawer, SearchGrid.class);
        var registered = DrawerMonet.class.getDeclaredField("gridIcons"); registered.setAccessible(true);
        var map = (java.util.Map<android.view.View, java.lang.ref.WeakReference<android.view.View>>) registered.get(drawer);
        var qualifies = DrawerMonet.class.getDeclaredMethod("isDrawer", android.view.View.class); qualifies.setAccessible(true);
        Grid grid = new Grid(context);
        var icon = new android.view.View(context);
        assertFalse((Boolean) qualifies.invoke(drawer, icon));
        map.put(icon, new java.lang.ref.WeakReference<>(grid));
        assertTrue((Boolean) qualifies.invoke(drawer, icon));
        grid.addView(icon);
        assertTrue((Boolean) qualifies.invoke(drawer, icon));
        grid.removeView(icon);
        SearchGrid results = new SearchGrid(context); results.addView(icon);
        assertFalse((Boolean) qualifies.invoke(drawer, icon));
        results.removeView(icon);
        new android.widget.FrameLayout(context).addView(icon);
        assertFalse((Boolean) qualifies.invoke(drawer, icon));
    }

    @Test public void adaptiveArtworkIsNotRerasterizedOrRescaledByModule() {
        var adaptive = new AdaptiveIconDrawable(new ColorDrawable(Color.RED),
                new ColorDrawable(Color.BLUE), new ColorDrawable(Color.WHITE));
        assertSame(adaptive, DrawerMonetAssets.adaptiveSource(adaptive));
        assertNotNull(DrawerMonetAssets.adaptiveSource(new ColorDrawable(Color.RED)));
        assertNull(DrawerMonetAssets.adaptiveSource(null));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void oversizedMonoGetsSafePaddingWhileCorrectPaddingIsPreserved() {
        int size=96;
        var full=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE),new ColorDrawable(Color.WHITE));
        var safe=DrawerMonetAssets.safeMonochrome(context,full,size);
        assertNotSame(full,safe);
        var pixels=IconImages.render(safe.getMonochrome(),size);
        assertTrue(IconImages.contentBounds(pixels).width()<=size*.62f);
        assertSame(safe,DrawerMonetAssets.safeMonochrome(context,safe,size));
        assertNotNull(full.getMonochrome());
        var corner=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888);
        var canvas=new android.graphics.Canvas(corner);var paint=new android.graphics.Paint();paint.setColor(Color.WHITE);
        canvas.drawRect(0,0,48,48,paint);
        var offcenter=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE),new BitmapDrawable(context.getResources(),corner));
        var centered=DrawerMonetAssets.safeMonochrome(context,offcenter,size);
        assertNotSame(offcenter,centered);
        var bounds=IconImages.contentBounds(IconImages.render(centered.getMonochrome(),size));
        assertTrue(bounds.left>=size*.18f&&bounds.top>=size*.18f);
    }

    @Test public void drawerOnlyUsesModuleArtworkWhenMonochromeChannelChanged() throws Exception {
        var field=IconAssets.class.getDeclaredField("snapshot");field.setAccessible(true);Object old=field.get(null);
        var bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
        var original=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE));
        try {
            var colorOnly=new IconAssets.Entry(bitmap,bitmap,null,null,true,false,false);
            field.set(null,new IconAssets.Snapshot(java.util.Map.of("test.app",colorOnly),"",0,0,java.util.Set.of()));
            assertNull(IconAssets.drawerMonochrome(context,"test.app",original));
            var mono=new IconAssets.Entry(bitmap,bitmap,bitmap,null,true,true,false);
            field.set(null,new IconAssets.Snapshot(java.util.Map.of("test.app",mono),"",0,0,java.util.Set.of()));
            assertNotNull(IconAssets.drawerMonochrome(context,"test.app",original));
            var nativeMono=new IconAssets.Entry(bitmap,bitmap,bitmap,null,false,true,true);
            field.set(null,new IconAssets.Snapshot(java.util.Map.of("test.app",nativeMono),"",0,0,java.util.Set.of()));
            var animated=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE)) {};
            assertNotNull(((AdaptiveIconDrawable)IconAssets.drawerMonochrome(context,"test.app",animated)).getMonochrome());
            assertNull(animated.getMonochrome());
        } finally {field.set(null,old);}
    }

    public static class Predictions extends android.widget.LinearLayout {
        public Predictions(Context c) { super(c); }
    }
    @Test public void recommendationWorkspaceItemsQualifyButOrdinaryWorkspaceAndSearchDoNot() throws Exception {
        DrawerMonet drawer = new DrawerMonet();
        for (var pair : new Object[][]{{"recycler",Grid.class},{"searchRecycler",SearchGrid.class},
                {"predictionRow",Predictions.class},{"appInfo",String.class}}) {
            var field=DrawerMonet.class.getDeclaredField((String)pair[0]);field.setAccessible(true);field.set(drawer,pair[1]);
        }
        var field=DrawerMonet.class.getDeclaredField("gridIcons");field.setAccessible(true);
        var map=(java.util.Map<android.view.View,java.lang.ref.WeakReference<android.view.View>>)field.get(drawer);
        var eligible=DrawerMonet.class.getDeclaredMethod("eligibleItem",android.view.View.class,Object.class);eligible.setAccessible(true);
        Predictions row=new Predictions(context);var icon=new android.view.View(context);row.addView(icon);
        map.put(icon,new java.lang.ref.WeakReference<>(row));Object workspaceItem=new Object();
        assertTrue((Boolean)eligible.invoke(drawer,icon,workspaceItem));
        SearchGrid outerSearch=new SearchGrid(context);outerSearch.addView(row);
        assertFalse((Boolean)eligible.invoke(drawer,icon,workspaceItem));
        outerSearch.removeView(row);
        row.removeView(icon);new android.widget.FrameLayout(context).addView(icon);
        assertFalse((Boolean)eligible.invoke(drawer,icon,workspaceItem));
        ((android.view.ViewGroup)icon.getParent()).removeView(icon);new SearchGrid(context).addView(icon);
        assertFalse((Boolean)eligible.invoke(drawer,icon,workspaceItem));
    }
}
