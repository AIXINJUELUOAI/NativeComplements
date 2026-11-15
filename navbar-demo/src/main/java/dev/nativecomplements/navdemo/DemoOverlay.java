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
import android.view.MotionEvent;
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
    private LinearLayout panel;
    private TextView summary;
    private Button yes, no, unsure;
    private String pendingToken;
    private long pendingStarted;
    private Bundle latest, pressed;
    private boolean destroyed, annotating, pressing;
    private WindowManager.LayoutParams position;
    private float downX, downY;
    private int originX, originY;
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
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(4);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Color.rgb(245, 245, 245));
        summary = new TextView(this);
        summary.setTextColor(Color.BLACK);
        summary.setTextSize(16);
        summary.setGravity(Gravity.CENTER);
        summary.setContentDescription("算法结果，按住拖动悬浮条");
        panel.addView(summary, new LinearLayout.LayoutParams(dp(64), dp(48)));
        yes = button(panel, "肯定", () -> annotate(true));
        no = button(panel, "否定", () -> annotate(false));
        unsure = button(panel, "待定", () -> annotate(null));
        position = new WindowManager.LayoutParams(
                dp(276), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = dp(12);
        position.y = dp(32);
        summary.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    originX = position.x; originY = position.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Clamp to the usable display, keeping the handle reachable after dragging.
                    android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
                    android.graphics.Insets bars = windows.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    position.x = Math.max(0, Math.min(bounds.width() - bars.left - bars.right - panel.getWidth(),
                            originX + Math.round(event.getRawX() - downX)));
                    position.y = Math.max(0, Math.min(bounds.height() - bars.top - bars.bottom - panel.getHeight(),
                            originY + Math.round(event.getRawY() - downY)));
                    try { windows.updateViewLayout(panel, position); } catch (RuntimeException ignored) {}
                    return true;
                case MotionEvent.ACTION_UP: view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
        });
        try {
            windows.addView(panel, position);
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
                Bundle shot = getContentResolver().call(DemoActivity.DATA, "screenshot_status", null, null);
                main.post(() -> {
                    if (destroyed) return;
                    latest = snapshot;
                    if (pendingToken != null && shot != null && pendingToken.equals(shot.getString("token"))
                            && !"requested".equals(shot.getString("status"))) {
                        String state = shot.getString("status", "failed");
                        Toast.makeText(this, "saved".equals(state) ? "已记录，截图已保存"
                                : "content_changed".equals(state) ? "已记录，截图时画面有变化"
                                : "已记录，截图失败：" + shot.getString("reason", ""), Toast.LENGTH_LONG).show();
                        pendingToken = null;
                    }
                    render();
                });
            } catch (Exception e) { fail("读取样本失败", e); }
        });
    }

    static boolean fresh(Bundle sample) {
        if (sample == null || !sample.getBoolean("valid") || !sample.containsKey("id")
                || sample.getString("component", "").isEmpty()) return false;
        long age = SystemClock.elapsedRealtime() - sample.getLong("capturedAt", -1);
        return age >= 0 && age <= 3000;
    }

    private void render() {
        if (summary == null) return;
        if (pendingToken != null && SystemClock.elapsedRealtime() - pendingStarted > 12000) {
            pendingToken = null;
            Toast.makeText(this, "截图未完成，标注已记录", Toast.LENGTH_SHORT).show();
        }
        if (pressing) return; // Keep the visible verdict and ID together until pointer-up.
        String decision = expected(latest, true);
        summary.setText(verdictText(latest));
        yes.setEnabled(!annotating && pendingToken == null && decision != null);
        no.setEnabled(!annotating && pendingToken == null && decision != null);
        unsure.setEnabled(!annotating && pendingToken == null && fresh(latest));
    }

    static String verdictText(Bundle sample) {
        String decision = expected(sample, true);
        return decision == null ? "等待" : decision.equals("EXTEND") ? "延伸" : "悬浮";
    }

    // NATIVE is the original pixel classifier's non-extension verdict, not proof of safe Insets removal.
    static String expected(Bundle sample, boolean agree) {
        if (!fresh(sample)) return null;
        String decision = sample.getString("decision", "");
        if (!decision.equals("EXTEND") && !decision.equals("NATIVE")) return null;
        return agree ? decision : decision.equals("EXTEND") ? "NATIVE" : "EXTEND";
    }

    private void annotate(Boolean agree) {
        Bundle source = pressed != null ? pressed : latest;
        String expected = agree == null && fresh(source) ? "UNKNOWN" : agree == null ? null : expected(source, agree);
        if (expected == null || annotating || pendingToken != null) return;
        Bundle sample = source.deepCopy();
        annotating = true;
        render();
        worker.execute(() -> {
            String error = null, token = null;
            try {
                Bundle extras = new Bundle();
                extras.putBundle("sample", sample);
                extras.putString("expected", expected);
                Bundle result = getContentResolver().call(DemoActivity.DATA, "annotate", null, extras);
                if (result == null) error = "未收到保存确认";
                else if (result.containsKey("error")) error = result.getString("error");
                else token = result.getString("token");
            } catch (Exception e) { error = e.toString(); }
            final String failure = error, requestedToken = token;
            main.post(() -> {
                if (destroyed) return;
                annotating = false;
                pendingToken = failure == null ? requestedToken : null;
                pendingStarted = SystemClock.elapsedRealtime();
                Toast.makeText(this, failure == null ? (pendingToken == null ? "已记录" : "已记录，正在截图") : "记录失败，请重试", Toast.LENGTH_SHORT).show();
                if (failure != null) android.util.Log.w("NavbarDemo", "Annotation failed: " + failure);
                render();
            });
        });
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressed = latest == null ? null : latest.deepCopy();
                pressing = true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                boolean click = event.getActionMasked() == MotionEvent.ACTION_UP && view.isPressed()
                        && event.getX() >= 0 && event.getX() < view.getWidth()
                        && event.getY() >= 0 && event.getY() < view.getHeight();
                if (click) view.performClick();
                view.setPressed(false);
                pressing = false; pressed = null; render();
                return true;
            }
            return false;
        });
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
