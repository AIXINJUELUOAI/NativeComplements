package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import dev.local.sunwindowdemo.core.HostSnapshot;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** Vertical nudge for the status bar content, independent from the split-shade feature. */
public final class StatusBarOffset {
    private static final List<WeakReference<View>> bars = new ArrayList<>();
    private static final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile int offsetDp;
    private static volatile int contentId = -1;
    private static Consumer<String> logger = ignored -> {};
    private static boolean installed, connected;
    private static Runnable retryLoad;
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private StatusBarOffset() {}

    public static synchronized void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        if (installed) return;
        installed = true;
        logger = log;
        try {
            Class<?> phone = Class.forName("com.android.systemui.statusbar.phone.PhoneStatusBarView", false, loader);
            for (java.lang.reflect.Constructor<?> constructor : phone.getDeclaredConstructors()) {
                hooks.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            remember((View) chain.getThisObject());
                            return result;
                        }));
            }
            java.lang.reflect.Method update = phone.getDeclaredMethod("updateStatusBarHeight");
            hooks.add(module.hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        applyNow();
                        return result;
                    }));
            try {
                Class<?> keyguard = Class.forName("com.android.systemui.statusbar.phone.KeyguardStatusBarView", false, loader);
                for (java.lang.reflect.Constructor<?> constructor : keyguard.getDeclaredConstructors()) {
                    hooks.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                remember((View) chain.getThisObject());
                                return result;
                            }));
                }
            } catch (ClassNotFoundException absent) {
                note("STATUS_BAR_KEYGUARD_ABSENT");
            }
            try { StatusIconScale.install(module, loader); }
            catch (ReflectiveOperationException failure) { note("STATUS_ICON_SCALE_UNAVAILABLE " + failure); }
            note("STATUS_BAR_OFFSET_READY");
        } catch (Throwable failure) {
            installed = false;
            for (XposedInterface.HookHandle hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            hooks.clear();
            note("STATUS_BAR_OFFSET_UNAVAILABLE " + failure);
        }
    }

    public static void connect(Application app) {
        if (connected) return;
        connected = true;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!ShadeStore.CHANGED.equals(intent.getAction())) return;
                    try {
                        int uid = app.getPackageManager().getApplicationInfo("dev.nativecomplements.module", 0).uid;
                        if (getSentFromUid() == uid) load(app);
                    } catch (Exception failure) { note("STATUS_BAR_SENDER_REJECTED"); }
                }
            }, new IntentFilter(ShadeStore.CHANGED), null, main, Context.RECEIVER_EXPORTED);
            load(app);
        } catch (Exception failure) {
            note("STATUS_BAR_CONNECT_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static void load(Context context) {
        if(retryLoad!=null)main.removeCallbacks(retryLoad);
        worker.execute(() -> readSettings(context));
    }
    private static void readSettings(Context context) {
        // Shares the shade snapshot; a stopped module process keeps the last stored offset.
        HostSnapshot snapshot = HostSnapshot.open(context, "shade");
        HostSnapshot.Result read = snapshot.read(() -> context.getContentResolver()
                .call(Uri.parse("content://" + ShadeStore.AUTHORITY), "read", null, null));
        if (read.report()) note("STATUS_BAR_SETTINGS " + read.summary());
        main.post(() -> {
            if (read.config() != null) {
                set(read.config().getInt("statusBarOffset", 0));
                StatusIconScale.set(read.config().getInt("notificationIconScale",100),read.config().getInt("systemIconScale",100));
            }
            if (retryLoad != null) main.removeCallbacks(retryLoad);
            if (!read.live()) { retryLoad=()->load(context);main.postDelayed(retryLoad,snapshot.retryDelay(3000)); }
        });
    }

    private static void set(int dp) {
        offsetDp = ShadePolicy.offset(dp);
        main.post(StatusBarOffset::applyNow);
    }

    private static void remember(View view) {
        for (Iterator<WeakReference<View>> it = bars.iterator(); it.hasNext();) {
            View old = it.next().get();
            if (old == null) it.remove(); else if (old == view) return;
        }
        bars.add(new WeakReference<>(view));
        main.post(StatusBarOffset::applyNow);
    }

    private static void applyNow() {
        int dp = offsetDp;
        for (Iterator<WeakReference<View>> it = bars.iterator(); it.hasNext();) {
            View bar = it.next().get();
            if (bar == null) { it.remove(); continue; }
            try {
                View content = content(bar);
                content.setTranslationY(dp * bar.getResources().getDisplayMetrics().density);
            } catch (RuntimeException failure) {
                note("STATUS_BAR_OFFSET_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    private static View content(View bar) {
        if (bar.getClass().getName().endsWith("KeyguardStatusBarView")) return bar;
        if (contentId == -1)
            contentId = bar.getResources().getIdentifier("status_bar_contents", "id", "com.android.systemui");
        View inner = contentId == 0 ? null : bar.findViewById(contentId);
        return inner == null ? bar : inner;
    }

    private static void note(String message) { try { logger.accept(message); } catch (Throwable ignored) {} }
}
