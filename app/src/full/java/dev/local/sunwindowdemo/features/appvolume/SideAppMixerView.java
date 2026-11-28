package dev.local.sunwindowdemo.features.appvolume;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.List;

final class SideAppMixerView extends LinearLayout {
    static final String TAG = "dev.nativecomplements.module.sideMixer";
    private static final int MAX_RAILS = 2;
    private final MediaPlayers players;
    private final Runnable userActivity;
    private final Runnable observer = this::render;
    private final int track, accent, text;
    private final NumberFormat percentage;
    private Collection<View> touchableBounds;
    private List<MediaPlayers.App> rendered = List.of();
    private GainRail dragging;
    private boolean expanded, collapsing;

    SideAppMixerView(Context context, MediaPlayers players, Runnable userActivity) {
        super(context);
        this.players = players;
        this.userActivity = userActivity;
        setTag(TAG);
        setOrientation(HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);
        percentage = NumberFormat.getPercentInstance(getResources().getConfiguration().getLocales().get(0));
        track = nativeColor("materialColorSurfaceContainerHighest");
        accent = nativeColor("materialColorPrimary");
        text = nativeColor("materialColorOnSurface");
    }

    private int nativeColor(String name) {
        return getContext().getColor(getResources().getIdentifier(name, "color", "com.android.systemui"));
    }

    int gap() { return dp(5); }
    int footerHeight() { return dp(52); }
    boolean toggleExpanded() {
        if (!players.enabled()) return false;
        if (dragging != null) return true;
        expanded = !expanded;
        userActivity.run();
        if (!expanded && getChildCount() > 0) {
            collapsing = true;
            animateRails(false);
        } else {
            boolean reopening = collapsing;
            collapsing = false;
            render();
            if (reopening) animateRails(true);
        }
        return true;
    }

