package dev.local.sunwindowdemo.features.navbar;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SystemUI side of the labelling mode: a small chip under the status bar showing the AUTO verdict of the
 * current page with ✓ (write it as the Activity rule) and ✗ (preview the next mode on the page, then decide).
 * Nothing is written without ✓; an unconfirmed preview is withdrawn after a few idle seconds.
 */
final class NavbarLabelHost {
    static final String LABEL = "dev.nativecomplements.module.NAVBAR_LABEL";
    private static final Uri PROVIDER = Uri.parse("content://" + NavbarStore.AUTHORITY);
    private static final long SHOW_MS = 6000, PREVIEW_IDLE_MS = 9000;
    /** Hidden WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL (FIRST_SYSTEM_WINDOW + 24), SystemUI-only. */
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static Application app;
    private static WindowManager windows;
    private static LinearLayout chip;
    private static TextView text;
    private static String pkg, activity, label;
    private static int mode = -1;
    private static boolean previewing;
    private static long lastShownAt;
    private static final Runnable hide = NavbarLabelHost::dismiss;

    private NavbarLabelHost() {}

    static void connect(Application application) {
        if (app != null || !application.getPackageName().equals("com.android.systemui")) return;
        app = application;
        application.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 1000) return;
                verdict(intent.getStringExtra("package"), intent.getStringExtra("activity"), intent.getIntExtra("mode", -1));
            }
        }, new IntentFilter(LABEL), "android.permission.STATUS_BAR_SERVICE", main, Context.RECEIVER_EXPORTED);
    }

    private static void verdict(String nextPkg, String nextActivity, int nextMode) {
        if (nextPkg == null || nextActivity == null || nextMode < 0 || previewing) return;
        boolean same = nextPkg.equals(pkg) && nextActivity.equals(activity) && nextMode == mode;
        // The same verdict for the same page is shown again only after the chip had time to go away.
        if (same && chip != null && chip.isAttachedToWindow()) return;
        if (same && SystemClock.uptimeMillis() - lastShownAt < SHOW_MS + 4000) return;
        pkg = nextPkg; activity = nextActivity; mode = nextMode;
        try { label = String.valueOf(app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception unknown) { label = pkg; }
        show();
    }

    private static String modeName(int value) {
        return value == NavbarStore.FLOATING ? "悬浮" : value == NavbarStore.COLOR ? "延伸" : "保留原有";
    }

    private static void show() {
        try {
            if (chip == null) build();
            String page = activity.substring(activity.lastIndexOf('.') + 1);
            text.setText(label + " · " + page + "\n" + (previewing ? "预览：" : "判断：") + modeName(mode));
            if (!chip.isAttachedToWindow()) windows.addView(chip, params());
            lastShownAt = SystemClock.uptimeMillis();
            main.removeCallbacks(hide);
            main.postDelayed(hide, previewing ? PREVIEW_IDLE_MS : SHOW_MS);
        } catch (RuntimeException failure) {
            android.util.Log.w("NativeNavbar", "label chip unavailable", failure);
        }
    }

    private static void build() {
        windows = app.getSystemService(WindowManager.class);
        chip = new LinearLayout(app);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        chip.setPadding(dp(14), pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 32, 33, 36));
        background.setCornerRadius(dp(24));
        chip.setBackground(background);
        text = new TextView(app);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13);
        chip.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        chip.addView(button("✓", NavbarLabelHost::confirm));
        chip.addView(button("✗", NavbarLabelHost::reject));
    }

    private static TextView button(String glyph, Runnable action) {
        TextView button = new TextView(app);
        button.setText(glyph);
        button.setTextSize(20);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(40));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.min(dp(360), app.getResources().getDisplayMetrics().widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT, TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(44);
        p.setTitle("NativeComplements-navbar-label");
        return p;
    }

    /** ✓: the shown mode becomes this Activity's rule (or the Activity is marked multi-page). */
    private static void confirm() {
        String p = pkg, a = activity;
        int m = mode;
        previewing = false;
        call("confirm", p, a, m, result -> {
            text.setText("multi".equals(result) ? "该 Activity 含多个页面，保持自动" : "已写入：" + modeName(m));
            main.removeCallbacks(hide);
            main.postDelayed(hide, 1500);
        });
    }

    /** ✗: preview the next mode on the page; ✓ then confirms what is now visible. */
    private static void reject() {
        mode = mode == NavbarStore.FLOATING ? NavbarStore.COLOR : mode == NavbarStore.COLOR ? NavbarStore.ORIGINAL : NavbarStore.FLOATING;
        previewing = true;
        show();
        call("preview", pkg, activity, mode, null);
    }

    private static void dismiss() {
        if (previewing) { previewing = false; call("preview", pkg, activity, -1, null); }
        if (chip != null && chip.isAttachedToWindow()) {
            try { windows.removeView(chip); } catch (RuntimeException ignored) {}
        }
    }

    private static void call(String method, String p, String a, int m, java.util.function.Consumer<String> done) {
        io.execute(() -> {
            String result = "error";
            try {
                Bundle extras = new Bundle();
                extras.putString("package", p);
                extras.putString("activity", a);
                extras.putInt("mode", m);
                Bundle reply = app.getContentResolver().call(PROVIDER, method, null, extras);
                result = reply == null ? "ok" : reply.getString("result", "ok");
            } catch (RuntimeException failure) {
                android.util.Log.w("NativeNavbar", "label " + method + " failed", failure);
            }
            String r = result;
            if (done != null) main.post(() -> done.accept(r));
        });
    }

    private static int dp(int value) { return Math.round(value * app.getResources().getDisplayMetrics().density); }
}
