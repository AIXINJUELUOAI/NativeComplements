package dev.local.sunwindowdemo.features.navbar;

import android.annotation.SuppressLint;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * SystemUI side of the labelling mode: a draggable panel that stays on screen while the mode is on.
 * It shows the current page with the mode actually in effect (pushed by system_server on every change);
 * a mode button writes that Activity's rule at once (latest tap wins) and 撤销 restores the rule before the
 * last tap. "—" folds the panel to a single line; tapping the line unfolds it.
 */
final class NavbarLabelHost {
    static final String LABEL = "dev.nativecomplements.module.NAVBAR_LABEL";
    private static final Uri PROVIDER = Uri.parse("content://" + NavbarStore.AUTHORITY);
    /** Hidden WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL (FIRST_SYSTEM_WINDOW + 24), SystemUI-only. */
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;
    private static final int QUERY = -3, CLEAR = -1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private record Undo(String pkg, String activity, int previous) {}
    private static final ArrayDeque<Undo> undo = new ArrayDeque<>();
    private static final Map<Integer, TextView> choices = new HashMap<>();
    private static Application app;
    private static WindowManager windows;
    private static WindowManager.LayoutParams params;
    private static LinearLayout panel, row;
    private static TextView text, undoButton, fold;
    private static String pkg, activity, label = "", source = "", idle = "";
    private static String note = "", container = "无结构信息";
    private static int suggested;
    private static int shown = -1, rule = -1;
    private static boolean folded;

    private NavbarLabelHost() {}

