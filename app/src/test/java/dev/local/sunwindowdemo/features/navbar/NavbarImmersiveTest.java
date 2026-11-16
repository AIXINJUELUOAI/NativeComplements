package dev.local.sunwindowdemo.features.navbar;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Looper;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class NavbarImmersiveTest {
    private static Class<?> provider() throws Exception { return Class.forName("android.view.InsetsFrameProvider"); }

    private static Object source(int type, int bottom) throws Exception {
        Object source = provider().getConstructor(Object.class, int.class, int.class).newInstance(new Binder(), 0, type);
        provider().getMethod("setInsetsSize", Insets.class).invoke(source, Insets.of(0, 0, 0, bottom));
        return source;
    }

    private static Insets size(Object source) throws Exception {
        return (Insets) provider().getMethod("getInsetsSize").invoke(source);
    }

    private static WindowManager.LayoutParams params(Object... sources) throws Exception {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1000, 80, 2019, 537133096, -3);
        Object array = Array.newInstance(provider(), sources.length);
        for (int i = 0; i < sources.length; i++) Array.set(array, i, sources[i]);
        WindowManager.LayoutParams.class.getField("providedInsets").set(params, array);
        return params;
    }

    public static class TransitionsBase { public Drawable mBarBackground = new ColorDrawable(); }
    public static class Transitions extends TransitionsBase {}
    public static class Bar {
        public Context mContext;
        public int mNavBarMode = 2;
        public Transitions mNavigationBarTransitions = new Transitions();
        NavbarHost host;
        WindowManager.LayoutParams latest;
        int updates;
        boolean mainThread;
        Bar(Context context) { mContext = context; }
        public void repositionNavigationBar(int rotation) throws Exception {
            updates++;
            mainThread = Looper.myLooper() == Looper.getMainLooper();
            latest = params(source(WindowInsets.Type.navigationBars(), 24));
            host.onLayout(this, latest);
        }
    }

    private Bar bar() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Display display = activity.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        return new Bar(activity.createDisplayContext(display));
    }

    private NavbarHost host(Bar bar) throws Exception {
        return bar.host = new NavbarHost(Bar.class, provider());
    }

    @Test public void changesOnlyNavigationSizeAndPreservesImeOverridesAndGeometry() throws Exception {
        NavbarHost host = host(bar());
        Object navigation = source(WindowInsets.Type.navigationBars(), 24);
        Object gestures = source(WindowInsets.Type.mandatorySystemGestures(), 32);
        Object tappable = source(WindowInsets.Type.tappableElement(), 0);
        Object sideGestures = source(WindowInsets.Type.systemGestures(), 18);
        Class<?> overrideType = Class.forName("android.view.InsetsFrameProvider$InsetsSizeOverride");
        Object override = overrideType.getConstructor(int.class, Insets.class).newInstance(2011, Insets.of(0, 0, 0, 48));
        Object overrides = Array.newInstance(overrideType, 1);
        Array.set(overrides, 0, override);
        provider().getMethod("setInsetsSizeOverrides", overrides.getClass()).invoke(navigation, overrides);
        WindowManager.LayoutParams params = params(navigation, gestures, tappable, sideGestures);
        Object array = WindowManager.LayoutParams.class.getField("providedInsets").get(params);
        Object nativeOverrides = provider().getMethod("getInsetsSizeOverrides").invoke(navigation);
        host.removeNavigationInset(params);
        assertEquals(Insets.NONE, size(navigation));
        assertEquals(Insets.of(0, 0, 0, 32), size(gestures));
        assertEquals(Insets.NONE, size(tappable));
        assertEquals(Insets.of(0, 0, 0, 18), size(sideGestures));
        assertSame(array, WindowManager.LayoutParams.class.getField("providedInsets").get(params));
        assertSame(nativeOverrides, provider().getMethod("getInsetsSizeOverrides").invoke(navigation));
        assertEquals(1000, params.width);
        assertEquals(80, params.height);
        assertEquals(537133096, params.flags);
        assertEquals(2019, params.type);
    }

    @Test public void updatesBothEdgesOnMainAndRestoresFreshNativeParams() throws Exception {
        Bar bar = bar();
        NavbarHost host = host(bar);
        WindowManager.LayoutParams original = params(source(WindowInsets.Type.navigationBars(), 24));
        host.onLayout(bar, original);
        host.applyEnabled(true);
        assertEquals(1, bar.updates);
        assertTrue(bar.mainThread);
        assertNotSame(original, bar.latest);
        assertEquals(Insets.NONE, size(Array.get(WindowManager.LayoutParams.class.getField("providedInsets").get(bar.latest), 0)));
        host.applyEnabled(false);
        assertEquals(2, bar.updates);
        assertEquals(Insets.of(0, 0, 0, 24), size(Array.get(WindowManager.LayoutParams.class.getField("providedInsets").get(bar.latest), 0)));
        host.applyEnabled(false);
        assertEquals(2, bar.updates);
    }

    @Test public void defaultOffAndOnlyDefaultDisplayGesturesEligible() throws Exception {
        Bar bar = bar();
        NavbarHost host = host(bar);
        Object navigation = source(WindowInsets.Type.navigationBars(), 24);
        host.onLayout(bar, params(navigation));
        assertEquals(Insets.of(0, 0, 0, 24), size(navigation));
        Display display = bar.mContext.getDisplay();
        assertNotNull(display);
        assertTrue(NavbarHost.eligible(true, display, 2));
        assertFalse(NavbarHost.eligible(false, display, 2));
        assertFalse(NavbarHost.eligible(true, null, 2));
        assertFalse(NavbarHost.eligible(true, display, 0));
        assertFalse(NavbarHost.eligible(true, display, 1));
        int second = ShadowDisplayManager.addDisplay("w720dp-h1280dp");
        Display secondary = bar.mContext.getSystemService(DisplayManager.class).getDisplay(second);
        assertNotNull(secondary);
        assertFalse(NavbarHost.eligible(true, secondary, 2));
    }

    @Test public void onlyOwnedBackgroundIsHiddenAndModeAndDisableRestoreIt() throws Exception {
        Bar bar = bar();
        NavbarHost host = host(bar);
        Drawable background = bar.mNavigationBarTransitions.mBarBackground;
        host.onLayout(bar, params(source(WindowInsets.Type.navigationBars(), 24)));
        assertFalse(host.hideBackground(background));
        host.applyEnabled(true);
        assertTrue(host.hideBackground(background));
        assertFalse(host.hideBackground(new ColorDrawable()));
        bar.mNavBarMode = 0;
        assertFalse(host.hideBackground(background));
        bar.mNavBarMode = 2;
        assertTrue(host.hideBackground(background));
        host.applyEnabled(false);
        assertFalse(host.hideBackground(background));
    }

    @Test public void backgroundThreadCannotMutateNavigationLayout() throws Exception {
        Bar bar = bar();
        NavbarHost host = host(bar);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { host.applyEnabled(true); } catch (Throwable error) { failure.set(error); }
        });
        thread.start();
        thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(0, bar.updates);
    }
}
