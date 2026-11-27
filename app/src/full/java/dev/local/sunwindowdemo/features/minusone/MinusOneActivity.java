package dev.local.sunwindowdemo.features.minusone;

import android.animation.ValueAnimator;
import dev.local.sunwindowdemo.R;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class MinusOneActivity extends Activity {
    private static final int HOST_ID = 9090;
    private static final int BIND = 1;
    private static final int CONFIGURE = 2;
    private static final int PICK_WIDGET = 3;
    private static final String PICK_SESSION = "dev.nativecomplements.module.action.PICK_SESSION";
    private static final int NO_WIDGET = AppWidgetManager.INVALID_APPWIDGET_ID;
    private static final String IDS = "widgets";
    private static final String SLOTS = "widget_slots";
    private static final String DIM = "dim_wallpaper";
    private static final String TRANSPARENT = "transparent_widgets";
    private static final int APPEARANCE_DURATION = 200;
    private static MinusOneActivity listeningActivity;
    private final List<Integer> widgets = new ArrayList<>();
    private final Map<Integer, WidgetCanvas.Slot> slots = new LinkedHashMap<>();
    private final Map<Integer, View> widgetViews = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean awaitingPicker;
    private final Runnable pickerTimeout = () -> {
        if (!awaitingPicker) return;
        awaitingPicker = false;
        pickerUnavailable();
    };
    private final ContentObserver pickerObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { launchWidgetPicker(); }
    };
    private final ContentObserver gridObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            if (!Objects.equals(workspaceGrid, MinusOneSettings.widgetGrid(MinusOneActivity.this))) render();
        }
    };
    private AppWidgetManager widgetManager;
    private AppWidgetHost host;
    private Sha…332 tokens truncated…      if (saved != null) {
            pending = saved.getInt("pending", NO_WIDGET);
            pickerSessionStarted = saved.getBoolean("picker_session_started");
        }
        if (isPickerSession()) {
            loadPresentation();
            setContentView(new View(this));
        } else {
            render();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(MinusOneSettings.PICKER_READY_URI, false, pickerObserver);
        if (isPickerSession()) {
            if (!pickerSessionStarted) {
                getWindow().getDecorView().post(() -> {
                    if (isFinishing() || !hasWindowFocus()) return;
                    startPickerSession();
                });
            }
            return;
        }
        getContentResolver().registerContentObserver(MinusOneSettings.WIDGET_GRID_URI, false, gridObserver);
        var saved = readWidgets();
        if (!widgets.equals(saved) || !Objects.equals(loadedSlots, preferences.getString(SLOTS, ""))
                || !Objects.equals(workspaceGrid, MinusOneSettings.widgetGrid(this))) {
            widgets.clear();
            widgets.addAll(saved);
            render();
        } else {
            if (dimWallpaper != preferences.getBoolean(DIM, false)) updateWallpaperDim();
        }
        if (listeningActivity != this) {
            // The preview and the embedded page share one system widget host ID.
            if (listeningActivity != null) listeningActivity.host.stopListening();
            listeningActivity = this;
            host.startListening();
        }
    }

    @Override protected void onPause() {
        if (optionsPopup != null) optionsPopup.dismiss();
        awaitingPicker = false;
        main.removeCallbacks(pickerTimeout);
        getContentResolver().unregisterContentObserver(pickerObserver);
        if (!isPickerSession()) getContentResolver().unregisterContentObserver(gridObserver);
        if (edit != null) finishCanvasEdit(false);
        if (resizeTarget != null) selectResize(null);
        if (listeningActivity == this) {
            listeningActivity = null;
            host.stopListening();
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("pending", pending);
        state.putBoolean("picker_session_started", pickerSessionStarted);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isPickerSession()) startPickerSession();
    }

    @Override protected void onDestroy() {
        if (widgetAppearance != null) widgetAppearance.cancel();
        if (dimAppearance != null) dimAppearance.cancel();
        if (isFinishing()) abandonWidget();
        super.onDestroy();
    }

    private void loadPresentation() {
        workspaceGrid = MinusOneSettings.widgetGrid(this);
        int feedWidth = dp(Math.max(100, getResources().getConfiguration().screenWidthDp - 32));
        grid = workspaceGrid.valid() ? workspaceGrid : new WidgetGrid(4, 5, feedWidth / 4, dp(84), 0, dp(8));
        if (grid.width(grid.columns()) > feedWidth) {
            grid = new WidgetGrid(grid.columns(), grid.rows(),
                    (feedWidth - (grid.columns() - 1) * grid.gapX()) / grid.columns(),
                    grid.cellHeight(), grid.gapX(), grid.gapY());
        }
        widgetBackgroundOpacity = widgetBackgroundTarget();
        loadSlots();
    }

    private float widgetBackgroundTarget() {
        return preferences.getBoolean(TRANSPARENT, false) ? 0f : 1f;
    }

    private void render() {
        if (optionsPopup != null) optionsPopup.dismiss();
        if (edit != null) finishCanvasEdit(false);
        if (resizeTarget != null) selectResize(null);
        if (widgetAppearance != null) widgetAppearance.cancel();
        if (dimAppearance != null) dimAppearance.cancel();
        widgetViews.clear();
        loadPresentation();
        dimWallpaper = preferences.getBoolean(DIM, false);
        int previousScroll = scroll == null ? 0 : scroll.getScrollY();
        var root = column();
        wallpaperDim = new ColorDrawable(Color.BLACK);
        wallpaperDim.setAlpha(dimWallpaper ? 64 : 0);
        root.setBackground(wallpaperDim);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(16) + safe.left, dp(6) + safe.top, dp(16) + safe.right, safe.bottom);
            return insets;
        });
        var headerLayout = new LinearLayout(this);
        header = headerLayout;
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        var title = new TextView(this);
        title.setText("负一屏");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setShadowLayer(dp(3), 0, dp(1), 0xb0000000);
        title.setAccessibilityHeading(true);
        title.setPaddingRelative(dp(6), 0, 0, 0);
        headerLayout.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        var menu = new ImageButton(this);
        menu.setImageResource(R.drawable.nc_minus_one_menu);
        menu.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        menu.setBackground(rounded(0x60000000, 24));
        menu.setContentDescription("负一屏选项");
        menu.setOnClickListener(this::showOptions);
        headerLayout.addView(menu, dp(48), dp(48));
        root.addView(headerLayout, -1, dp(56));

        scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            positionRemoveAction();
        });
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        var feed = column();
        feed.setPadding(0, dp(4), 0, dp(16));
        canvas = new WidgetCanvas(this, grid);
        var canvasLayout = new LinearLayout.LayoutParams(grid.width(grid.columns()), canvasHeight(0));
        canvasLayout.gravity = Gravity.CENTER_HORIZONTAL;
        feed.addView(canvas, canvasLayout);
        overlay = new FrameLayout(this);
        for (int id : widgets) addWidgetToCanvas(id);
        var add = new Button(this, null, android.R.attr.borderlessButtonStyle);
        add.setText("＋ 添加微件");
        add.setAllCaps(false);
        add.setLetterSpacing(0);
        add.setTextColor(Color.WHITE);
        add.setTextSize(15);
        add.setBackground(rounded(0x68000000, 24));
        add.setOnClickListener(view -> openWidgetPicker());
        var addLayout = new LinearLayout.LayoutParams(-2, dp(48));
        addLayout.gravity = Gravity.CENTER_HORIZONTAL;
        addLayout.topMargin = dp(12);
        feed.addView(add, addLayout);
        scroll.addView(feed, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        overlay.addView(root, 0, new FrameLayout.LayoutParams(-1, -1));
        setContentView(overlay);
        if (previousScroll != 0) scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private void updateWallpaperDim() {
        dimWallpaper = preferences.getBoolean(DIM, false);
        if (dimAppearance != null) dimAppearance.cancel();
        int target = dimWallpaper ? 64 : 0;
        if (wallpaperDim.getAlpha() == target) return;
        dimAppearance = ValueAnimator.ofInt(wallpaperDim.getAlpha(), target);
        dimAppearance.setDuration(APPEARANCE_DURATION);
        dimAppearance.setInterpolator(new DecelerateInterpolator());
        dimAppearance.addUpdateListener(value -> wallpaperDim.setAlpha((Integer) value.getAnimatedValue()));
        dimAppearance.start();
    }

    private void updateWidgetAppearance() {
        if (edit != null) finishCanvasEdit(false);
        if (resizeTarget != null) selectResize(null);
        if (widgetAppearance != null) widgetAppearance.cancel();
        float target = widgetBackgroundTarget();
        if (widgetBackgroundOpacity == target) return;
        widgetAppearance = ValueAnimator.ofFloat(widgetBackgroundOpacity, target);
        widgetAppearance.setDuration(APPEARANCE_DURATION);
        widgetAppearance.setInterpolator(new DecelerateInterpolator());
        widgetAppearance.addUpdateListener(value -> {
            widgetBackgroundOpacity = (Float) value.getAnimatedValue();
            for (var view : widgetViews.values()) {
                if (view instanceof ResizableWidgetFrame frame
                        && frame.getChildAt(0) instanceof FeedWidgetView widget) widget.updatePanelOpacity();
            }
        });
        widgetAppearance.start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (resizeTarget != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            var bounds = new Rect();
            resizeTarget.getGlobalVisibleRect(bounds);
            int x = Math.round(event.getRawX()), y = Math.round(event.getRawY());
            if (!bounds.contains(x, y)) {
                if (removeAction == null || !removeAction.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) {
                    selectResize(null);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void selectResize(ResizableWidgetFrame target) { selectResize(target, null); }

    private void selectResize(ResizableWidgetFrame target, View action) {
        if (resizeTarget != null) resizeTarget.setResizing(false);
        if (removeAction != null) removeAction.setVisibility(View.GONE);
        resizeTarget = target;
        removeAction = action;
        if (target != null) target.setResizing(true);
        if (action != null) {
            positionRemoveAction();
            action.setVisibility(View.VISIBLE);
        }
    }

    private void positionRemoveAction() {
        if (resizeTarget == null || removeAction == null || overlay == null) return;
        int[] location = new int[2];
        overlay.getLocationInWindow(location);
        int rootY = location[1];
        header.getLocationInWindow(location);
        int headerTop = location[1] - rootY;
        removeAction.setTranslationY(headerTop + (header.getHeight() - dp(44)) / 2f);
    }

    private void openWidgetPicker() {
        if (getComponentName().getClassName().endsWith(".MinusOneOverlay")) {
            // Pixel Launcher deletes its overlay TaskFragment when a new Activity
            // takes focus. Keep the picker result and widget binding outside that fragment.
            var session = new Intent(this, MinusOneActivity.class).setAction(PICK_SESSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try {
                startActivity(session);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                pickerUnavailable();
            }
            return;
        }
        requestWidgetPicker();
    }

    private boolean isPickerSession() { return PICK_SESSION.equals(getIntent().getAction()); }

    private void startPickerSession() {
        if (pickerSessionStarted || isFinishing()) return;
        pickerSessionStarted = true;
        requestWidgetPicker();
    }

    private void requestWidgetPicker() {
        if (pending != NO_WIDGET || awaitingPicker) return;
        awaitingPicker = true;
        main.postDelayed(pickerTimeout, 2500);
        MinusOneSettings.requestWidgetPicker(this);
    }

    private void launchWidgetPicker() {
        var picker = MinusOneSettings.takeWidgetPicker();
        if (!awaitingPicker || picker == null) return;
        awaitingPicker = false;
        main.removeCallbacks(pickerTimeout);
        try {
            startIntentSenderForResult(picker.getIntentSender(), PICK_WIDGET, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | SecurityException unavailable) {
            pickerUnavailable();
        }
    }

    private void pickerUnavailable() {
        Toast.makeText(this, "无法打开桌面微件面板", Toast.LENGTH_SHORT).show();
        closePickerSession();
    }

    private void closePickerSession() {
        if (!isPickerSession()) return;
        if (isTaskRoot()) finishAndRemoveTask();
        else finish();
    }

    private void showOptions(View anchor) {
        if (optionsPopup != null) {
            optionsPopup.dismiss();
            return;
        }
        var add = new FeedOptionsPopup.Item("添加微件", null, android.R.drawable.ic_input_add, this::openWidgetPicker);
        var popup = new FeedOptionsPopup(anchor, List.of(
                List.of(add), List.of(feedToggle("调暗壁纸", DIM), feedToggle("小部件透明", TRANSPARENT))));
        popup.setOnDismissListener(() -> optionsPopup = null);
        optionsPopup = popup;
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END);
    }

    private FeedOptionsPopup.Item feedToggle(String label, String key) {
        boolean checked = preferences.getBoolean(key, false);
        return new FeedOptionsPopup.Item(label, checked, 0, () -> {
            preferences.edit().putBoolean(key, !checked).apply();
            if (DIM.equals(key)) updateWallpaperDim();
            else updateWidgetAppearance();
        });
    }

    private void addWidgetToCanvas(int id) {
        var info = widgetManager.getAppWidgetInfo(id);
        var remove = new Button(this, null, android.R.attr.borderlessButtonStyle);
        remove.setText(info == null ? "×  移除不可用的微件" : "×  移除");
        remove.setTextColor(getColor(android.R.color.system_accent1_900));
        remove.setAllCaps(false);
        remove.setLetterSpacing(0);
        remove.setBackground(rounded(getColor(android.R.color.system_accent1_100), 24));
        remove.setOnClickListener(view -> removeWidget(id));
        var slot = slots.get(id);
        if (info == null) {
            var placeholder = new FrameLayout.LayoutParams(grid.width(slot.columns()), dp(48));
            placeholder.leftMargin = canvas.left(slot);
            placeholder.topMargin = canvas.top(slot);
            canvas.addView(remove, placeholder);
            widgetViews.put(id, remove);
            return;
        }
        remove.setVisibility(View.GONE);
        remove.setElevation(dp(5));
        overlay.addView(remove, new FrameLayout.LayoutParams(dp(110), dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        var widget = host.createView(this, id, info);
        var frame = new ResizableWidgetFrame(this, null,
                () -> selectResize((ResizableWidgetFrame) widget.getParent(), remove),
                new ResizableWidgetFrame.EditListener() {
                    @Override public void start(int edge, float x, float y) {
                        startCanvasEdit(id, (ResizableWidgetFrame) widget.getParent(), null, edge, x, y);
                    }
                    @Override public void move(float x, float y) { moveCanvasEdit(x, y); }
                    @Override public void finish(boolean commit) { finishCanvasEdit(commit); }
                });
        frame.addView(widget, new FrameLayout.LayoutParams(-1, -1));
        frame.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (resizeTarget == frame) positionRemoveAction();
        });
        canvas.place(frame, slot);
        canvas.addView(frame);
        widgetViews.put(id, frame);
    }

    private void removeWidget(int id) {
        host.deleteAppWidgetId(id);
        widgets.remove(Integer.valueOf(id));
        slots.remove(id);
        preferences.edit().remove("widget_width_" + id).remove("widget_height_" + id)
                .remove("widget_columns_" + id).remove("widget_rows_" + id).apply();
        saveWidgets();
        render();
    }

    private static final class CanvasEdit {
        final int id, edge, canvasY;
        final float startX, startY;
        final ResizableWidgetFrame frame;
        final AppWidgetProviderInfo limits;
        final WidgetCanvas.Slot original;
        WidgetCanvas.Slot candidate, lastValid;
        Map<Integer, WidgetCanvas.Slot> displaced = Map.of();
        float x, y;
        boolean valid;

        CanvasEdit(int id, int edge, int canvasY, float x, float y, ResizableWidgetFrame frame,
                   AppWidgetProviderInfo limits, WidgetCanvas.Slot original) {
            this.id = id;
            this.edge = edge;
            this.canvasY = canvasY;
            this.startX = this.x = x;
            this.startY = this.y = y;
            this.frame = frame;
            this.limits = limits;
            this.original = candidate = lastValid = original;
            valid = true;
        }
    }

    private final int[] canvasLocation = new int[2];
    private final int[] scrollLocation = new int[2];
    private final Runnable scrollDuringEdit = new Runnable() {
        @Override public void run() {
            var current = edit;
            if (current == null || current.edge != 0) return;
            scroll.getLocationOnScreen(scrollLocation);
            int buffer = dp(68);
            float top = scrollLocation[1], bottom = top + scroll.getHeight();
            float amount = Math.min(0, current.y - top - buffer) + Math.max(0, current.y - bottom + buffer);
            if (amount != 0) {
                int before = scroll.getScrollY();
                scroll.scrollBy(0, Math.round(Math.max(-dp(16), Math.min(dp(16), amount / 5))));
                if (before != scroll.getScrollY()) moveCanvasEdit(current.x, current.y);
            }
            scroll.postOnAnimation(this);
        }
    };

    private void startCanvasEdit(int id, ResizableWidgetFrame frame, AppWidgetProviderInfo limits,
                                 int edge, float x, float y) {
        if (edit != null) finishCanvasEdit(false);
        frame.animate().cancel();
        frame.setTranslationX(0);
        frame.setTranslationY(0);
        canvas.getLocationOnScreen(canvasLocation);
        edit = new CanvasEdit(id, edge, canvasLocation[1], x, y, frame, limits, slots.get(id));
        frame.setTranslationZ(dp(12));
        if (removeAction != null) removeAction.setVisibility(View.INVISIBLE);
        setCanvasHeight(canvasHeight(grid.rows()));
        canvas.setPreview(edit.original, true);
        if (edge == 0) {
            frame.setAlpha(.82f);
            scroll.postOnAnimation(scrollDuringEdit);
        }
    }

    private void moveCanvasEdit(float x, float y) {
        var current = edit;
        if (current == null) return;
        current.x = x;
        current.y = y;
        canvas.getLocationOnScreen(canvasLocation);
        float deltaX = x - current.startX, deltaY = y - current.startY + current.canvasY - canvasLocation[1];
        int stepX = grid.cellWidth() + grid.gapX(), stepY = grid.cellHeight() + grid.gapY();
        int dx = Math.round(deltaX / stepX), dy = Math.round(deltaY / stepY);
        var original = current.original;
        WidgetCanvas.Slot candidate;
        if (current.edge == 0) {
            int column = WidgetGrid.clamp(original.column() + dx, 0, grid.columns() - original.columns());
            int row = WidgetGrid.clamp(original.row() + dy, 0, canvasRowLimit(original.rows()));
            candidate = new WidgetCanvas.Slot(column, row, original.columns(), original.rows());
            current.frame.setTranslationX(deltaX);
            current.frame.setTranslationY(deltaY);
            current.frame.invalidate();
        } else {
            candidate = resizedSlot(current, dx, dy);
        }
        if (candidate.equals(current.candidate)) return;
        Map<Integer, WidgetCanvas.Slot> moves = current.edge == 0
                ? WidgetCanvas.rearrange(candidate, grid.columns(), canvasRows(grid.rows()), slots, current.id)
                : WidgetCanvas.fits(candidate, grid.columns(), slots, current.id) ? Map.of() : null;
        boolean valid = moves != null;
        if (current.edge == 0) previewDisplacement(current, valid ? moves : Map.of());
        if (valid) {
            current.frame.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK);
        }
        current.candidate = candidate;
        current.valid = valid;
        if (valid && !candidate.equals(current.lastValid)) {
            current.lastValid = candidate;
            if (current.edge != 0) canvas.place(current.frame, candidate);
        }
        canvas.setPreview(candidate, valid);
    }

    private int canvasRowLimit(int height) {
        return Math.max(0, canvasRows(grid.rows()) - height);
    }

    private void previewDisplacement(CanvasEdit current, Map<Integer, WidgetCanvas.Slot> moves) {
        for (var entry : slots.entrySet()) {
            int id = entry.getKey();
            var original = entry.getValue();
            var previous = current.displaced.getOrDefault(id, original);
            var next = moves.getOrDefault(id, original);
            if (previous.equals(next)) continue;
            var view = widgetViews.get(id);
            view.animate().translationX(canvas.left(next) - view.getLeft())
                    .translationY(canvas.top(next) - view.getTop())
                    .setDuration(150).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(view::invalidate).start();
        }
        current.displaced = moves;
    }

    private WidgetCanvas.Slot resizedSlot(CanvasEdit current, int dx, int dy) {
        var slot = current.original;
        var limits = current.limits;
        int minW = limits == null ? 1 : Math.min(slot.columns(), grid.minColumns(limits.minResizeWidth));
        int minH = limits == null ? 1 : Math.min(slot.rows(), grid.minRows(limits.minResizeHeight));
        int maxW = limits == null || limits.maxResizeWidth <= 0 ? grid.columns()
                : Math.max(slot.columns(), grid.maxColumns(limits.maxResizeWidth));
        int maxH = limits == null || limits.maxResizeHeight <= 0 ? grid.rows()
                : Math.max(slot.rows(), grid.maxRows(limits.maxResizeHeight));
        int left = slot.column(), top = slot.row(), right = slot.right(), bottom = slot.bottom();
        switch (current.edge) {
            case ResizableWidgetFrame.LEFT -> left = WidgetGrid.clamp(left + dx, Math.max(0, right - maxW), right - minW);
            case ResizableWidgetFrame.RIGHT -> right = WidgetGrid.clamp(right + dx, left + minW, Math.min(grid.columns(), left + maxW));
            case ResizableWidgetFrame.TOP -> top = WidgetGrid.clamp(top + dy, Math.max(0, bottom - maxH), bottom - minH);
            case ResizableWidgetFrame.BOTTOM -> bottom = WidgetGrid.clamp(bottom + dy, top + minH, top + maxH);
        }
        return new WidgetCanvas.Slot(left, top, right - left, bottom - top);
    }

    private void finishCanvasEdit(boolean commit) {
        var current = edit;
        if (current == null) return;
        edit = null;
        scroll.removeCallbacks(scrollDuringEdit);
        var target = current.original;
        if (commit) {
            if (current.valid) target = current.candidate;
            else if (current.edge != 0) target = current.lastValid;
            if (!target.equals(current.original) || !current.displaced.isEmpty()) {
                slots.putAll(current.displaced);
                slots.put(current.id, target);
                saveWidgets();
            }
        }
        canvas.setPreview(null, false);
        if (current.edge != 0) {
            canvas.place(current.frame, target);
            resetEditedFrame(current.frame);
            return;
        }
        for (var entry : current.displaced.entrySet()) {
            var position = commit ? entry.getValue() : slots.get(entry.getKey());
            settleView(widgetViews.get(entry.getKey()), position, null);
        }
        var frame = current.frame;
        settleView(frame, target, () -> {
            if (frame.getParent() == canvas && edit == null) resetEditedFrame(frame);
            else frame.setTranslationZ(0);
        });
    }

    private void settleView(View view, WidgetCanvas.Slot target, Runnable after) {
        view.animate().cancel();
        float visualX = view.getX(), visualY = view.getY();
        canvas.relocate(view, target);
        view.setTranslationX(visualX - view.getLeft());
        view.setTranslationY(visualY - view.getTop());
        view.animate().translationX(0).translationY(0).alpha(1f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    if (after != null) after.run();
                    view.invalidate();
                }).start();
    }

    private void resetEditedFrame(ResizableWidgetFrame frame) {
        frame.setTranslationX(0);
        frame.setTranslationY(0);
        frame.setTranslationZ(0);
        frame.setAlpha(1);
        setCanvasHeight(canvasHeight(0));
        if (resizeTarget == frame && removeAction != null) {
            positionRemoveAction();
            removeAction.setVisibility(View.VISIBLE);
        }
    }

    private void setCanvasHeight(int height) {
        var params = canvas.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        canvas.setLayoutParams(params);
    }

    private int canvasHeight(int extra) {
        if (slots.isEmpty()) return 0;
        return grid.height(canvasRows(extra));
    }

    private int canvasRows(int extra) {
        int used = slots.values().stream().mapToInt(WidgetCanvas.Slot::bottom).max().orElse(0);
        return Math.max(grid.rows(), used + extra);
    }

    private void bindSelection(Intent result) {
        var provider = result.getParcelableExtra(WidgetPickerHandoff.COMPONENT, ComponentName.class);
        if (provider == null || pending != NO_WIDGET) return;
        var profile = result.getParcelableExtra(WidgetPickerHandoff.USER, UserHandle.class);
        for (var info : widgetManager.getInstalledProvidersForPackage(provider.getPackageName(), profile)) {
            if (provider.equals(info.provider)) {
                bindWidget(info);
                return;
            }
        }
        Toast.makeText(this, "未找到所选微件", Toast.LENGTH_SHORT).show();
        closePickerSession();
    }

    private void bindWidget(AppWidgetProviderInfo info) {
        pending = host.allocateAppWidgetId();
        try {
            if (widgetManager.bindAppWidgetIdIfAllowed(pending, info.getProfile(), info.provider, null)) {
                configureWidget();
                return;
            }
            var intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            startActivityForResult(intent, BIND);
        } catch (RuntimeException failure) {
            abandonWidget();
            Toast.makeText(this, "无法绑定此小部件", Toast.LENGTH_SHORT).show();
            closePickerSession();
        }
    }

    private void configureWidget() {
        var info = widgetManager.getAppWidgetInfo(pending);
        if (info == null) {
            abandonWidget();
            closePickerSession();
            return;
        }
        if (info.configure == null
                || (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0) {
            commitWidget();
            return;
        }
        try {
            host.startAppWidgetConfigureActivityForResult(this, pending, 0, CONFIGURE, null);
        } catch (RuntimeException failure) {
            abandonWidget();
            Toast.makeText(this, "无法打开该小部件的设置", Toast.LENGTH_SHORT).show();
            closePickerSession();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_WIDGET) {
            if (result == RESULT_OK && data != null) bindSelection(data);
            else closePickerSession();
            return;
        }
        if ((code != BIND && code != CONFIGURE) || pending == NO_WIDGET) return;
        if (result != RESULT_OK) {
            abandonWidget();
            closePickerSession();
        }
        else if (code == BIND) configureWidget();
        else commitWidget();
    }

    private void abandonWidget() {
        if (pending != NO_WIDGET) host.deleteAppWidgetId(pending);
        pending = NO_WIDGET;
    }

    private void commitWidget() {
        var size = oldSize(pending);
        slots.put(pending, WidgetCanvas.firstFit(size.columns(), size.rows(), grid.columns(), slots));
        widgets.add(pending);
        pending = NO_WIDGET;
        saveWidgets();
        if (isPickerSession()) closePickerSession();
        else render();
    }

    private void saveWidgets() {
        var serialized = new java.util.StringJoiner(";");
        slots.forEach((id, slot) -> serialized.add(id + "," + slot.column() + "," + slot.row()
                + "," + slot.columns() + "," + slot.rows()));
        loadedSlots = serialized.toString();
        preferences.edit().putString(IDS, android.text.TextUtils.join(",", widgets))
                .putString(SLOTS, loadedSlots).apply();
    }

    private void loadSlots() {
        loadedSlots = preferences.getString(SLOTS, "");
        var saved = new LinkedHashMap<Integer, WidgetCanvas.Slot>();
        for (String item : loadedSlots.split(";")) {
            var parts = item.split(",");
            if (parts.length != 5) continue;
            try {
                int id = Integer.parseInt(parts[0]), column = Integer.parseInt(parts[1]), row = Integer.parseInt(parts[2]);
                int width = Integer.parseInt(parts[3]), height = Integer.parseInt(parts[4]);
                if (column >= 0 && column < grid.columns() && row >= 0 && row <= 32767 && width > 0 && height > 0) {
                    saved.put(id, new WidgetCanvas.Slot(column, row, Math.min(width, grid.columns() - column),
                            Math.min(height, grid.rows())));
                }
            } catch (NumberFormatException ignored) {
                // Recreate unreadable placements from the widget's saved size.
            }
        }
        slots.clear();
        for (int id : widgets) {
            var slot = saved.get(id);
            if (slot != null && WidgetCanvas.fits(slot, grid.columns(), slots, id)) slots.put(id, slot);
        }
        for (int id : widgets) {
            if (slots.containsKey(id)) continue;
            var size = oldSize(id);
            if (loadedSlots.isEmpty()) {
                // The previous version stacked and centered each widget in its own row.
                int row = slots.values().stream().mapToInt(WidgetCanvas.Slot::bottom).max().orElse(0);
                slots.put(id, new WidgetCanvas.Slot((grid.columns() - size.columns()) / 2, row,
                        size.columns(), size.rows()));
            } else {
                slots.put(id, WidgetCanvas.firstFit(size.columns(), size.rows(), grid.columns(), slots));
            }
        }
    }

    private WidgetCanvas.Slot oldSize(int id) {
        var info = widgetManager.getAppWidgetInfo(id);
        int defaultColumns = info == null ? grid.columns()
                : info.targetCellWidth > 0 ? info.targetCellWidth : grid.minColumns(info.minWidth);
        int defaultRows = info == null ? 1
                : info.targetCellHeight > 0 ? info.targetCellHeight : grid.minRows(info.minHeight);
        int legacyWidth = preferences.getInt("widget_width_" + id, 0);
        int legacyHeight = preferences.getInt("widget_height_" + id, 0);
        int columns = preferences.getInt("widget_columns_" + id,
                legacyWidth > 0 ? grid.columnSpan(dp(legacyWidth)) : defaultColumns);
        int rows = preferences.getInt("widget_rows_" + id,
                legacyHeight > 0 ? grid.rowSpan(dp(legacyHeight)) : defaultRows);
        return new WidgetCanvas.Slot(0, 0, WidgetGrid.clamp(columns, 1, grid.columns()),
                WidgetGrid.clamp(rows, 1, grid.rows()));
    }

    private List<Integer> readWidgets() {
        var saved = new ArrayList<Integer>();
        for (String value : preferences.getString(IDS, "").split(",")) {
            if (!value.isEmpty()) saved.add(Integer.parseInt(value));
        }
        return saved;
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable rounded(int color, int radius) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int pixelsToDp(int value) { return Math.round(value / getResources().getDisplayMetrics().density); }

    private final class FeedWidgetView extends AppWidgetHostView {
        private record Opacity(Drawable drawable, int alpha) {}

        private final Map<View, Opacity> panelBackgrounds = new WeakHashMap<>();
        private final Map<ImageView, Opacity> panelImages = new WeakHashMap<>();
        private int lastWidth, lastHeight;
        private final Runnable updateSize = () -> {
            int width = pixelsToDp(getWidth()), height = pixelsToDp(getHeight());
            if (width <= 0 || height <= 0 || (width == lastWidth && height == lastHeight)) return;
            lastWidth = width;
            lastHeight = height;
            updateAppWidgetSize(new Bundle(), List.of(new SizeF(width, height)));
        };

        FeedWidgetView(Context context) { super(context); }

        @Override public void updateAppWidget(RemoteViews remoteViews) {
            restorePanelOpacity();
            super.updateAppWidget(remoteViews);
            updatePanelOpacity();
            if (widgetBackgroundOpacity < 1f) post(this::updatePanelOpacity);
        }

        void updatePanelOpacity() {
            if (!isAttachedToWindow()) return;
            if (widgetBackgroundOpacity >= 1f) {
                restorePanelOpacity();
                return;
            }
            View background = findViewById(android.R.id.background);
            if (background != null && background != this) fadeBackground(background);
            long area = (long) getWidth() * getHeight();
            if (area > 0) fadeLargeBackgrounds(this, area / 2);
        }

        private void restorePanelOpacity() {
            panelBackgrounds.forEach((view, value) -> {
                if (view.getBackground() == value.drawable()) value.drawable().setAlpha(value.alpha());
            });
            panelImages.forEach((image, value) -> {
                if (image.getDrawable() == value.drawable()) image.setImageAlpha(value.alpha());
            });
            panelBackgrounds.clear();
            panelImages.clear();
        }

        private void fadeBackground(View view) {
            var drawable = view.getBackground();
            if (drawable == null) return;
            var saved = panelBackgrounds.get(view);
            if (saved == null || saved.drawable() != drawable) {
                var local = drawable.mutate();
                if (local != drawable) view.setBackground(local);
                saved = new Opacity(local, local.getAlpha());
                panelBackgrounds.put(view, saved);
            }
            saved.drawable().setAlpha(Math.round(saved.alpha() * widgetBackgroundOpacity));
        }

        private void fadeLargeBackgrounds(ViewGroup group, long threshold) {
            if (group instanceof AdapterView<?>) return;
            for (int i = 0; i < group.getChildCount(); i++) {
                var view = group.getChildAt(i);
                if (view.getVisibility() != View.VISIBLE) continue;
                if ((long) view.getWidth() * view.getHeight() >= threshold) {
                    Drawable background = view.getBackground();
                    if (isPlainShape(background)) fadeBackground(view);
                    if (view instanceof ImageView image && isPlainShape(image.getDrawable())) {
                        var drawable = image.getDrawable();
                        var saved = panelImages.get(image);
                        if (saved == null || saved.drawable() != drawable) {
                            saved = new Opacity(drawable, image.getImageAlpha());
                            panelImages.put(image, saved);
                        }
                        image.setImageAlpha(Math.round(saved.alpha() * widgetBackgroundOpacity));
                    }
                }
                if (view instanceof ViewGroup child) fadeLargeBackgrounds(child, threshold);
            }
        }

        private boolean isPlainShape(Drawable drawable) {
            if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable
                    || drawable instanceof ShapeDrawable) return true;
            if (drawable instanceof RippleDrawable) return false;
            if (drawable instanceof LayerDrawable layers) {
                for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                    if (!isPlainShape(layers.getDrawable(i))) return false;
                }
                return layers.getNumberOfLayers() > 0;
            }
            return drawable instanceof DrawableWrapper wrapper && isPlainShape(wrapper.getDrawable());
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            post(this::updatePanelOpacity);
            removeCallbacks(updateSize);
            postDelayed(updateSize, 120);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(updateSize);
            restorePanelOpacity();
            super.onDetachedFromWindow();
        }
    }
}
