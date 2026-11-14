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
    @Test public void launcherClearsHostOnlyWhileItOwnsScreenAndAdaptedTasksRemain() {
        String launcher = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity";
        assertTrue(NavbarSystem.clearsLauncherHost(false, true, launcher));
        assertFalse(NavbarSystem.clearsLauncherHost(false, false, launcher));
        assertFalse(NavbarSystem.clearsLauncherHost(false, true, "com.taobao.taobao/.Main"));
        assertFalse(NavbarSystem.clearsLauncherHost(false, true, null));
        assertFalse(NavbarSystem.clearsLauncherHost(true, true, launcher));
    }

    @Test public void samplingBoundaryDoesNotSubtractNavigationInsetsTwice() {
        Rect display = new Rect(0, 0, 1080, 2400);
        assertEquals(2320, NavbarSystem.navigationTop(new Rect(0, 0, 1080, 2400), display, 80));
        assertEquals(2320, NavbarSystem.navigationTop(new Rect(0, 0, 1080, 2320), display, 80));
        assertEquals(2220, NavbarSystem.navigationTop(new Rect(0, 100, 1080, 2320), display, 80));
    }

    @Test public void landscapeLauncherKeepsClearThroughHomeAndRecentsButNotSystemPanels() {
        String launcher = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity";
        assertFalse(NavbarSystem.keepsOriginal(NavbarStore.ORIGINAL, true, "系统宿主", launcher));
        assertTrue(NavbarSystem.keepsOriginal(NavbarStore.ORIGINAL, false, "系统宿主", launcher));
        assertTrue(NavbarSystem.keepsOriginal(NavbarStore.ORIGINAL, true, "系统宿主", "com.android.systemui/.Panel"));
        assertTrue(NavbarSystem.keepsOriginal(NavbarStore.ORIGINAL, true, "系统窗口", null));
        assertFalse(NavbarSystem.supportsNavigation(new Rect(0, 0, 2400, 1080), false, true, true, false));
        assertFalse(NavbarSystem.supportsNavigation(new Rect(0, 0, 2400, 1080), true, true, true, true));
    }

    public static final class Window {
        public final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
        public Object mActivityRecord;
        public boolean visible = true;
        public int mode = 1;
        public Object display;
        public Object getDisplayContent() { return display; }
        public boolean isVisible() { return visible; }
        public int getWindowingMode() { return mode; }
    }
    public static final class Policy {
        public Object mNavBarBackgroundWindowCandidate;
    }
    public static final class Display {
        public Object mCurrentFocus, mFocusedApp;
        public final Policy policy = new Policy();
        public Policy getDisplayPolicy() { return policy; }
    }

    @Test public void freeformFocusUsesNativeBottomOwnerNotThePreviousPage() throws Exception {
        Window floating = new Window(), bottom = new Window(), stale = new Window();
        floating.mAttrs.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        floating.mode = 5;
        Display display = new Display();
        display.mCurrentFocus = floating;
        display.policy.mNavBarBackgroundWindowCandidate = bottom;
        bottom.mAttrs.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        bottom.display = display;
        assertSame(bottom, NavbarSystem.targetWindow(display, stale));
        bottom.display = new Display();
        assertNull(NavbarSystem.targetWindow(display, stale));
        bottom.display = display;
        bottom.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        assertNull(NavbarSystem.targetWindow(display, stale));
        bottom.mAttrs.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        bottom.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        assertNull(NavbarSystem.targetWindow(display, stale));
        bottom.mAttrs.flags = 0;
        bottom.visible = false;
        assertNull(NavbarSystem.targetWindow(display, stale));
        bottom.visible = true; bottom.mode = 5;
        assertNull(NavbarSystem.targetWindow(display, stale));
        display.policy.mNavBarBackgroundWindowCandidate = null;
        assertNull(NavbarSystem.targetWindow(display, stale));
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
