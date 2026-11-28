package dev.local.sunwindowdemo.features.shade;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import java.util.Locale;

/** Uses SystemUI colors and the current user's system brightness mode. */
final class QsAdaptiveBrightnessButton extends ImageButton {
    static final float SIZE_DP = 44f;
    private static final String MODE = Settings.System.SCREEN_BRIGHTNESS_MODE;
    private static final String TAG = "PixelOSControlCenter";

    private final GradientDrawable surface = new GradientDrawable();
    private final GradientDrawable mask = new GradientDrawable();
    private final RippleDrawable ripple;
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private final ContentObserver observer;
    private ValueAnimator animator;
    private boolean automatic;
    private float fraction;
    private int activeSurface, inactiveSurface, activeIcon, inactiveIcon;

    QsAdaptiveBrightnessButton(Context context) {
        super(context);
        mask.setColor(Color.WHITE);
        ripple = new RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), surface, mask);
        setBackground(ripple);
        setScaleType(ScaleType.FIT_CENTER);
        int padding = Math.round(dp(10f));
        setPadding(padding, padding, padding, padding);
        loadIcon();
        setFocusable(true);
        setContentDescription(inChinese() ? "自适应亮度" : "Adaptive brightness");
        observer = new ContentObserver(new Handler(context.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { sync(true); }
        };
        setOnClickListener(view -> toggleMode());
        setOnLongClickListener(view -> openDisplaySettings());
        readColors();
        sync(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(MODE), false, observer);
        sync(false);
    }

    @Override protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(observer);
        if (animator != null) animator.cancel();
        animator = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        setContentDescription(inChinese() ? "自适应亮度" : "Adaptive brightness");
        readColors();
        sync(false);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(automatic);
    }

    private int readMode() {
        return Settings.System.getInt(getContext().getContentResolver(), MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    private void toggleMode() {
        try {
            int next = readMode() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            if (Settings.System.putInt(getContext().getContentResolver(), MODE, next)) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                sync(true);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not change adaptive brightness", error);
        }
    }

    private boolean openDisplaySettings() {
        try {
            var intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not open display settings", error);
            return false;
        }
    }

    private void sync(boolean animate) {
        boolean next = readMode() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        if (animate && next == automatic) return;
        automatic = next;
        setStateDescription(inChinese() ? (next ? "已开启" : "已关闭") : (next ? "On" : "Off"));
        ripple.setColor(ColorStateList.valueOf(((next ? activeIcon : inactiveIcon) & 0x00ffffff) | 0x20000000));
        float target = next ? 1f : 0f;
        if (animator != null) animator.cancel();
        if (!animate || fraction == target) {
            drawState(target);
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(200);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(animation -> drawState((float) animation.getAnimatedValue()));
        animator.start();
    }

    private void drawState(float value) {
        fraction = value;
        float radius = dp(22f - 10f * value);
        surface.setCornerRadius(radius);
        mask.setCornerRadius(radius);
        surface.setColor((int) colors.evaluate(value, inactiveSurface, activeSurface));
        setColorFilter((int) colors.evaluate(value, inactiveIcon, activeIcon));
    }

    @SuppressWarnings("DiscouragedApi")
    private void loadIcon() {
        int id = getResources().getIdentifier("ic_qs_autobrightness", "drawable", ShadeStore.SYSTEM_UI);
        if (id != 0) setImageResource(id);
    }

    private void readColors() {
        activeSurface = systemColor("materialColorPrimary", 0xff6750a4);
        inactiveSurface = systemColor("materialColorSurfaceContainerHigh", 0xffece6f0);
        activeIcon = systemColor("materialColorOnPrimary", Color.WHITE);
        inactiveIcon = systemColor("materialColorOnSurface", 0xff1d1b20);
    }

    @SuppressWarnings("DiscouragedApi")
    private int systemColor(String name, int fallback) {
        int id = getResources().getIdentifier(name, "color", ShadeStore.SYSTEM_UI);
        if (id == 0) return fallback;
        try { return getContext().getColor(id); }
        catch (Resources.NotFoundException error) { return fallback; }
    }

    private float dp(float value) { return getResources().getDisplayMetrics().density * value; }

    private boolean inChinese() {
        return Locale.CHINESE.getLanguage().equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }
}
