package dev.local.sunwindowdemo.features.appvolume;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.text.NumberFormat;
import java.util.List;

/** A Material-style app strip inside the stock expanded audio panel. */
final class AppMixerView extends LinearLayout {
    private final MediaPlayers players;
    private final Runnable observer = this::render;
    private final LinearLayout cards;
    private final int foreground, accent, cardColor;
    private List<MediaPlayers.App> rendered = List.of();
    private boolean tracking;

    AppMixerView(Context context, MediaPlayers players) {
        super(context);
        this.players = players;
        setOrientation(VERTICAL);
        setPadding(dp(2), dp(2), dp(2), dp(4));
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        foreground = themeColor(android.R.attr.textColorPrimary, dark ? 0xffe4eaf0 : 0xff21313a);
        accent = themeColor(android.R.attr.colorAccent, dark ? 0xff78d7dc : 0xff176b76);
        cardColor = dark ? 0xff27353b : 0xffe4eef0;

        String title = getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")
                ? "应用音量" : "App volume";
        var heading = text(title, 16);
        heading.setAccessibilityHeading(true);
        addView(heading, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

        var scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        cards = new LinearLayout(context);
        cards.setOrientation(HORIZONTAL);
        scroll.addView(cards, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        players.observe(observer);
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        players.unobserve(observer);
        tracking = false;
        super.onDetachedFromWindow();
    }

    private void render() {
        List<MediaPlayers.App> apps = players.apps();
        if (tracking || apps.equals(rendered)) return;
        rendered = apps;
        cards.removeAllViews();
        for (MediaPlayers.App app : apps) {
            var params = new LayoutParams(dp(76), LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            cards.addView(card(app), params);
        }
        setVisibility(apps.isEmpty() ? GONE : VISIBLE);
    }

    private LinearLayout card(MediaPlayers.App app) {
        var panel = new LinearLayout(getContext());
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(4), dp(6), dp(4), dp(6));
        var shape = new GradientDrawable();
        shape.setColor(cardColor);
        shape.setCornerRadius(dp(18));
        panel.setBackground(shape);

        int gain = players.gain(app);
        var number = text(percent(gain), 12);
        number.setGravity(Gravity.CENTER);
        panel.addView(number, LayoutParams.MATCH_PARENT, dp(18));

        var track = new FrameLayout(getContext());
        var slider = new SeekBar(getContext());
        slider.setMax(100);
        slider.setProgress(gain);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setRotation(-90);
        slider.setContentDescription(app.name());
        slider.setStateDescription(percent(gain));
        slider.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) view.getParent().requestDisallowInterceptTouchEvent(true);
            else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                if (!fromUser) return;
                number.setText(percent(value));
                seek.setStateDescription(percent(value));
                if (tracking) players.preview(app, value);
                else players.save(app, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seek) { tracking = true; }

            @Override
            public void onStopTrackingTouch(SeekBar seek) {
                players.save(app, seek.getProgress());
                tracking = false;
                render();
            }
        });

        track.addView(slider, new FrameLayout.LayoutParams(dp(87), dp(44), Gravity.CENTER));
        panel.addView(track, dp(64), dp(87));

        var icon = new ImageView(getContext());
        var constantState = app.icon().getConstantState();
        icon.setImageDrawable(constantState == null ? app.icon() : constantState.newDrawable(getResources()));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(icon, dp(22), dp(22));
        var label = text(app.name(), 11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(label, LayoutParams.MATCH_PARENT, dp(16));
        return panel;
    }

    private TextView text(String text, int size) {
        var view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(foreground);
        return view;
    }

    private int themeColor(int attr, int fallback) {
        try {
            var attrs = getContext().obtainStyledAttributes(new int[] {attr});
            try {
                return attrs.getColor(0, fallback);
            } finally {
                attrs.recycle();
            }
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private String percent(int gain) {
        return NumberFormat.getPercentInstance(getResources().getConfiguration().getLocales().get(0))
                .format(gain / 100.0);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
