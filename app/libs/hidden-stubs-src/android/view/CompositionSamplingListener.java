package android.view;
/** Compile-only stub of the hidden framework class; the real one is on the boot classpath. */
public abstract class CompositionSamplingListener {
    public CompositionSamplingListener(java.util.concurrent.Executor executor) { throw new RuntimeException("stub"); }
    public void destroy() { throw new RuntimeException("stub"); }
    public abstract void onSampleCollected(float medianLuma);
    public static void register(CompositionSamplingListener listener, int displayId, SurfaceControl stopLayer, android.graphics.Rect samplingArea) { throw new RuntimeException("stub"); }
    public static void unregister(CompositionSamplingListener listener) { throw new RuntimeException("stub"); }
}
