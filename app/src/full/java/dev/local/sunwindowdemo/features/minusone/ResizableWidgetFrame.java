package dev.local.sunwindowdemo.features.minusone;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.util.List;

final class ResizableWidgetFrame extends FrameLayout {
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int TOP = 4;
    static final int BOTTOM = 8;

    interface EditListener {
        void start(int edge, float x, float y);
        void move(float x, float y);
        void finish(boolean commit);
    }

    private final GestureDetector gesture;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final EditListener listener;
    private final int accent;
    private int allowedEdges;
    private boolean resizing, consumedPress, reorderStarted, pressedOnSelected;
    private int edge;
    private float startX, startY;

    ResizableWidgetFrame(Context context, AppWidgetProviderInfo limits, Runnable select, EditListener listener) {
        super(context);
        this.listener = listener;
        setResizeLimits(limits);
        accent = context.getColor(android.R.color.system_accent1_600);
        setClickable(true);
        setOnLongClickListener(view -> {
            select.run();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public void onLongPress(MotionEvent event) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                ResizableWidgetFrame.super.dispatchTouchEvent(cancel);
                cancel.recycle();
                consumedPress = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                performLongClick();
            }
        });
    }

    void setResizeLimits(AppWidgetProviderInfo limits) {
        int mode = limits == null ? AppWidgetProviderInfo.RESIZE_BOTH : limits.resizeMode;
        allowedEdges = ((mode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0 ? LEFT | RIGHT : 0)
                | ((mode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0 ? TOP | BOTTOM : 0);
        updateGestureExclusion();
        invalidate();
    }

    void setResizing(boolean enabled) {
        resizing = enabled;
        setSelected(enabled);
        updateGestureExclusion();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateGestureExclusion();
    }

    private void updateGestureExclusion() {
        if (!resizing || (allowedEdges & (LEFT | RIGHT)) == 0 || getWidth() == 0 || getHeight() == 0) {
            setSystemGestureExclusionRects(List.of());
            return;
        }
        int grip = dp(22);
        int width = getWidth(), height = getHeight();
        int top = Math.max(0, height / 2 - grip);
        int bottom = Math.min(height, height / 2 + grip);
        setSystemGestureExclusionRects(List.of(
                new Rect(0, top, grip, bottom), new Rect(width - grip, top, width, bottom)));
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            consumedPress = false;
            reorderStarted = false;
            startX = event.getRawX();
            startY = event.getRawY();
            edge = resizing ? gripAt(event.getX(), event.getY()) : 0;
            pressedOnSelected = resizing && edge == 0;
            if (pressedOnSelected) getParent().requestDisallowInterceptTouchEvent(true);
            if (edge != 0) {
                getParent().requestDisallowInterceptTouchEvent(true);
                listener.start(edge, startX, startY);
                return true;
            }
        }
        if (edge != 0 || reorderStarted) return drag(event);
        gesture.onTouchEvent(event);
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (!reorderStarted && (consumedPress || pressedOnSelected) && action == MotionEvent.ACTION_MOVE
                && (Math.abs(event.getRawY() - startY) > slop || Math.abs(event.getRawX() - startX) > slop)) {
            if (!consumedPress) {
                var cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            consumedPress = true;
            reorderStarted = true;
            listener.start(0, startX, startY);
            listener.move(event.getRawX(), event.getRawY());
        }
        if (consumedPress) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean drag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE -> listener.move(event.getRawX(), event.getRawY());
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) listener.move(event.getRawX(), event.getRawY());
                edge = 0;
                reorderStarted = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                listener.finish(event.getActionMasked() == MotionEvent.ACTION_UP);
            }
        }
        return true;
    }

    private int gripAt(float x, float y) {
        int hit = dp(22);
        int width = getWidth(), height = getHeight();
        boolean nearCenterX = Math.abs(x - width / 2f) < hit;
        boolean nearCenterY = Math.abs(y - height / 2f) < hit;
        int vertical = (y < hit ? TOP : y >= height - hit ? BOTTOM : 0) & allowedEdges;
        int horizontal = (x < hit ? LEFT : x >= width - hit ? RIGHT : 0) & allowedEdges;
        if (nearCenterX) return vertical;
        return nearCenterY ? horizontal : 0;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!resizing) return;
        float inset = dp(7), right = getWidth() - inset, bottom = getHeight() - inset;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(accent);
        canvas.drawRoundRect(inset, inset, right, bottom, dp(24), dp(24), paint);
        paint.setStyle(Paint.Style.FILL);
        if ((allowedEdges & (TOP | BOTTOM)) != 0) {
            handle(canvas, getWidth() / 2f, inset);
            handle(canvas, getWidth() / 2f, bottom);
        }
        if ((allowedEdges & (LEFT | RIGHT)) != 0) {
            handle(canvas, inset, getHeight() / 2f);
            handle(canvas, right, getHeight() / 2f);
        }
    }

    private void handle(Canvas canvas, float x, float y) {
        paint.setColor(accent);
        canvas.drawCircle(x, y, dp(8), paint);
        paint.setColor(getContext().getColor(android.R.color.system_accent1_100));
        canvas.drawCircle(x, y, dp(5), paint);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

}
