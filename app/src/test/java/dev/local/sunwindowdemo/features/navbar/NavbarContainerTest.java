package dev.local.sunwindowdemo.features.navbar;

import android.content.Context;
import android.graphics.Rect;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ScrollView;
import androidx.compose.ui.platform.ComposeView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE, qualifiers = "mdpi")
public class NavbarContainerTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private FrameLayout root() {
        FrameLayout root = new FrameLayout(context);
        root.layout(0, 0, 1000, 2000);
        return root;
    }

    private <T extends View> T add(ViewGroup parent, T child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
        if (!(child instanceof ComposeView))
            child.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        child.layout(left, top, right, bottom);
        return child;
    }

    private FrameLayout bar(ViewGroup parent, int bottom) {
        FrameLayout bar = add(parent, new FrameLayout(context), 0, bottom - 100, 1000, bottom);
        add(bar, new EditText(context), 20, 10, 800, 90);
        add(bar, new Button(context), 820, 10, 980, 90);
        assertEquals(1000, bar.getWidth());
        assertEquals(100, bar.getHeight());
        assertTrue(bar.getChildAt(0).getWidth() > 0);
        assertTrue(bar.getChildAt(0).getHeight() > 0);
        assertTrue(bar.getChildAt(0).isEnabled());
        assertTrue(bar.getChildAt(0).isFocusable());
        return bar;
    }

    private NavbarContainer.Result inspect(View root, int nav, boolean floating) {
        // Class loading/JIT in the host can consume the entire soft budget. Retry only that outcome;
        // do not turn a genuine structural UNKNOWN into a successful assertion.
        NavbarContainer.Result result = null;
        for (int i = 0; i < 8; i++) {
            result = NavbarContainer.inspect(root, nav, floating);
            if (!result.reason().contains("时间软预算")) return result;
        }
        return result;
    }

    private void mode(int expected, NavbarContainer.Result result) {
        assertEquals(result.reason(), expected, result.mode());
        assertTrue(result.nodes() <= NavbarContainer.MAX_NODES);
        assertTrue(result.nanos() >= 0);
    }

    @Test public void fixedInputBarRequiresRealVisibleControls() {
        FrameLayout root = root();
        bar(root, 1920);
        NavbarContainer.Result result = inspect(root, 80, false);
        mode(NavbarContainer.COLOR, result);
        assertEquals("固定操作区", result.kind());
        assertEquals("FrameLayout", result.node());
        assertEquals(new Rect(0, 1820, 1000, 1920), result.bounds());
    }

    @Test public void blankOrMerelyClickableContainerIsNotAnOperationBar() {
        FrameLayout root = root();
        FrameLayout blank = add(root, new FrameLayout(context), 0, 1820, 1000, 1920);
        mode(NavbarContainer.ORIGINAL, inspect(root, 80, false));
        blank.setClickable(true);
        mode(NavbarContainer.ORIGINAL, inspect(root, 80, false));
        add(blank, new FrameLayout(context), 0, 0, 900, 90).setClickable(true);
        mode(NavbarContainer.ORIGINAL, inspect(root, 80, false));
    }

    @Test public void bottomGridIsScrollableEvenWithoutCurrentScrollRange() {
        FrameLayout root = root();
        add(root, new GridView(context), 0, 1200, 1000, 1920);
        NavbarContainer.Result result = inspect(root, 80, false);
        mode(NavbarContainer.FLOATING, result);
        assertEquals("滚动内容区", result.kind());
        assertEquals("GridView", result.node());
    }

    @Test public void scrollAtEndStaysScrollableAndNeverExaminesItsRows() {
        FrameLayout root = root();
        ScrollView scroll = add(root, new ScrollView(context), 0, 0, 1000, 1920);
        FrameLayout content = new FrameLayout(context);
        scroll.addView(content, new ScrollView.LayoutParams(1000, 4000));
        bar(content, 4000);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 1000, 1920);
        assertEquals(4000, content.getHeight());
        mode(NavbarContainer.FLOATING, inspect(root, 80, false));
        scroll.scrollTo(0, 2080);
        assertFalse(scroll.canScrollVertically(1));
        assertTrue(scroll.canScrollVertically(-1));
        NavbarContainer.Result result = inspect(root, 80, false);
        mode(NavbarContainer.FLOATING, result);
        assertEquals(2, result.nodes());
    }

    @Test public void nonBottomScrollDoesNotOverrideIndependentFixedBar() {
        FrameLayout root = root();
        add(root, new ScrollView(context), 0, 0, 1000, 1820);
        bar(root, 1920);
        mode(NavbarContainer.COLOR, inspect(root, 80, false));
    }

    @Test public void siblingOrderAndElevationDetermineWhichWideLayerIsInFront() {
        FrameLayout root = root();
        ScrollView scroll = add(root, new ScrollView(context), 0, 0, 1000, 1920);
        FrameLayout bar = bar(root, 1920);
        mode(NavbarContainer.COLOR, inspect(root, 80, false));
        scroll.setElevation(10);
        mode(NavbarContainer.FLOATING, inspect(root, 80, false));
        bar.setElevation(20);
        mode(NavbarContainer.COLOR, inspect(root, 80, false));
    }

    @Test public void publicCustomDrawingOrderIsRespected() {
        ReverseLayout root = new ReverseLayout(context);
        root.layout(0, 0, 1000, 2000);
        add(root, new ScrollView(context), 0, 0, 1000, 1920);
        bar(root, 1920);
        mode(NavbarContainer.FLOATING, inspect(root, 80, false));
    }

    @Test public void hiddenAndSmallFloatingControlsDoNotHideWideScrollingContent() {
        FrameLayout root = root();
        add(root, new ScrollView(context), 0, 0, 1000, 1920);
        bar(root, 1920).setVisibility(View.GONE);
        add(root, new Button(context), 900, 1850, 980, 1920);
        mode(NavbarContainer.FLOATING, inspect(root, 80, false));
    }

    @Test public void unknownWideForegroundBlocksTheScrollBehindIt() {
        FrameLayout root = root();
        add(root, new ScrollView(context), 0, 0, 1000, 1920);
        add(root, new View(context), 0, 1820, 1000, 1920);
        NavbarContainer.Result result = inspect(root, 80, false);
        mode(NavbarContainer.ORIGINAL, result);
        assertTrue(result.reason().contains("自绘"));
    }

    @Test public void unknownLeafPreventsParentBarFromBecomingFixed() {
        FrameLayout root = root();
        FrameLayout bar = bar(root, 1920);
        add(bar, new View(context), 0, 0, 1000, 100);
        mode(NavbarContainer.ORIGINAL, inspect(root, 80, false));
    }

    @Test public void clippedAndInvisibleUnknownLeafDoesNotPoisonRealBar() {
        FrameLayout root = root();
        FrameLayout bar = bar(root, 1920);
        add(bar, new View(context), 1100, 0, 1200, 100);
        add(bar, new View(context), 0, 0, 1000, 100).setAlpha(0);
        mode(NavbarContainer.COLOR, inspect(root, 80, false));
        bar.setClipBounds(new Rect(0, 0, 1000, 50));
        mode(NavbarContainer.ORIGINAL, inspect(root, 80, false));
    }

    @Test public void embeddedAndSelfDrawnRootsHaveSpecificUnknownReasons() {
        View[] views = {new ComposeView(context), new WebView(context), new SurfaceView(context), new View(context)};
        String[] reasons = {"Compose", "WebView", "Surface", "自绘"};
        for (int i = 0; i < views.length; i++) {
            FrameLayout root = root();
            // ComposeView starts GONE until content is supplied; this test needs a visible opaque host,
            // not hidden Compose content (which correctly must be ignored).
            views[i].setVisibility(View.VISIBLE);
            add(root, views[i], 0, 0, 1000, 1920);
            if (views[i] instanceof WebView) {
                // Host-only workaround: Robolectric's WebView layout stub leaves a zero frame.
                // Supply its visible viewport directly; production still uses standard View geometry.
                views[i].setLeft(0);
                views[i].setTop(0);
                views[i].setRight(1000);
                views[i].setBottom(1920);
            }
            NavbarContainer.Result result = inspect(root, 80, false);
            mode(NavbarContainer.ORIGINAL, result);
            assertTrue("case=" + i + " type=" + views[i].getClass().getName()
                            + " visibility=" + views[i].getVisibility() + " alpha=" + views[i].getAlpha()
                            + " frame=" + new Rect(views[i].getLeft(), views[i].getTop(), views[i].getRight(), views[i].getBottom())
                            + " clip=" + views[i].getClipBounds() + " result=" + result,
                    result.reason().contains(reasons[i]));
        }
    }

    @Test public void floatingUsesWindowBottomAndReportsWindowCoordinates() {
        FrameLayout root = root();
        root.setTranslationY(10);
        bar(root, 2000);
        NavbarContainer.Result result = inspect(root, 80, true);
        mode(NavbarContainer.COLOR, result);
        int[] at = new int[2];
        root.getLocationInWindow(at);
        assertEquals(new Rect(at[0], at[1] + 1900, at[0] + 1000, at[1] + 2000), result.bounds());
        // Native-layout inspection uses the content boundary above reserved navigation space.
        FrameLayout nativeRoot = root();
        bar(nativeRoot, 1920);
        mode(NavbarContainer.ORIGINAL, inspect(nativeRoot, 80, true));
        mode(NavbarContainer.COLOR, inspect(nativeRoot, 80, false));
    }

    @Test public void nodeAndDepthLimitsFailClosed() {
        FrameLayout root = root();
        for (int i = 0; i < NavbarContainer.MAX_NODES; i++)
            add(root, new Button(context), 0, 0, 10, 10);
        NavbarContainer.Result nodes = inspect(root, 80, false);
        mode(NavbarContainer.ORIGINAL, nodes);
        assertTrue(nodes.reason(), nodes.reason().contains("节点上限"));
        root = root();
        FrameLayout child = root;
        for (int i = 0; i <= NavbarContainer.MAX_DEPTH; i++)
            child = add(child, new FrameLayout(context), 0, 0, 1000, 1920);
        NavbarContainer.Result depth = inspect(root, 80, false);
        mode(NavbarContainer.ORIGINAL, depth);
        assertTrue(depth.reason(), depth.reason().contains("深度上限"));
    }

    private static final class ReverseLayout extends FrameLayout {
        ReverseLayout(Context context) { super(context); setChildrenDrawingOrderEnabled(true); }
        @Override protected int getChildDrawingOrder(int count, int index) { return count - 1 - index; }
    }
}
