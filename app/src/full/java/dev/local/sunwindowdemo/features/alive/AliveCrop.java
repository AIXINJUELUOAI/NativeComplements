package dev.local.sunwindowdemo.features.alive;

/** Center and zoom are relative to the source and survive display size changes. */
public record AliveCrop(float x, float y, float zoom) {
    public static final AliveCrop CENTER = new AliveCrop(.5f, .5f, 1f);

    public AliveCrop {
        x = clamp(Float.isFinite(x) ? x : .5f, 0f, 1f);
        y = clamp(Float.isFinite(y) ? y : .5f, 0f, 1f);
        zoom = clamp(Float.isFinite(zoom) ? zoom : 1f, 1f, 4f);
    }

    public record Window(float left, float top, float width, float height) {}

    public Window window(float sourceAspect, float targetAspect) {
        float width = Math.min(1f, targetAspect / sourceAspect) / zoom;
        float height = Math.min(1f, sourceAspect / targetAspect) / zoom;
        return new Window(clamp(x - width / 2f, 0f, 1f - width),
                clamp(y - height / 2f, 0f, 1f - height), width, height);
    }

    public AliveCrop gesture(float sourceAspect, float targetAspect, float focusX, float focusY,
                             float panX, float panY, float zoomFactor) {
        Window before = window(sourceAspect, targetAspect);
        AliveCrop scaled = new AliveCrop(x, y, zoom * zoomFactor);
        Window after = scaled.window(sourceAspect, targetAspect);
        float nextX = before.left + focusX * before.width + (.5f - focusX - panX) * after.width;
        float nextY = before.top + focusY * before.height + (.5f - focusY - panY) * after.height;
        return new AliveCrop(clamp(nextX, after.width / 2f, 1f - after.width / 2f),
                clamp(nextY, after.height / 2f, 1f - after.height / 2f), scaled.zoom);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
