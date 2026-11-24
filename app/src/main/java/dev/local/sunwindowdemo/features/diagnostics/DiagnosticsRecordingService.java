package dev.local.sunwindowdemo.features.diagnostics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated, short-lived system log export. No restart, boot receiver or background trigger. */
public final class DiagnosticsRecordingService extends Service {
    private static final String CHANNEL = "manual-system-log-recording";
    private static final String STOP = "dev.local.sunwindowdemo.diagnostics.STOP";
    private static final int NOTIFICATION = 7014;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "diagnostics-capture"));
    private boolean started;
    private volatile String terminalReason;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        DiagnosticsStore.connect(this);
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            DiagnosticsStore.stop();
            if (!started) stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "手动系统日志记录", NotificationManager.IMPORTANCE_LOW);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
            // Explicit, immutable intent targets only this non-exported Service.
            PendingIntent stop = PendingIntent.getService(this, 0,
                    new Intent(this, DiagnosticsRecordingService.class).setAction(STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("正在记录系统日志")
                    .setContentText("手动短时记录；复现问题后请停止并按需导出")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "停止记录", stop).build());
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) notification.setContentIntent(PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            startForeground(NOTIFICATION, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException denied) {
            terminalReason = "start_failed"; stopSelf(); return START_NOT_STICKY;
        }
        // Foreground deadline is satisfied before opening files, requesting su or reading any stream.
        worker.execute(() -> {
            String reason = "capture_failed";
            try {
                DiagnosticsCapture capture = DiagnosticsStore.prepare();
                if (capture == null) return;
                reason = capture.run(DiagnosticsStore.rawFile(), DiagnosticsStore.errorFile(), DiagnosticsStore.startedWall());
            } catch (Exception failure) {
                reason = "storage_failed";
            } finally {
                terminalReason = reason;
                // Keep the Store busy until this Service is destroyed; a new start must not be
                // swallowed by this instance's started guard or stopped by its pending stopSelf.
                DiagnosticsStore.stop(reason);
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
                });
            }
        });
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (started) {
            DiagnosticsStore.stop("service_destroyed");
            // Serialized behind capture.run: onDestroy never blocks the UI or releases the state
            // before its process/streams have actually ended. Metadata is also written off-main.
            worker.execute(() -> {
                String reason = terminalReason == null ? "service_destroyed" : terminalReason;
                try {
                    // FGS setup may have failed before prepare/run. Start still means overwrite:
                    // publish an empty failed attempt, never relabel a previous session's raw logs.
                    if ("start_failed".equals(reason)) DiagnosticsStore.prepare();
                } catch (Exception failure) { reason = "storage_failed"; }
                DiagnosticsStore.finished(reason);
            });
        }
        worker.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
