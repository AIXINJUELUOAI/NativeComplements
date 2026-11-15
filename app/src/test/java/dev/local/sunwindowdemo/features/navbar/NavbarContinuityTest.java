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
        public Window parent;
        public Rect frame = new Rect(0, 0, 1080, 2400);
        public Object getDisplayContent() { return display; }
        public boolean isVisible() { return visible; }
        public int getWindowingMode() { return mode; }
        public int getBaseType() { return parent == null ? mAttrs.type : parent.getBaseType(); }
        public Window getParentWindow() { return parent; }
        public Rect getFrame() { return frame; }
    }
    public static final class Policy {
        public Object mNavBarBackgroundWindowCandidate;
    }
    public static final class Display {
        public Object mCurrentFocus, mFocusedApp;
        public final Policy policy = new Policy();
        public final java.util.List<Window> windows = new java.util.ArrayList<>();
        public Rect getBounds() { return new Rect(0, 0, 1080, 2400); }
        public void forAllWindows(java.util.function.Consumer<Window> consumer, boolean topToBottom) {
            assertTrue(topToBottom);
            windows.forEach(consumer);
        }
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

    @Test public void systemUiPanelsKeepTheAppBelowAndOtherAppsKeepTheirOwnFocus() throws Exception {
        Window app = new Window(), shade = new Window();
        shade.mAttrs.packageName = "com.android.systemui";
        shade.mAttrs.setTitle("NotificationShade");
        Display display = new Display();
        display.mCurrentFocus = shade;
        // Control centre over the app: the app still owns the bottom and keeps its adaptation.
        app.mActivityRecord = new Object();
        display.mFocusedApp = app.mActivityRecord;
        assertSame(app, NavbarSystem.targetWindow(display, app));
        display.mFocusedApp = new Object();
        assertNull(NavbarSystem.targetWindow(display, app));
        shade.mAttrs.packageName = "other.application";
        shade.mAttrs.setTitle("ScreenshotUI");
        assertSame(shade, NavbarSystem.targetWindow(display, app));
    }

    private static Window root(Display display, Object activity, int type, Rect frame) {
        Window window = new Window();
        window.display = display; window.mActivityRecord = activity;
        window.mAttrs.type = type; window.frame = frame;
        display.windows.add(window);
        return window;
    }

    @Test public void zOrderSelectsBottomPopupEvenWithoutReportsAndNeverSkipsSecure() throws Exception {
        Display display = new Display(); Object activity = new Object();
        Window popup = root(display, activity, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                new Rect(0, 2000, 1080, 2320));
        Window app = root(display, activity, WindowManager.LayoutParams.TYPE_BASE_APPLICATION, display.getBounds());
        popup.parent = app;
        // Parent activity fallback and getBaseType include subwindows; focus can stay on the Activity.
        popup.mActivityRecord = null;
        display.mCurrentFocus = app;
        assertSame(popup, NavbarSystem.bottomWindow(display, NavbarSystem.targetWindow(display, null), 80));
        popup.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        assertSame(popup, NavbarSystem.bottomWindow(display, app, 80));
        popup.visible = false;
        assertSame(app, NavbarSystem.bottomWindow(display, app, 80));
        popup.visible = true; popup.mAttrs.alpha = 0;
        assertSame(app, NavbarSystem.bottomWindow(display, app, 80));
    }

    @Test public void bottomSelectionIgnoresOtherActivitiesOffBandAndOtherDisplays() throws Exception {
        Display display = new Display(); Object activity = new Object();
        Window other = root(display, new Object(), WindowManager.LayoutParams.TYPE_BASE_APPLICATION, display.getBounds());
        Window offBand = root(display, activity, WindowManager.LayoutParams.TYPE_APPLICATION, new Rect(0, 100, 1080, 1000));
        Window offDisplay = root(display, activity, WindowManager.LayoutParams.TYPE_APPLICATION, display.getBounds());
        offDisplay.display = new Display();
        Window app = root(display, activity, WindowManager.LayoutParams.TYPE_BASE_APPLICATION, display.getBounds());
        assertSame(app, NavbarSystem.bottomWindow(display, app, 80));
        offBand.frame = new Rect(0, 2200, 1080, 2350);
        assertSame(offBand, NavbarSystem.bottomWindow(display, app, 80));
        other.mActivityRecord = activity;
        assertSame(other, NavbarSystem.bottomWindow(display, app, 80));
        assertSame(app, NavbarSystem.bottomWindow(display, app, 0));
    }

    @Test public void popupAndSecureWindowsAreDiagnosticOnlyExceptExplicitBaseFloating() {
        WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        attrs.width = attrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        NavbarRules.Choice automatic = new NavbarRules.Choice(NavbarStore.FLOATING, null, NavbarSystem.AUTO_SOURCE);
        assertTrue(NavbarSystem.executionWindow(attrs, automatic));
        attrs.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        assertFalse(NavbarSystem.executionWindow(attrs, automatic));
        attrs.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        attrs.height = 400;
        assertFalse(NavbarSystem.executionWindow(attrs, automatic));
        attrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        attrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        assertFalse(NavbarSystem.executionWindow(attrs, automatic));
        assertFalse(NavbarSystem.executionWindow(attrs, new NavbarRules.Choice(NavbarStore.COLOR, null, "用户Activity")));
        assertTrue(NavbarSystem.executionWindow(attrs, new NavbarRules.Choice(NavbarStore.FLOATING, null, "用户Activity")));
        assertTrue(NavbarSystem.keepsOriginal(NavbarStore.ORIGINAL, true, "窗口限制", "pkg/.Popup"));
    }

    @Test public void detailLogIgnoresTimingAndNodeCountJitterButKeepsGeometryAndReason() {
        android.os.Bundle data = new android.os.Bundle();
        data.putBinder("window", new android.os.Binder()); data.putLong("sampledAt", 100);
        data.putString("container", "固定操作区"); data.putString("node", "LinearLayout");
        data.putString("bounds", "[0,2200][1080,2320]"); data.putString("reason", "固定控件");
        data.putInt("suggested", NavbarStore.COLOR); data.putInt("nodes", 5); data.putLong("nanos", 2000);
        String key = NavbarSystem.structureKey(NavbarStructure.read(data, 100));
        data.putInt("nodes", 7); data.putLong("nanos", 4000);
        assertEquals(key, NavbarSystem.structureKey(NavbarStructure.read(data, 100)));
        data.putString("bounds", "[0,2210][1080,2320]");
        assertNotEquals(key, NavbarSystem.structureKey(NavbarStructure.read(data, 100)));
        data.putString("bounds", "[0,2200][1080,2320]"); data.putString("reason", "新原因");
        assertNotEquals(key, NavbarSystem.structureKey(NavbarStructure.read(data, 100)));
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
