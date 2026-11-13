package dev.local.sunwindowdemo.features.navbar;

import android.view.WindowManager;
import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarContinuityTest {
    @Test public void landscapeClearsTransientNavigationButStillHonorsKeyboardAndSwitch() {
        Rect landscape = new Rect(0, 0, 2670, 1200);
        Rect portrait = new Rect(0, 0, 1200, 2670);
        assertTrue(NavbarSystem.supportsNavigation(landscape, true, false, false, false));
        assertFalse(NavbarSystem.supportsNavigation(landscape, false, true, true, false));
        assertFalse(NavbarSystem.supportsNavigation(landscape, true, true, true, true));
        assertFalse(NavbarSystem.supportsNavigation(portrait, true, false, true, false));
        assertFalse(NavbarSystem.supportsNavigation(portrait, true, true, false, false));
        assertTrue(NavbarSystem.supportsNavigation(portrait, false, true, true, false));
    }
    public static final class Window {
        public final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
        public Object mActivityRecord;
        public boolean visible = true;
        public boolean isVisible() { return visible; }
    }
    public static final class Display {
        public Object mCurrentFocus, mFocusedApp;
    }

    @Test public void screenshotKeepsTheVisibleApplicationAndFloatingInsetsOwner() throws Exception {
        Window app = new Window();
        app.mActivityRecord = new Object();
        Window screenshot = new Window();
        screenshot.mAttrs.packageName = "com.android.systemui";
        screenshot.mAttrs.setTitle("ScreenshotUI");
        Display display = new Display();
        display.mFocusedApp = app.mActivityRecord;
        display.mCurrentFocus = screenshot;
        assertSame(app, NavbarSystem.targetWindow(display, app));
        display.mCurrentFocus = null;
        assertSame(app, NavbarSystem.targetWindow(display, app));
        display.mCurrentFocus = app;
        assertSame(app, NavbarSystem.targetWindow(display, app));
    }

    @Test public void screenshotCannotKeepAnotherActivityOrHiddenWindow() throws Exception {
        Window app = new Window();
        app.mActivityRecord = new Object();
        Display display = new Display();
        display.mFocusedApp = new Object();
        assertNull(NavbarSystem.targetWindow(display, app));
        display.mFocusedApp = app.mActivityRecord;
        app.visible = false;
        assertNull(NavbarSystem.targetWindow(display, app));
        assertNull(NavbarSystem.targetWindow(display, null));
    }

    @Test public void otherSystemPanelsAndApplicationsKeepTheirOwnFocus() throws Exception {
        Window app = new Window(), shade = new Window();
        shade.mAttrs.packageName = "com.android.systemui";
        shade.mAttrs.setTitle("NotificationShade");
        Display display = new Display();
        display.mCurrentFocus = shade;
        assertSame(shade, NavbarSystem.targetWindow(display, app));
        shade.mAttrs.packageName = "other.application";
        shade.mAttrs.setTitle("ScreenshotUI");
        assertSame(shade, NavbarSystem.targetWindow(display, app));
    }

    @Test public void failedSampleOnlyRetainsRecentMatchingWindowModeAndGeometry() {
        Object window = new Object();
        NavbarSystem.Frame frame = new NavbarSystem.Frame(window, 0xffffffff, NavbarStore.COLOR, 73, 1000);
        assertTrue(frame.canRetain(window, NavbarStore.COLOR, 73, 2500));
        // A visible same-page screenshot may keep the frame for the overlay's whole lifetime.
        assertTrue(frame.matches(window, NavbarStore.COLOR, 73));
        assertFalse(frame.canRetain(window, NavbarStore.COLOR, 73, 2501));
        assertFalse(frame.canRetain(new Object(), NavbarStore.COLOR, 73, 1001));
        assertFalse(frame.canRetain(window, NavbarStore.ORIGINAL, 73, 1001));
        assertFalse(frame.canRetain(window, NavbarStore.COLOR, 90, 1001));
        assertFalse(frame.canRetain(window, NavbarStore.COLOR, 73, 999));
    }
}
