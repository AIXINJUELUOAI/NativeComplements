package dev.local.sunwindowdemo.features.navbar;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
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
public class NavbarTaskbarHostTest {
    private static Class<?> provider() throws Exception { return Class.forName("android.view.InsetsFrameProvider"); }
    private static Object source(int type, int bottom) throws Exception {
        Object result = provider().getConstructor(Object.class, int.class, int.class).newInstance(new Binder(), 0, type);
        provider().getMethod("setInsetsSize", Insets.class).invoke(result, Insets.of(0, 0, 0, bottom));
        return result;
    }
    private static Insets size(Object source) throws Exception { return (Insets) provider().getMethod("getInsetsSize").invoke(source); }
    private static Object[] sources(WindowManager.LayoutParams params) throws Exception {
        return (Object[]) WindowManager.LayoutParams.class.getField("providedInsets").get(params);
    }
    private static void sources(WindowManager.LayoutParams params, Object... providers) throws Exception {
        Object array = Array.newInstance(provider(), providers.length);
        for (int i = 0; i < providers.length; i++) Array.set(array, i, providers[i]);
        WindowManager.LayoutParams.class.getField("providedInsets").set(params, array);
    }
    private static WindowManager.LayoutParams[] rotations(WindowManager.LayoutParams params) throws Exception {
        return (WindowManager.LayoutParams[]) WindowManager.LayoutParams.class.getField("paramsForRotation").get(params);
    }
    public static class Background { }
    public static class Layer extends View {
        public Background mBackgroundRenderer = new Background();
        boolean attached = true;
        Layer(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return attached; }
    }
    public static class TransitionsBase { public Drawable mBarBackground = new ColorDrawable(); }
    public static class Transitions extends TransitionsBase { }
    public static class Buttons { public Transitions mTaskbarTransitions = new Transitions(); }
    public static class Controllers { public Buttons navbarButtonsViewController = new Buttons(); }
    public static class Owner extends ContextWrapper {
        public boolean mIsDestroyed;
        boolean gesture = true;
        public Layer mDragLayer;
        public Controllers mControllers = new Controllers();
        public WindowManager mWindowManager;
        public WindowManager.LayoutParams mWindowLayoutParams = new WindowManager.LayoutParams(1000, 73, 2019, 0, -3);
        int submits;
        Owner(Context context) throws Exception {
            super(context);
            mDragLayer = new Layer(this);
            WindowManager.LayoutParams[] rotations = new WindowManager.LayoutParams[4];
            WindowManager.LayoutParams.class.getField("paramsForRotation").set(mWindowLayoutParams, rotations);
            initialize(mWindowLayoutParams);
            for (int i = 0; i < 4; i++) {
                rotations[i] = new WindowManager.LayoutParams(1000, 73, 2019, 0, -3);
                initialize(rotations[i]);
            }
            mWindowManager = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                    new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
                        assertEquals("updateViewLayout", method.getName());
                        assertSame(mDragLayer.getRootView(), args[0]);
                        assertSame(mWindowLayoutParams, args[1]);
                        assertEquals(Looper.getMainLooper(), Looper.myLooper());
                        submits++;
                        return null;
                    });
        }
        public boolean isGestureNav() { return gesture; }
        private void initialize(WindowManager.LayoutParams params) throws Exception {
            sources(params, source(WindowInsets.Type.navigationBars(), 73),
                    source(WindowInsets.Type.mandatorySystemGestures(), 98), source(WindowInsets.Type.tappableElement(), 0),
                    source(WindowInsets.Type.systemGestures(), 55));
        }
    }
    public static class Controller {
        public Owner context;
        NavbarTaskbarHost host;
        int nativeRefreshes, nativeSubmits;
        Object[] shallowLastProviders;
        Controller(Owner owner) throws Exception { context = owner; shallowLastProviders = sources(owner.mWindowLayoutParams); }
        public void onTaskbarOrBubblebarWindowHeightOrInsetsChanged() throws Exception {
            nativeRefreshes++;
            update(context.mWindowLayoutParams);
            for (WindowManager.LayoutParams rotation : rotations(context.mWindowLayoutParams)) update(rotation);
            // Reproduce the verified native comparison: references shared with the last submitted snapshot.
            if (!java.util.Arrays.equals(shallowLastProviders, sources(context.mWindowLayoutParams))) nativeSubmits++;
        }
        private void update(WindowManager.LayoutParams params) throws Exception {
            for (Object source : sources(params)) {
                int type = (Integer) provider().getMethod("getType").invoke(source);
                provider().getMethod("setInsetsSize", Insets.class).invoke(source,
                        Insets.of(0, 0, 0, type == WindowInsets.Type.navigationBars() ? 73
                                : type == WindowInsets.Type.mandatorySystemGestures() ? 98
                                : type == WindowInsets.Type.systemGestures() ? 55 : 0));
                host.onProvider(this, source);
            }
        }
    }
    private Owner owner() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Display display = activity.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        return new Owner(activity.createDisplayContext(display));
    }
    private Controller controller(Owner owner) throws Exception {
        Controller result = new Controller(owner);
        result.host = new NavbarTaskbarHost(Controller.class, provider());
        result.host.onProvider(result, sources(owner.mWindowLayoutParams)[0]);
        return result;
    }
    @Test public void enableAndDisableSubmitRecomputedMainAndFourRotationsDespiteShallowCache() throws Exception {
        Owner owner = owner();
        Controller controller = controller(owner);
        controller.host.applyEnabled(true);
        assertEquals(0, controller.nativeSubmits);
        assertEquals(1, owner.submits);
        assertSizes(owner.mWindowLayoutParams, 0);
        controller.host.applyEnabled(false);
        assertEquals(0, controller.nativeSubmits);
        assertEquals(2, owner.submits);
        assertEquals(2, controller.nativeRefreshes);
        assertSizes(owner.mWindowLayoutParams, 73);
    }
    private void assertSizes(WindowManager.LayoutParams params, int nav) throws Exception {
        assertEquals(Insets.of(0, 0, 0, nav), size(sources(params)[0]));
        assertEquals(Insets.of(0, 0, 0, 98), size(sources(params)[1]));
        assertEquals(Insets.NONE, size(sources(params)[2]));
        assertEquals(Insets.of(0, 0, 0, 55), size(sources(params)[3]));
        assertEquals(73, params.height);
        if (rotations(params) != null) for (WindowManager.LayoutParams rotation : rotations(params)) assertSizes(rotation, nav);
    }
    @Test public void preservesOverridesAndOnlyExactOwnedBackgroundsWhileEligible() throws Exception {
        Owner owner = owner();
        Controller controller = controller(owner);
        Object nav = sources(owner.mWindowLayoutParams)[0];
        Class<?> overrideType = Class.forName("android.view.InsetsFrameProvider$InsetsSizeOverride");
        Object overrides = Array.newInstance(overrideType, 1);
        Array.set(overrides, 0, overrideType.getConstructor(int.class, Insets.class).newInstance(2011, Insets.of(0, 0, 0, 40)));
        provider().getMethod("setInsetsSizeOverrides", overrides.getClass()).invoke(nav, overrides);
        Object original = provider().getMethod("getInsetsSizeOverrides").invoke(nav);
        controller.host.applyEnabled(true);
        assertSame(original, provider().getMethod("getInsetsSizeOverrides").invoke(nav));
        assertTrue(controller.host.hideBackground(owner.mDragLayer.mBackgroundRenderer));
        assertTrue(controller.host.hideBackground(owner.mControllers.navbarButtonsViewController.mTaskbarTransitions.mBarBackground));
        assertFalse(controller.host.hideBackground(new Background()));
        owner.gesture = false;
        assertFalse(controller.host.eligible(owner));
        assertFalse(controller.host.hideBackground(owner.mDragLayer.mBackgroundRenderer));
        owner.gesture = true;
        owner.mIsDestroyed = true;
        assertFalse(controller.host.eligible(owner));
        int count = owner.submits;
        controller.host.applyEnabled(false);
        assertEquals(count, owner.submits);
    }
    @Test public void secondaryDisplayAndDetachedWindowAreNotSubmittedAndBackgroundThreadRejected() throws Exception {
        Owner owner = owner();
        Controller controller = controller(owner);
        owner.mDragLayer.attached = false;
        controller.host.applyEnabled(true);
        assertEquals(0, owner.submits);
        int second = ShadowDisplayManager.addDisplay("w720dp-h1280dp");
        Display display = owner.getSystemService(DisplayManager.class).getDisplay(second);
        Owner secondary = new Owner(owner.createDisplayContext(display));
        assertFalse(controller.host.eligible(secondary));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { controller.host.applyEnabled(false); } catch (Throwable error) { failure.set(error); }
        });
        thread.start(); thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
    }
}
