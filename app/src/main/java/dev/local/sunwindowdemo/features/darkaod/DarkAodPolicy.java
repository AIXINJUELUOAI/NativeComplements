package dev.local.sunwindowdemo.features.darkaod;

/** Lux hysteresis only. Native doze state, ownership and proximity stay with the host. */
public final class DarkAodPolicy {
    public static final long DARK_DELAY_MS = 10_000;
    public static final long BRIGHT_DELAY_MS = 2_000;
    private boolean enabled, dark;
    private float darkLux = 1f, brightLux = 5f;
    private long deadline = -1;
    private boolean pendingDark;

    public void configure(boolean enabled, float darkLux, float brightLux) {
        boolean valid = Float.isFinite(darkLux) && Float.isFinite(brightLux)
                && darkLux >= 0f && darkLux <= 5f && brightLux >= 5f
                && brightLux >= darkLux + 3f;
        boolean nextEnabled = enabled && valid;
        if (this.enabled == nextEnabled && this.darkLux == darkLux && this.brightLux == brightLux) return;
        this.enabled = nextEnabled;
        if (valid) { this.darkLux = darkLux; this.brightLux = brightLux; }
        reset();
    }

    public void sample(float lux, long now) {
        if (!enabled || !Float.isFinite(lux) || lux < 0 || now < 0) {
            reset();
            return;
        }
        boolean target = dark;
        if (lux <= darkLux) target = true;
        else if (lux >= brightLux) target = false;
        if (target == dark) { deadline = -1; return; }
        if (deadline < 0 || pendingDark != target) {
            pendingDark = target;
            deadline = now + (target ? DARK_DELAY_MS : BRIGHT_DELAY_MS);
        }
        tick(now);
    }

    public void tick(long now) {
        if (enabled && deadline >= 0 && now >= deadline) {
            dark = pendingDark;
            deadline = -1;
        }
    }

    public long deadline() { return deadline; }
    public boolean isDark() { return enabled && dark; }
    public void reset() { dark = false; deadline = -1; pendingDark = false; }
}
