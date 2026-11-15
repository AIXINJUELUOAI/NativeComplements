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
    private record Finding(View view, Rect bounds, int mode, String reason, boolean standardControl) {}
    private record Scan(Finding bottom, boolean controls, boolean scrolling, boolean opaque, boolean covered) {}
    private static final Scan EMPTY = new Scan(null, false, false, false, false);

    static Result inspect(View decor, int navigationHeight, boolean floating) {
        long start = System.nanoTime();
        if (Looper.myLooper() != Looper.getMainLooper())
            return unknown("", "需在应用主线程读取视图", new Rect(), 0, start);
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0)
            return unknown("", "窗口尚未布局", new Rect(), 0, start);
        Probe probe = new Probe(decor, navigationHeight, floating, start);
        try {
            Scan scan = probe.visit(decor, probe.window.left, probe.window.top, probe.window, 0, 1);
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
        final Rect window, band;
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
            // The caller supplies the current layout mode and original reserved navigation height.
            edge = window.bottom - (floating ? 0 : Math.max(0, Math.min(navigationHeight, decor.getHeight())));
            float density = decor.getResources().getDisplayMetrics().density;
            tolerance = Math.max(1, Math.round(2 * density));
            minBar = Math.max(1, Math.round(24 * density));
            maxBar = Math.min(Math.round(160 * density), decor.getHeight() / 4);
            band = new Rect(window.left, Math.max(window.top, edge - maxBar), window.right, edge);
        }

        private boolean exhausted() {
            if (limit == null && System.nanoTime() - start >= BUDGET_NANOS)
                limit = "达到时间软预算，保留原有（非硬实时保证）";
            return limit != null;
        }

        Scan visit(View view, float x, float y, Rect clip, int depth, float parentAlpha) {
            if (exhausted()) return EMPTY;
            if (nodes >= MAX_NODES) { limit = "达到节点上限"; return EMPTY; }
            nodes++;
            float alpha = parentAlpha * view.getAlpha();
            if (view.getVisibility() != View.VISIBLE || alpha <= 0.01f
                    || view.getId() == android.R.id.navigationBarBackground
                    || view.getId() == android.R.id.statusBarBackground) return EMPTY;
            Rect bounds = new Rect(Math.round(x), Math.round(y), Math.round(x + view.getWidth()), Math.round(y + view.getHeight()));
            Rect visible = new Rect(bounds);
            if (!visible.intersect(clip)) visible.setEmpty();
            Rect ownClip = view.getClipBounds();
            if (ownClip != null) {
                ownClip.offset(bounds.left, bounds.top);
                if (!visible.intersect(ownClip)) visible.setEmpty();
            }
            boolean transformed = view.getRotation() != 0 || view.getRotationX() != 0 || view.getRotationY() != 0
                    || view.getScaleX() != 1 || view.getScaleY() != 1;
            boolean clipsChildren = !(view instanceof ViewGroup group) || group.getClipChildren();
            // A non-clipping parent can draw descendants outside its own frame: only prune its inherited clip.
            if (!Rect.intersects(clip, band) || !transformed && clipsChildren && !Rect.intersects(visible, band)) return EMPTY;
            if (depth > MAX_DEPTH) { limit = "达到层级深度上限"; return EMPTY; }
            boolean bottom = !visible.isEmpty() && visible.top < edge
                    && visible.bottom >= edge - tolerance && visible.width() >= window.width() * 0.8f;
            boolean substantialOverlay = !visible.isEmpty() && visible.top < edge
                    && visible.bottom >= edge - tolerance && visible.width() >= window.width() * 0.2f;
            boolean covered = !transformed && alpha >= 0.99f && view.isOpaque()
                    && visible.left <= window.left && visible.right >= window.right
                    && visible.top <= edge - tolerance && visible.bottom >= edge;
            String opaque = embeddedReason(view);
            if (transformed) opaque = "视图存在旋转或缩放，无法确认可见边界";
            if (opaque != null) return new Scan(substantialOverlay ? finding(view, visible, ORIGINAL, opaque) : null,
                    false, false, Rect.intersects(visible, band), covered);
            // Unknown scroll-capable groups must be checked before custom drawing, but never Web/Compose/Surface.
            if (isScrolling(view)) return new Scan(substantialOverlay ? finding(view, visible,
                    !bottom || alpha < 0.99f ? ORIGINAL : FLOATING,
                    !bottom ? "局部滚动区域与底边相交，无法确认整宽内容归属"
                            : alpha < 0.99f ? "滚动容器半透明，无法确认底部遮挡关系"
                            : "宽滚动容器紧邻底边；按容器类型/双向滚动能力识别，不依赖当前是否到末尾") : null,
                    false, Rect.intersects(visible, band), false, covered);
            if (!(view instanceof ViewGroup group)) {
                String reason = leafReason(view);
                boolean inBand = Rect.intersects(visible, band);
                if (reason != null) return new Scan(substantialOverlay ? finding(view, visible, ORIGINAL, reason) : null,
                        false, false, inBand, covered);
                boolean controls = inBand && view.isEnabled()
                        && (view.isClickable() || view.isLongClickable() || view.isFocusable() || view instanceof EditText);
                Finding leaf = substantialOverlay ? finding(view, visible, ORIGINAL, "单个宽控件不构成独立底部操作区") : null;
                if (leaf != null && controls)
                    leaf = new Finding(leaf.view, leaf.bounds, leaf.mode, leaf.reason, true);
                return new Scan(leaf, controls, false, false, covered);
            }

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
            if (!Rect.intersects(childClip, band)) return new Scan(null, false, false, false, covered);
            int count = group.getChildCount();
            if (count > MAX_NODES - nodes) { limit = "达到节点上限"; return EMPTY; }
            ArrayList<View> children = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (exhausted()) return EMPTY;
                children.add(group.getChildAt(group.getChildDrawingOrder(i)));
            }
            // Public custom drawing order first, then stable Z sort, then front-to-back traversal.
            children.sort(Comparator.comparingDouble(View::getZ));
            Finding front = null;
            boolean childControls = false, childScroll = false, childOpaque = false, childCovered = false;
            for (int i = children.size() - 1; i >= 0; i--) {
                View child = children.get(i);
                Scan scan = visit(child, x + child.getX() - group.getScrollX(),
                        y + child.getY() - group.getScrollY(), childClip, depth + 1, alpha);
                if (exhausted()) return EMPTY;
                if (scan.bottom != null) {
                    if (front == null) front = scan.bottom;
                    else if (front.mode != ORIGINAL || front.standardControl && !scan.bottom.standardControl)
                        front = finding(front.view, front.bounds, ORIGINAL, "底部宽区域与下层内容重叠，前景未确认完全不透明");
                }
                childControls |= scan.controls;
                childScroll |= scan.scrolling;
                childOpaque |= scan.opaque;
                if (scan.covered) { childCovered = true; break; }
            }
            // ponytail: standard child geometry is usable even in custom drawing shells; arbitrary
            // dispatchDraw/foreground rendering has no public structural API and is not pixel-inspected.
            // Only standard interactive leaf evidence can be absorbed by its independent operation bar.
            // Unknown/self-drawn/mixed candidates never acquire this flag.
            if ((front == null || front.standardControl) && bottom && view != decor && visible.height() >= minBar
                    && bounds.height() <= maxBar && childControls && !childScroll && !childOpaque)
                front = finding(view, visible, alpha < 0.99f ? ORIGINAL : COLOR,
                        alpha < 0.99f ? "底部操作条半透明，无法确认遮挡关系"
                                : "独立宽底部短条，含可见操作控件且不属于滚动容器");
            boolean unresolvedDrawing = count == 0 && !view.willNotDraw() && Rect.intersects(visible, band);
            if (front == null && substantialOverlay)
                front = finding(view, visible, ORIGINAL, childOpaque
                        ? "底部容器含无法确认的自绘/嵌入内容" : unresolvedDrawing
                        ? "自绘容器无可确认的标准子节点" : "底部容器缺少独立短条及可见操作控件证据");
            return new Scan(front, childControls, childScroll, childOpaque || unresolvedDrawing, covered || childCovered);
        }

        private Finding finding(View view, Rect bounds, int mode, String reason) {
            Rect area = new Rect(bounds);
            area.bottom = Math.min(area.bottom, edge);
            return new Finding(view, area, mode, reason, false);
        }
    }

    private static String embeddedReason(View view) {
        if (view instanceof WebView) return "WebView 内部页面结构不可由标准 View 树确认";
        if (view instanceof SurfaceView || view instanceof TextureView) return "Surface/Texture 自绘内容不可由标准 View 树确认";
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().startsWith("androidx.compose.")) return "Compose 语义/布局不属于标准 View 子树";
        return null;
    }

    private static String leafReason(View view) {
        if (view.getClass() == View.class) return "普通 View 无标准内容结构，无法排除自绘";
        return view.getClass().getName().startsWith("android.widget.") ? null
                : "自定义绘制视图，标准 View API 无法确认内容结构";
    }

    private static boolean isScrolling(View view) {
        // EditText's internal text scrolling is not a scrolling content container.
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
