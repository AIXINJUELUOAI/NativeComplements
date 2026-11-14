package dev.nativecomplements.navdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small independent overlay: no captured pixels, preview, or navigation adaptation. */
public final class DemoOverlay extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private LinearLayout panel, labels;
    private TextView summary;
    private Button freeze;
    private Bundle latest, frozen;
    private boolean destroyed, annotating;
    private int ticks;
    private final Runnable readLatest = this::refresh;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            main.removeCallbacks(readLatest);
            main.postDelayed(readLatest, 100);
        }
    };
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            render();
            refresh();
            if (++ticks % 5 == 0) worker.execute(() -> {
                try {
                    Bundle extras = new Bundle();
                    extras.putBoolean("enabled", true);
                    getContentResolver().call(DemoActivity.DATA, "control", null, extras);
                } catch (Exception e) { fail("续租失败，采样可能暂停", e); }
            });
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("observer", "导航观察", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DemoActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "observer").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("导航观察运行中").setContentText("仅采样；打开管理页可停止观察")
                .setContentIntent(open).setOngoing(true).build());
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少悬浮窗权限，请在管理页手动授予", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        windows = getSystemService(WindowManager.class);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Color.rgb(245, 245, 245));
        summary = new TextView(this);
        summary.setTextColor(Color.BLACK);
        summary.setTextSize(12);
        panel.addView(summary);
        LinearLayout controls = new LinearLayout(this);
        panel.addView(controls);
        freeze = button(controls, "锁定样本", () -> {
            if (frozen != null) {
                frozen = null;
            } else if (fresh(latest)) {
                frozen = latest.deepCopy();
            } else {
                Toast.makeText(this, "仅可锁定有效且不超过 3 秒的新样本", Toast.LENGTH_SHORT).show();
            }
            render();
        });
        button(controls, "重采", () -> worker.execute(() -> {
            try {
                getContentResolver().call(DemoActivity.DATA, "request", null, null);
                main.post(() -> Toast.makeText(this, "已请求重采；锁定样本 ID 保持不变", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { fail("请求重采失败", e); }
        }));
        button(controls, "停止观察", this::stopSelf);
        labels = new LinearLayout(this);
        panel.addView(labels);
        button(labels, "适合延伸", () -> annotate("EXTEND"));
        button(labels, "不适合", () -> annotate("NATIVE"));
        button(labels, "不确定", () -> annotate("UNKNOWN"));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.min(dp(350), getResources().getDisplayMetrics().widthPixels - dp(16)),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(32);
        try {
            windows.addView(panel, params);
            getContentResolver().registerContentObserver(DemoActivity.DATA, true, observer);
            worker.execute(() -> {
                try {
                    Bundle extras = new Bundle();
                    extras.putBoolean("enabled", true);
                    getContentResolver().call(DemoActivity.DATA, "control", null, extras);
                    main.post(readLatest);
                } catch (Exception e) { fail("开启采样失败", e); main.post(this::stopSelf); }
            });
            main.post(clock);
        } catch (Exception e) {
            fail("显示悬浮面板失败", e);
            stopSelf();
        }
    }

    private void refresh() {
        if (destroyed) return;
        worker.execute(() -> {
            try {
                Bundle result = getContentResolver().call(DemoActivity.DATA, "latest", null, null);
                Bundle sample = result == null ? null : result.getBundle("sample");
                if (sample == null && result != null && !result.isEmpty()) sample = result;
                Bundle snapshot = sample == null ? null : sample.deepCopy();
                main.post(() -> { if (!destroyed) { latest = snapshot; render(); } });
            } catch (Exception e) { fail("读取样本失败", e); }
        });
    }

    private boolean fresh(Bundle sample) {
        if (sample == null || !sample.getBoolean("valid") || !sample.containsKey("id")
                || sample.getString("component", "").isEmpty()) return false;
        long age = SystemClock.elapsedRealtime() - sample.getLong("capturedAt", -1);
        return age >= 0 && age <= 3000;
    }

    private void render() {
        if (summary == null) return;
        Bundle sample = frozen != null ? frozen : latest;
        String text = frozen == null ? "实时 · 仅观察，不执行适配" : "已锁定 ID " + frozen.getLong("id") + " · 请尽快标注，历史可能失效\n切页前请解锁；重采不改变锁定 ID";
        if (sample == null) text += "\n等待样本；请切到目标应用页面";
        else {
            long age = SystemClock.elapsedRealtime() - sample.getLong("capturedAt", -1);
            text += "\nID " + sample.getLong("id") + " · " + sample.getString("component", "未知页面")
                    + "\n" + decision(sample.getString("decision", "")) + " · " + sample.getString("reason", "")
                    + "\n有效=" + sample.getBoolean("valid") + " · 样本年龄=" + age + "ms · 耗时=" + sample.get("elapsedMs")
                    + "ms\n稳定=" + sample.get("stableRatio") + " · 边缘=" + sample.get("edgeRatio")
                    + " · 分段=" + sample.get("segments")
                    + "\n捕获=" + sample.getLong("captureMs") + "ms · 判断=" + sample.getLong("classifyMs") + "ms";
            if (sample.containsKey("logError")) text += "\n" + sample.getString("logError");
            if (frozen == null && !fresh(sample)) text += "\n当前样本无效或已过期，不能锁定";
        }
        summary.setText(text);
        freeze.setText(frozen == null ? "锁定样本" : "解锁／实时");
        freeze.setEnabled(!annotating && (frozen != null || fresh(latest)));
        labels.setVisibility(frozen == null ? View.GONE : View.VISIBLE);
        for (int i = 0; i < labels.getChildCount(); i++) labels.getChildAt(i).setEnabled(!annotating);
    }

    private static String decision(String value) {
        return switch (value) {
            case "EXTEND" -> "适合延伸";
            case "NATIVE" -> "不建议延伸（不代表必须悬浮）";
            case "UNKNOWN" -> "不确定";
            case "UNAVAILABLE" -> "采样不可用";
            default -> "等待采样";
        };
    }

    private void annotate(String expected) {
        if (frozen == null || annotating) return;
        Bundle sample = frozen.deepCopy();
        annotating = true;
        render();
        worker.execute(() -> {
            String error = null;
            try {
                Bundle extras = new Bundle();
                extras.putBundle("sample", sample);
                extras.putString("expected", expected);
                Bundle result = getContentResolver().call(DemoActivity.DATA, "annotate", null, extras);
                if (result == null) error = "未收到保存确认";
                else if (result.containsKey("error")) error = result.getString("error");
            } catch (Exception e) { error = e.toString(); }
            final String failure = error;
            main.post(() -> {
                if (destroyed) return;
                annotating = false;
                Toast.makeText(this, failure == null ? "已标注 ID " + sample.getLong("id") + "；请解锁后再观察下一页"
                        : "标注失败：" + failure, Toast.LENGTH_LONG).show();
                render();
            });
        });
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        return button;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private void fail(String message, Exception error) {
        main.post(() -> Toast.makeText(this, message + "：" + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        getContentResolver().unregisterContentObserver(observer);
        if (panel != null && panel.isAttachedToWindow()) {
            try { windows.removeView(panel); }
            catch (RuntimeException e) { fail("移除悬浮面板失败", e); }
        }
        worker.execute(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putBoolean("enabled", false);
                getContentResolver().call(DemoActivity.DATA, "control", null, extras);
            } catch (Exception e) { fail("关闭采样配置失败，请在管理页重试停止", e); }
        });
        worker.shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