    private void animateRails(boolean show) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View rail = getChildAt(i);
            rail.animate().cancel();
            var animation = rail.animate().alpha(show ? 1f : 0f).translationX(show ? 0f : dp(18))
                    .setDuration(show ? 200 : 160).setStartDelay((show ? count - 1L - i : i) * 60);
            if (!show && i == count - 1) animation.withEndAction(() -> {
                collapsing = false;
                render();
            });
            else animation.withEndAction(null);
            animation.start();
        }
    }

    void setTouchableBounds(Collection<View> bounds) {
        touchableBounds = bounds;
        if (isAttachedToWindow()) bounds.add(this);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (touchableBounds != null) touchableBounds.add(this);
        players.observe(observer);
        render();
    }

    @Override protected void onDetachedFromWindow() {
        if (touchableBounds != null) touchableBounds.remove(this);
        players.unobserve(observer);
        dragging = null;
        expanded = false;
        collapsing = false;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().cancel();
        super.onDetachedFromWindow();
    }

    private void render() {
        if (dragging != null || collapsing) return;
        var apps = expanded ? players.apps() : List.<MediaPlayers.App>of();
        List<MediaPlayers.App> next = List.copyOf(apps.subList(0, Math.min(MAX_RAILS, apps.size())));
        if (next.equals(rendered)) {
            for (int i = 0; i < getChildCount(); i++) {
                var rail = (GainRail) getChildAt(i);
                rail.setProgress(players.gain(rail.app));
            }
            return;
        }
        boolean opening = rendered.isEmpty() && !next.isEmpty();
        rendered = next;
        removeAllViews();
        for (MediaPlayers.App app : next) {
            var layout = new LayoutParams(dp(52), LayoutParams.MATCH_PARENT);
            if (getChildCount() != 0) layout.setMarginStart(gap());
            var rail = new GainRail(getContext(), app);
            if (opening) {
                rail.setAlpha(0f);
                rail.setTranslationX(dp(18));
            }
            addView(rail, layout);
        }
        setVisibility(next.isEmpty() ? GONE : VISIBLE);
        if (opening) animateRails(true);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private final class GainRail extends View {
        final MediaPlayers.App app;
        private final Drawable icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();
        private final Path segment = new Path();
        private final Runnable pushGain;
        private int gain;
        private boolean pending;
        private float overscroll;
        private ValueAnimator rebound;

        GainRail(Context context, MediaPlayers.App app) {
            super(context);
            this.app = app;
            var background = context.getDrawable(getResources().getIdentifier(
                    "volume_dialog_background", "drawable", "com.android.systemui")).mutate();
            if (background instanceof GradientDrawable shape) shape.setCornerRadius(dp(26));
            setBackground(background);
            var constant = app.icon().getConstantState();
            icon = constant == null ? app.icon() : constant.newDrawable(getResources());
            pushGain = () -> {
                pending = false;
                players.preview(app, gain);
            };
            setContentDescription(app.name());
            setFocusable(true);
            setClickable(true);
            setProgress(players.gain(app));
        }

        void setProgress(int value) {
            gain = Math.max(0, Math.min(100, value));
            setStateDescription(percentage.format(gain / 100.0));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (height < dp(70)) return;
            float top = trackTop();
            float bottom = trackBottom();
            rectangle.set(dp(6), top, width - dp(6), bottom);
            paint.setColor(track);
            float endpoint = bottom - (bottom - top) * gain / 100f;
            if (gain < 100) {
                rectangle.bottom = Math.max(top, endpoint - dp(8));
                drawSegment(canvas, dp(13), dp(2));
            }
            if (gain > 0) {
                rectangle.top = Math.min(bottom, endpoint + dp(8));
                rectangle.bottom = bottom;
                paint.setColor(accent);
                drawSegment(canvas, dp(2), dp(13));
            }
            paint.setColor(accent);
            canvas.drawRoundRect(dp(5), endpoint - dp(2), width - dp(5), endpoint + dp(2),
                    dp(2), dp(2), paint);

            paint.setColor(text);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(12));
            if (players.showPercentage())
                canvas.drawText(percentage.format(gain / 100.0), width / 2f, dp(27), paint);
            int iconSize = dp(32);
            int iconLeft = (width - iconSize) / 2;
            int iconTop = height - (width + iconSize) / 2;
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            icon.draw(canvas);
        }

        private void drawSegment(Canvas canvas, float topRadius, float bottomRadius) {
            if (rectangle.height() <= 0) return;
            segment.reset();
            segment.addRoundRect(rectangle, new float[] {topRadius, topRadius, topRadius, topRadius,
                    bottomRadius, bottomRadius, bottomRadius, bottomRadius}, Path.Direction.CW);
            canvas.drawPath(segment, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (dragging != null) return false;
                    if (rebound != null) rebound.cancel();
                    dragging = this;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    move(event.getY());
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (dragging != this) return false;
                    move(event.getY());
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging != this) return false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        move(event.getY());
                        performClick();
                    }
                    removeCallbacks(pushGain);
                    pending = false;
                    players.save(app, gain);
                    dragging = null;
                    rebound = ValueAnimator.ofFloat(overscroll, 0f);
                    rebound.setDuration(220);
                    rebound.setInterpolator(new OvershootInterpolator(.6f));
                    rebound.addUpdateListener(value -> {
                        overscroll = (float) value.getAnimatedValue();
                        setTranslationY(overscroll);
                    });
                    rebound.start();
                    getParent().requestDisallowInterceptTouchEvent(false);
                    render();
                }
                default -> { return dragging == this; }
            }
            return true;
        }

        private int trackTop() { return dp(players.showPercentage() ? 46 : 30); }
        private int trackBottom() { return getHeight() - footerHeight() - dp(2); }

        private void move(float y) {
            userActivity.run();
            int height = trackBottom() - trackTop();
            if (height <= 0) return;
            float extra = y < trackTop() ? y - trackTop() : y > trackBottom() ? y - trackBottom() : 0f;
            overscroll = Math.copySign(Math.min(dp(56), Math.abs(extra) / 4f), extra);
            setTranslationY(overscroll);
            int next = Math.max(0, Math.min(100, Math.round((trackBottom() - y) * 100f / height)));
            if (next == gain) return;
            setProgress(next);
            if (!pending) {
                pending = true;
                postOnAnimation(pushGain);
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (rebound != null) rebound.cancel();
            removeCallbacks(pushGain);
            super.onDetachedFromWindow();
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(new AccessibilityNodeInfo.RangeInfo(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, gain));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
                setProgress(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
                players.save(app, gain);
                userActivity.run();
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }
}
