package dev.local.sunwindowdemo.features.minusone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.transition.Fade;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Anchored options for the negative-one page, styled with system dynamic colors. */
final class FeedOptionsPopup extends PopupWindow {
    record Item(String label, Boolean checked, int icon, Runnable select) {
        boolean enabled() { return select != null; }
    }

    private final Context context;
    private final int surface, rowColor, ink, secondary, primary, onPrimary, container, outline;

    FeedOptionsPopup(View anchor, List<List<Item>> groups) {
        context = anchor.getContext();
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        surface = color(dark, android.R.color.system_neutral1_900, android.R.color.system_neutral1_10);
        rowColor = color(dark, android.R.color.system_neutral1_800, android.R.color.system_neutral1_50);
        ink = color(dark, android.R.color.system_neutral1_50, android.R.color.system_neutral1_900);
        secondary = color(dark, android.R.color.system_neutral2_200, android.R.color.system_neutral2_700);
        primary = color(dark, android.R.color.system_accent1_200, android.R.color.system_accent1_700);
        onPrimary = color(dark, android.R.color.system_accent1_900, android.R.color.system_accent1_0);
        container = color(dark, android.R.color.system_accent1_800, android.R.color.system_accent1_100);
        outline = color(dark, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500);

        var content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (var group : groups) {
            for (int i = 0; i < group.size(); i++) {
                var row = row(group.get(i), i == 0 ? 20 : 5, i == group.size() - 1 ? 20 : 5);
                var params = new LinearLayout.LayoutParams(-1, -2);
                if (content.getChildCount() > 0) params.topMargin = dp(i == 0 ? 8 : 2);
                content.addView(row, params);
            }
        }
        var scroll = new ScrollView(context);
        scroll.setAccessibilityPaneTitle("负一屏选项");
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        var visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        int availableWidth = visible.width() > 0 ? visible.width() : anchor.getRootView().getWidth();
        int width = Math.min(dp(210), Math.max(1, availableWidth - dp(32)));
        setContentView(scroll);
        setWidth(width);
        setFocusable(true);
        setOutsideTouchable(true);
        setInputMethodMode(INPUT_METHOD_NOT_NEEDED);
        setBackgroundDrawable(shape(surface, 28, 28));
        setElevation(dp(8));
        setEnterTransition(new Fade(Fade.IN).setDuration(150));
        setExitTransition(new Fade(Fade.OUT).setDuration(110));
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = getMaxAvailableHeight(anchor, dp(8));
        setHeight(maxHeight > 0 ? Math.min(content.getMeasuredHeight(), maxHeight) : content.getMeasuredHeight());
    }

    private LinearLayout row(Item item, int top, int bottom) {
        var row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        row.setMinimumHeight(dp(48));
        boolean add = item.icon() == android.R.drawable.ic_input_add;
        int fill = add ? container : rowColor;
        row.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0xffffff) | 0x20000000),
                shape(fill, top, bottom), null));

        if (add) {
            var plus = glyph(item.icon(), ink);
            var size = new LinearLayout.LayoutParams(dp(22), dp(22));
            size.setMarginEnd(dp(10));
            row.addView(plus, size);
        }

        var label = new TextView(context);
        label.setText(item.label());
        label.setTextColor(item.enabled() ? ink : secondary);
        label.setTextSize(14);
        label.setTypeface(Typeface.create(add ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        if (item.checked() != null) {
            var size = new LinearLayout.LayoutParams(dp(40), dp(24));
            size.setMarginStart(dp(10));
            row.addView(toggle(item.checked()), size);
        } else if (item.icon() != 0 && !add) {
            var size = new LinearLayout.LayoutParams(dp(20), dp(20));
            size.setMarginStart(dp(10));
            row.addView(glyph(item.icon(), secondary), size);
        }

        row.setContentDescription(item.label());
        row.setFocusable(true);
        row.setEnabled(item.enabled());
        if (!item.enabled()) row.setAlpha(0.55f);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (item.checked() == null) {
                    info.setClassName(android.widget.Button.class.getName());
                } else {
                    info.setClassName(android.widget.Switch.class.getName());
                    info.setCheckable(true);
                    info.setChecked(item.checked());
                }
            }
        });
        if (item.enabled()) {
            row.setOnClickListener(view -> {
                dismiss();
                item.select().run();
            });
        }
        return row;
    }

    private FrameLayout toggle(boolean checked) {
        var track = new FrameLayout(context);
        var drawable = shape(checked ? primary : surface, 15, 15);
        if (!checked) drawable.setStroke(dp(2), outline);
        track.setBackground(drawable);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int diameter = dp(checked ? 18 : 12);
        var thumb = new View(context);
        thumb.setBackground(shape(checked ? onPrimary : outline, 14, 14));
        var params = new FrameLayout.LayoutParams(diameter, diameter,
                Gravity.CENTER_VERTICAL | (checked ? Gravity.END : Gravity.START));
        if (checked) params.setMarginEnd(dp(3));
        else params.setMarginStart(dp(6));
        track.addView(thumb, params);
        return track;
    }

    private ImageView glyph(int icon, int color) {
        var view = new ImageView(context);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private GradientDrawable shape(int color, int top, int bottom) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        float t = dp(top), b = dp(bottom);
        drawable.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return drawable;
    }

    private int color(boolean dark, int night, int day) { return context.getColor(dark ? night : day); }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
