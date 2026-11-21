package dev.local.sunwindowdemo.core.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;

/** Observes committed Android palette resources in the process that draws them. */
public final class SystemPalette implements AutoCloseable {
    private static final String OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";
    private static final int[] COLORS = {
            android.R.color.system_accent1_100, android.R.color.system_accent1_500, android.R.color.system_accent1_800,
            android.R.color.system_accent2_100, android.R.color.system_accent2_500, android.R.color.system_accent2_800,
            android.R.color.system_accent3_100, android.R.color.system_accent3_500, android.R.color.system_accent3_800,
            android.R.color.system_neutral1_100, android.R.color.system_neutral1_500, android.R.color.system_neutral1_800,
            android.R.color.system_neutral2_100, android.R.color.system_neutral2_500, android.R.color.system_neutral2_800,
    };

    private final Context resourcesContext;
    private final Context callbacksContext;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::read;
    private int[] palette;
    private boolean receiving;
    private boolean closed;

    private final ComponentCallbacks configuration = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration newConfig) { recheck(); }
        @Override public void onLowMemory() {}
    };
    private final BroadcastReceiver overlays = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (OVERLAY_CHANGED.equals(intent.getAction()) && intent.getData() != null
                    && "android".equals(intent.getData().getSchemeSpecificPart())) recheck();
        }
    };

    public SystemPalette(Context context, Runnable changed) {
        resourcesContext = context;
        Context app = context.getApplicationContext();
        callbacksContext = app != null ? app : context;
        this.changed = changed;
        palette = snapshot();
        callbacksContext.registerComponentCallbacks(configuration);
        IntentFilter filter = new IntentFilter(OVERLAY_CHANGED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart("android", android.os.PatternMatcher.PATTERN_LITERAL);
        try {
            callbacksContext.registerReceiver(overlays, filter, Context.RECEIVER_NOT_EXPORTED);
            receiving = true;
        } catch (RuntimeException unavailable) {
            // Configuration callbacks remain available on hosts that filter overlay broadcasts.
        }
    }

    /** A resource update can be dispatched just after the broadcast or activity resume. */
    public void recheck() {
        if (closed) return;
        main.removeCallbacks(refresh);
        main.post(refresh);
        main.postDelayed(refresh, 150);
        main.postDelayed(refresh, 600);
    }

    private void read() {
        if (closed) return;
        int[] next = snapshot();
        if (Arrays.equals(palette, next)) return;
        palette = next;
        changed.run();
    }

    private int[] snapshot() {
        int[] result = new int[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) result[i] = resourcesContext.getColor(COLORS[i]);
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(refresh);
        callbacksContext.unregisterComponentCallbacks(configuration);
        if (receiving) callbacksContext.unregisterReceiver(overlays);
    }
}
