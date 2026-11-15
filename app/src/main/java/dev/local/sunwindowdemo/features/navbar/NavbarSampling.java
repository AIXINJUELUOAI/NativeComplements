package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.CompositionSamplingListener;

/**
 * Measurement only: SurfaceFlinger's composition-time median luma of the area above the navigation boundary
 * (the mechanism SystemUI uses for the handle colour). Logged so its latency can be compared with the
 * thumbnail refresh detection before it is used as a trigger. Every failure disables it for the process.
 */
final class NavbarSampling {
    private static volatile boolean broken;
    private Listener listener;
    private Rect area;
    private long lastLog;
    private float lastLuma = -1;
    private int callbacks;

    private final class Listener extends CompositionSamplingListener {
        Listener(java.util.concurrent.Executor executor) { super(executor); }
        @Override public void onSampleCollected(float luma) {
            callbacks++;
            long now = SystemClock.uptimeMillis();
            // A luma step is what a page switch looks like here; small drifts are only counted.
            if (Math.abs(luma - lastLuma) >= 0.04f || now - lastLog > 5000) {
                NavbarTrace.i("SAMPLE luma=" + String.format(java.util.Locale.ROOT, "%.3f", luma)
                        + " prev=" + String.format(java.util.Locale.ROOT, "%.3f", lastLuma) + " callbacks=" + callbacks);
                lastLog = now;
                callbacks = 0;
            }
            lastLuma = luma;
        }
    }

    /** Display-space area; null stops sampling. Called on the navigation worker. */
    void follow(Rect next, java.util.concurrent.Executor executor) {
        if (broken) return;
        try {
            if (next == null || next.isEmpty()) { stop(); return; }
            if (next.equals(area)) return;
            stop();
            listener = new Listener(executor);
            CompositionSamplingListener.register(listener, 0, null, next);
            area = new Rect(next);
            NavbarTrace.i("SAMPLE area " + area.toShortString());
        } catch (RuntimeException | LinkageError failure) {
            broken = true;
            NavbarTrace.w("Composition sampling unavailable", failure);
        }
    }

    void stop() {
        if (listener == null) return;
        try { CompositionSamplingListener.unregister(listener); listener.destroy(); }
        catch (RuntimeException | LinkageError ignored) {}
        listener = null;
        area = null;
    }
}
