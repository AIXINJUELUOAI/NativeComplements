package dev.local.sunwindowdemo;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.util.function.Consumer;

/** Hides the native floating rotation button and surfaces the same action in the suggestion row. */
final class RotationSuggestion {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean enabled = true;
    private static Context context;
    private static Consumer<String> logger = ignored -> {};
    private RotationSuggestion() {}

    static void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        logger = log;
        try {
            Class<?> controller = Class.forName("com.android.systemui.shared.rotation.RotationButtonController", false, loader);
            java.lang.reflect.Method state = controller.getDeclaredMethod("setRotateSuggestionButtonState", boolean.class, boolean.class);
            module.hook(state).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                boolean show = (Boolean) chain.getArg(0);
                if (!enabled) return chain.proceed();
                // Never draw the native floating button; the module row presents it instead.
                Object result = chain.proceed(new Object[]{false, chain.getArg(1)});
                notifyHost(show);
                return result;
            });
            note("ROTATION_SUGGESTION_READY");
        } catch (Throwable failure) {
            note("ROTATION_SUGGESTION_UNAVAILABLE " + failure);
        }
    }

    static void connect(Application app) {
        context = app;
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    try {
                        int uid = app.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid;
                        if (getSentFromUid() == uid) load();
                    } catch (Exception ignored) {}
                }
            }, new IntentFilter(SuggestionStore.CHANGED), null, main, Context.RECEIVER_EXPORTED);
        } catch (Exception failure) {
            note("ROTATION_CONNECT_FAILED " + failure.getClass().getSimpleName());
        }
        main.post(RotationSuggestion::load);
    }

    private static void load() {
        if (context == null) return;
        try {
            Bundle bundle = context.getContentResolver()
                    .call(Uri.parse("content://" + SuggestionStore.AUTHORITY), "read", null, null);
            boolean next = bundle == null || bundle.getBoolean("rotate", true);
            if (next == enabled) return;
            enabled = next;
            if (!next) notifyHost(false);
        } catch (Exception failure) {
            note("ROTATION_SETTINGS_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static void notifyHost(boolean shown) {
        SystemUiHost host = SystemUiHost.live;
        if (host != null) host.rotationSuggestion(shown);
    }

    private static void note(String message) { try { logger.accept(message); } catch (Throwable ignored) {} }
}
