package dev.local.sunwindowdemo.features.appvolume;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
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
    private final int surface, track, accent, text;
    private final NumberFormat percentage;
    private Collection<View> touchableBounds;
    private List<MediaPlayers.App> rendered = List.of();
    private GainRail dragging;

    SideAppMixerView(Context context, MediaPlayers players, Runnable userActivity) {
        super(context);
        this.players = players;
        this.userActivity = userActivity;
        setTag(TAG);
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        percentage = NumberFormat.getPercentInstance(getResources().getConfiguration().getLocales().get(0));
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xff202a2e : 0xfff1f7f8;
        track = dark ? 0xff3b4a50 : 0xffd8e4e7;
        accent = context.getColor(dark ? android.R.color.system_accent1_200
                : android.R.color.system_accent1_700);
        text = dark ? 0xffe3eaeb : 0xff253237;
    }

    int gap() { return dp(5); }

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
        super.onDetachedFromWindow();
    }

    private void render() {
        if (dragging != null) return;
        var apps = players.apps();
        List<MediaPlayers.App> next = List.copyOf(apps.subList(0, Math.min(MAX_RAILS, apps.size())));
        if (next.equals(rendered)) {
            for (int i = 0; i < getChildCount(); i++) {
                var rail = (GainRail) getChildAt(i);
                rail.setProgress(players.gain(rail.app));
            }
            return;
        }
        rendered = next;
        removeAllViews();
        for (MediaPlayers.App app : next) {
            var layout = new LayoutParams(dp(42), LayoutParams.MATCH_PARENT);
            if (getChildCount() != 0) layout.setMarginStart(gap());
            addView(new GainRail(getContext(), app), layout);
        }
        setVisibility(next.isEmpty() ? GONE : VISIBLE);
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
        private final Runnable pushGain;
        private int gain;
        private boolean pending;

        GainRail(Context context, MediaPlayers.App app) {
            super(context);
            this.app = app;
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
            paint.setColor(surface);
            canvas.drawRoundRect(0f, 0f, width, height, dp(19), dp(19), paint);

            float top = dp(27);
            float bottom = height - dp(32);
            rectangle.set(dp(9), top, width - dp(9), bottom);
            paint.setColor(track);
            canvas.drawRoundRect(rectangle, dp(10), dp(10), paint);
            float endpoint = bottom - (bottom - top) * gain / 100f;
            if (gain > 0) {
                int save = canvas.save();
                canvas.clipRect(rectangle.left, endpoint, rectangle.right, bottom);
                paint.setColor(accent);
                canvas.drawRoundRect(rectangle, dp(10), dp(10), paint);
                canvas.restoreToCount(save);
            }
            paint.setColor(accent);
            canvas.drawRoundRect(dp(6), endpoint - dp(1), width - dp(6), endpoint + dp(1),
                    dp(1), dp(1), paint);

            paint.setColor(text);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(11));
            canvas.drawText(percentage.format(gain / 100.0), width / 2f, dp(18), paint);
            int iconSize = dp(21);
            int iconLeft = (width - iconSize) / 2;
            icon.setBounds(iconLeft, height - dp(26), iconLeft + iconSize, height - dp(26) + iconSize);
            icon.draw(canvas);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (dragging != null) return false;
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
                    getParent().requestDisallowInterceptTouchEvent(false);
                    render();
                }
                default -> { return dragging == this; }
            }
            return true;
        }

        private void move(float y) {
            userActivity.run();
            int height = getHeight() - dp(59);
            if (height <= 0) return;
            int next = Math.max(0, Math.min(100, Math.round((getHeight() - dp(32) - y) * 100f / height)));
            if (next == gain) return;
            setProgress(next);
            if (!pending) {
                pending = true;
                postOnAnimation(pushGain);
            }
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
