package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Comparator;

/** Bounded, main-thread, geometry-only inspection. No text, pixels, accessibility or view mutation. */
final class NavbarContainer {
    static final int ORIGINAL = 0, COLOR = 1, FLOATING = 2;
    static final int MAX_NODES = 256, MAX_DEPTH = 32;
    static final long BUDGET_NANOS = 4_000_000L;

    record Result(String kind, String node, String reason, Rect bounds, int nodes, long nanos, int mode) {}
    private record Finding(View view, Rect bounds, int mode, String reason) {}
    private record Scan(Finding bottom, boolean controls, boolean scrolling, boolean opaque) {}
    private static final Scan EMPTY = new Scan(null, false, false, false);

    static Result inspect(View decor, int navigationHeight, boolean floating) {
        long start = System.nanoTime();
        if (Looper.myLooper() != Looper.getMainLooper())
            return unknown("", "需在应用主线程读取视图", new Rect(), 0, start);
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0)
            return unknown("", "窗口尚未布局", new Rect(), 0, start);
        Probe probe = new Probe(decor, navigationHeight, floating, start);
        try {
            Scan scan = probe.visit(decor, probe.window.left, probe.window.top, probe.window, 0);
            if (probe.exhausted())
                return unknown("", probe.limit, probe.window, probe.nodes, start);
            Finding found = scan.bottom;
            if (found == null)
                return unknown(shortName(decor), "未找到紧邻底边的独立操作区或滚动容器", probe.window, probe.nodes, start);
            return new Result(found.mode == COLOR ? "固定操作区" : found.mode == FLOATING ? "滚动内容区" : "未确定",
                    shortName(found.view), found.reason, new Rect(found.bounds), probe.nodes,
                    System.nanoTime() - start, found.mode);
        } catch (RuntimeException | LinkageError unavailable) {
            return unknown("", "视图 API 不可用，保留原有", probe.window, probe.nodes, start);
        }
    }

    private static Result unknown(String node, String reason, Rect bounds, int nodes, long start) {
        return new Result("未确定", node, reason, new Rect(bounds), nodes, System.nanoTime() - start, ORIGINAL);
    }

    private static String shortName(View view) {
        String name = view.getClass().getName();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple.length() <= 120 ? simple : simple.substring(simple.length() - 120);
    }

    private static final class Probe {
        final View decor;
        final Rect window;
        final long start;
        final int edge, tolerance, minBar, maxBar;
        int nodes;
        String limit;

        Probe(View decor, int navigationHeight, boolean floating, long start) {
            this.decor = decor;
            this.start = start;
            int[] at = new int[2];
            decor.getLocationInWindow(at);
            window = new Rect(at[0], at[1], at[0] + decor.getWidth(), at[1] + decor.getHeight());
            // Caller supplies the current layout mode: only native/reserved-nav layouts subtract nav.
            // Do not infer a reserved area from blank pixels or force this boundary onto floating layouts.
            edge = window.bottom - (floating ? 0 : Math.max(0, Math.min(navigationHeight, decor.getHeight())));
            float density = decor.getResources().getDisplayMetrics().density;
            tolerance = Math.max(1, Math.round(2 * density));
            minBar = Math.max(1, Math.round(24 * density));
            maxBar = Math.min(Math.round(160 * density), decor.getHeight() / 4);
        }

        private boolean exhausted() {
            if (limit == null && System.nanoTime() - start >= BUDGET_NANOS)
                limit = "达到时间软预算，保留原有（非硬实时保证）";
            return limit != null;
        }

        Scan visit(View view, float x, float y, Rect clip, int depth) {
            if (exhausted()) return EMPTY;
            if (nodes >= MAX_NODES) { limit = "达到节点上限"; return EMPTY; }
            nodes++;
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f
                    || view.getId() == android.R.id.navigationBarBackground
                    || view.getId() == android.R.id.statusBarBackground) return EMPTY;
            if (depth > MAX_DEPTH) { limit = "达到层级深度上限"; return EMPTY; }
            Rect bounds = new Rect(Math.round(x), Math.round(y), Math.round(x + view.getWidth()), Math.round(y + view.getHeight()));
            Rect visible = new Rect(bounds);
            if (!visible.intersect(clip)) visible.setEmpty();
            Rect ownClip = view.getClipBounds();
            if (ownClip != null) {
                ownClip.offset(bounds.left, bounds.top);
                if (!visible.intersect(ownClip)) visible.setEmpty();
            }
            boolean bottom = !visible.isEmpty() && visible.top < edge
                    && visible.bottom >= edge - tolerance && visible.width() >= window.width() * 0.8f;
            String opaque = opaqueReason(view);
            if (view.getRotation() != 0 || view.getRotationX() != 0 || view.getRotationY() != 0
                    || view.getScaleX() != 1 || view.getScaleY() != 1)
                opaque = "视图存在旋转或缩放，无法确认可见边界";
            if (opaque != null) return new Scan(bottom ? finding(view, visible, ORIGINAL, opaque) : null,
                    false, false, !visible.isEmpty());
            boolean scrolling = isScrolling(view);
            if (scrolling) return visible.isEmpty() ? EMPTY : new Scan(bottom ? finding(view, visible, FLOATING,
                    "宽滚动容器紧邻底边；按容器类型/双向滚动能力识别，不依赖当前是否到末尾") : null, false, true, false);
            boolean controls = !visible.isEmpty() && !(view instanceof ViewGroup) && view.isEnabled()
                    && (view.isClickable() || view.isLongClickable() || view.isFocusable() || view instanceof EditText);
            if (!(view instanceof ViewGroup group)) return new Scan(null, controls, false, false);

            Rect childClip = new Rect(clip);
            if (ownClip != null && !childClip.intersect(ownClip)) childClip.setEmpty();
            if (group.getClipChildren()) {
                Rect parentClip = new Rect(bounds);
                if (group.getClipToPadding()) {
                    parentClip.left += group.getPaddingLeft(); parentClip.top += group.getPaddingTop();
                    parentClip.right -= group.getPaddingRight(); parentClip.bottom -= group.getPaddingBottom();
                }
                if (!childClip.intersect(parentClip)) childClip.setEmpty();
            }
            if (childClip.isEmpty()) return EMPTY;
            int count = group.getChildCount();
            if (count > MAX_NODES - nodes) { limit = "达到节点上限"; return EMPTY; }
            ArrayList<View> children = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (exhausted()) return EMPTY;
                // Public API (29+): includes ViewGroup's custom drawing order, without reflection.
                children.add(group.getChildAt(group.getChildDrawingOrder(i)));
            }
            // Android draws elevated children after lower-Z children; equal-Z sorting remains stable.
            children.sort(Comparator.comparingDouble(View::getZ));
            Finding front = null;
            boolean childControls = false, childScroll = false, childOpaque = false;
            for (int i = children.size() - 1; i >= 0; i--) {
                View child = children.get(i);
                Scan scan = visit(child, x + child.getX() - group.getScrollX(),
                        y + child.getY() - group.getScrollY(), childClip, depth + 1);
                if (exhausted()) return EMPTY;
                if (front == null) front = scan.bottom;
                childControls |= scan.controls;
                childScroll |= scan.scrolling;
                childOpaque |= scan.opaque;
            }
            // A list item is never a fixed bar: scrolling ancestors were stopped before entering children.
            if (front == null && bottom && view != decor && visible.height() >= minBar
                    && bounds.height() <= maxBar && childControls && !childScroll && !childOpaque)
                front = finding(view, visible, view.getAlpha() < 0.99f ? ORIGINAL : COLOR,
                        view.getAlpha() < 0.99f ? "底部操作条半透明，无法确认遮挡关系"
                                : "独立宽底部短条，含可见操作控件且不属于滚动容器");
            if (front == null && bottom)
                front = finding(view, visible, ORIGINAL, childOpaque
                        ? "底部容器含无法确认的自绘/嵌入内容" : "底部容器缺少独立短条及可见操作控件证据");
            return new Scan(front, controls || childControls, childScroll, childOpaque);
        }

        private Finding finding(View view, Rect bounds, int mode, String reason) {
            Rect area = new Rect(bounds);
            area.bottom = Math.min(area.bottom, edge);
            return new Finding(view, area, mode, reason);
        }
    }

    private static String opaqueReason(View view) {
        if (view instanceof WebView) return "WebView 内部页面结构不可由标准 View 树确认";
        if (view instanceof SurfaceView || view instanceof TextureView) return "Surface/Texture 自绘内容不可由标准 View 树确认";
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.startsWith("androidx.compose.")) return "Compose 语义/布局不属于标准 View 子树";
        }
        String name = view.getClass().getName();
        if (view.getClass() == View.class) return "普通 View 无标准内容结构，无法排除自绘";
        if (!name.startsWith("android.widget.") && !name.startsWith("com.android.internal.policy.")
                && !name.equals("androidx.constraintlayout.widget.ConstraintLayout")
                && !(view instanceof ScrollView) && !(view instanceof HorizontalScrollView)
                && !(view instanceof AbsListView) && !knownScrollClass(view)
                && (!(view instanceof ViewGroup) || !view.willNotDraw()))
            return "自定义绘制视图，标准 View API 无法确认内容结构";
        return null;
    }

    private static boolean isScrolling(View view) {
        // Text editors also expose internal text scrolling; that does not make their operation bar
        // a scrolling content container. Generic capability checks apply only to container views.
        return view instanceof ScrollView || view instanceof HorizontalScrollView || view instanceof AbsListView
                || knownScrollClass(view) || view instanceof ViewGroup
                && (view.canScrollVertically(-1) || view.canScrollVertically(1)
                    || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1));
    }

    private static boolean knownScrollClass(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals("androidx.recyclerview.widget.RecyclerView")
                    || name.equals("androidx.core.widget.NestedScrollView")
                    || name.equals("androidx.viewpager.widget.ViewPager")
                    || name.equals("androidx.viewpager2.widget.ViewPager2")) return true;
        }
        return false;
    }
}