    static void connect(Application application) {
        if (app != null || !application.getPackageName().equals("com.android.systemui")) return;
        app = application;
        application.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 1000) { android.util.Log.w("NativeNavbar", "label from uid " + getSentFromUid() + " ignored"); return; }
                if (!intent.getBooleanExtra("on", false)) { remove(); return; }
                container = intent.getStringExtra("container");
                if (container == null || container.length() > 600) container = "无结构信息";
                suggested = intent.getIntExtra("suggested", NavbarStore.ORIGINAL);
                state(intent.getStringExtra("package"), intent.getStringExtra("activity"), intent.getIntExtra("mode", -1),
                        intent.getStringExtra("source"), intent.getStringExtra("idle"));
            }
        }, new IntentFilter(LABEL), "android.permission.STATUS_BAR_SERVICE", main, Context.RECEIVER_EXPORTED);
    }

    private static void state(String nextPkg, String nextActivity, int mode, String nextSource, String nextIdle) {
        boolean page = nextPkg != null && nextActivity != null;
        boolean changed = page ? !nextPkg.equals(pkg) || !nextActivity.equals(activity) : pkg != null;
        if (changed) {
            note = "";
            rule = -1;
            if (page) {
                try { label = String.valueOf(app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(nextPkg, 0))); }
                catch (Exception unknown) { label = nextPkg; }
                String p = nextPkg, a = nextActivity;
                call(p, a, QUERY, previous -> { if (p.equals(pkg) && a.equals(activity)) { rule = previous; render(); } });
            }
        }
        pkg = page ? nextPkg : null;
        activity = page ? nextActivity : null;
        shown = mode;
        source = nextSource == null ? "" : nextSource;
        idle = nextIdle == null ? "" : nextIdle;
        render();
    }

    private static String modeName(int value) {
        return value == NavbarStore.FLOATING ? "悬浮" : value == NavbarStore.COLOR ? "取色"
                : value == NavbarStore.AUTO ? "自动" : "保留";
    }

    private static void render() {
        try {
            if (panel == null) build();
            String line;
            if (pkg == null) line = "标注模式 · " + (idle.isEmpty() ? "没有可标注的页面" : idle);
            else {
                int hash = activity.indexOf('#');
                String name = hash < 0 ? activity : activity.substring(0, hash);
                String page = name.substring(name.lastIndexOf('.') + 1) + (hash < 0 ? "" : " · 页面 " + activity.substring(hash + 1, Math.min(activity.length(), hash + 5)));
                line = label + " · " + page;
                if (!folded) line += "\n底部容器：" + container + "\n建议：" + modeName(suggested)
                        + " · 当前：" + modeName(shown) + " · " + source
                        + (rule >= 0 ? " · 规则=" + modeName(rule) : "") + (note.isEmpty() ? "" : "\n" + note);
                else line += " · " + container.substring(0, container.indexOf('\n') < 0 ? container.length() : container.indexOf('\n'))
                        + " → " + modeName(shown);
            }
            text.setText(line);
            row.setVisibility(folded || pkg == null ? View.GONE : View.VISIBLE);
            fold.setText(folded ? "＋" : "—");
            for (var entry : choices.entrySet()) highlight(entry.getValue(), entry.getKey() == rule);
            undoButton.setAlpha(undo.isEmpty() ? 0.35f : 1f);
            if (!panel.isAttachedToWindow()) windows.addView(panel, params);
        } catch (RuntimeException failure) {
            android.util.Log.w("NativeNavbar", "label panel unavailable", failure);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void build() {
        windows = app.getSystemService(WindowManager.class);
        params = params();
        panel = new LinearLayout(app);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        panel.setPadding(dp(14), pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 32, 33, 36));
        background.setCornerRadius(dp(20));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(app);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        text = new TextView(app);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13);
        header.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        fold = button("—", () -> { folded = !folded; render(); });
        header.addView(fold, new LinearLayout.LayoutParams(dp(44), dp(36)));
        panel.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        drag(text);

        row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int option : new int[]{NavbarStore.COLOR, NavbarStore.FLOATING, NavbarStore.ORIGINAL, NavbarStore.AUTO}) {
            TextView choice = button(modeName(option), () -> choose(option));
            choices.put(option, choice);
            row.addView(choice, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        undoButton = button("撤销", NavbarLabelHost::undo);
        row.addView(undoButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Dragging the text moves the panel; a tap without movement unfolds a folded panel. */
    @SuppressLint("ClickableViewAccessibility")
    private static void drag(View handle) {
        float[] start = new float[4];
        boolean[] moved = new boolean[1];
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    start[0] = event.getRawX(); start[1] = event.getRawY(); start[2] = params.x; start[3] = params.y;
                    moved[0] = false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - start[0], dy = event.getRawY() - start[1];
                    if (!moved[0] && Math.hypot(dx, dy) < dp(6)) return true;
                    moved[0] = true;
                    params.x = Math.round(start[2] + dx);
                    params.y = Math.max(0, Math.round(start[3] + dy));
                    if (panel.isAttachedToWindow()) windows.updateViewLayout(panel, params);
                }
                case MotionEvent.ACTION_UP -> { if (!moved[0] && folded) { folded = false; render(); } }
                default -> {}
            }
            return true;
        });
    }

    private static void highlight(TextView view, boolean on) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(on ? Color.argb(255, 138, 180, 248) : Color.TRANSPARENT);
        view.setBackground(shape);
        view.setTextColor(on ? Color.rgb(32, 33, 36) : Color.WHITE);
    }

    private static TextView button(String glyph, Runnable action) {
        TextView button = new TextView(app);
        button.setText(glyph);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(36));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.min(dp(380), app.getResources().getDisplayMetrics().widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT, TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(44);
        p.setTitle("NativeComplements-navbar-label");
        return p;
    }

    /** A mode button: written immediately as this Activity's rule; the previous rule is kept for 撤销. */
    private static void choose(int option) {
        if (pkg == null || option == rule) return;
        String p = pkg, a = activity;
        rule = option;
        note = "已写入：" + modeName(option);
        render();
        call(p, a, option, previous -> {
            if (previous < -1) { note = "写入失败"; render(); return; }
            undo.push(new Undo(p, a, previous));
            while (undo.size() > 20) undo.removeLast();
            render();
        });
    }

    private static void undo() {
        Undo last = undo.poll();
        if (last == null) return;
        call(last.pkg(), last.activity(), last.previous() < 0 ? CLEAR : last.previous(), ignored -> {
            if (last.pkg().equals(pkg) && last.activity().equals(activity)) rule = last.previous();
            note = "已撤销 " + last.activity().substring(last.activity().lastIndexOf('.') + 1) + " → "
                    + (last.previous() < 0 ? "无规则" : modeName(last.previous()));
            render();
        });
    }

    private static void remove() {
        pkg = null; activity = null;
        if (panel != null && panel.isAttachedToWindow()) {
            try { windows.removeView(panel); } catch (RuntimeException ignored) {}
        }
    }

    private static void call(String p, String a, int m, java.util.function.IntConsumer done) {
        io.execute(() -> {
            int result = -2;
            try {
                Bundle extras = new Bundle();
                extras.putString("package", p);
                extras.putString("activity", a);
                extras.putInt("mode", m);
                Bundle reply = app.getContentResolver().call(PROVIDER, "label", null, extras);
                if (reply != null) result = reply.getInt("previous", -2);
            } catch (RuntimeException failure) {
                android.util.Log.w("NativeNavbar", "label call failed", failure);
            }
            int r = result;
            if (done != null) main.post(() -> done.accept(r));
        });
    }

    private static int dp(int value) { return Math.round(value * app.getResources().getDisplayMetrics().density); }
}
