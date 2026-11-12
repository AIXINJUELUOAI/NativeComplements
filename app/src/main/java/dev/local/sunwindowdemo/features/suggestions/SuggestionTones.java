package dev.local.sunwindowdemo.features.suggestions;

import android.graphics.Color;

/** Shared suggestion palette: the in-app list and the SystemUI floating buttons must match. */
public final class SuggestionTones {
    public static final int TORCH_LIGHT = 0xFFB26A00, TORCH_DARK = 0xFFFFB74D;
    public static final int AWAKE_LIGHT = 0xFFC58A00, AWAKE_DARK = 0xFFFFD54F;
    public static final int OTP_LIGHT = 0xFF00897B, OTP_DARK = 0xFF4DB6AC;
    public static final int MAP_LIGHT = 0xFF2E7D32, MAP_DARK = 0xFF81C784;
    public static final int ROTATE_LIGHT = 0xFF00639B, ROTATE_DARK = 0xFF8ECDF8;
    /** Same 16% tonal container used behind the in-app suggestion glyph. */
    public static final float CONTAINER_ALPHA = 0.16f;
    private SuggestionTones() {}

    public static int tone(boolean night, int light, int dark) { return night ? dark : light; }

    public static int container(int surface, int tone) {
        return Color.rgb(
                blend(Color.red(surface), Color.red(tone)),
                blend(Color.green(surface), Color.green(tone)),
                blend(Color.blue(surface), Color.blue(tone)));
    }

    private static int blend(int base, int tone) {
        return Math.round(base * (1f - CONTAINER_ALPHA) + tone * CONTAINER_ALPHA);
    }
}
