package dev.local.sunwindowdemo.features.shade;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.Arrays;

final class QsSliderPreferences {
    enum Option {
        BRIGHTNESS_VISIBILITY("brightness_visibility", 0, 2, 0),
        ADAPTIVE_BRIGHTNESS_SHORTCUT("adaptive_brightness_shortcut", 0, 1, 0),
        MEDIA_SLIDER("media_slider", 0, 1, 0),
        VOLUME_COMPACT("volume_compact", 0, 1, 0),
        SLIDER_LAYOUT("slider_layout", 0, 1, 0),
        VOLUME_UNMUTE("volume_unmute", 1, 100, 50);

        final String key;
        final int min, max, initial;

        Option(String id, int min, int max, int initial) {
            this.key = "qs_" + id;
            this.min = min;
            this.max = max;
            this.initial = initial;
        }

        int normalize(int value) { return value >= min && value <= max ? value : initial; }
    }

    private int[] values = new int[Option.values().length];

    static int read(Context context, Option option) {
        return option.normalize(ShadeStore.preferences(context).getInt(option.key, option.initial));
    }

    static void set(Context context, Option option, int value) {
        ShadeStore.preferences(context).edit().putInt(option.key, option.normalize(value)).apply();
        context.sendBroadcast(new Intent(ShadeStore.CHANGED).setPackage(ShadeStore.SYSTEM_UI), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    static void writeTo(Context context, Bundle result) {
        for (Option option : Option.values()) result.putInt(option.key, read(context, option));
    }

    boolean load(Bundle settings) {
        var next = new int[values.length];
        for (Option option : Option.values()) {
            int stored = settings == null ? option.initial : settings.getInt(option.key, option.initial);
            next[option.ordinal()] = option.normalize(stored);
        }
        if (Arrays.equals(values, next)) return false;
        values = next;
        return true;
    }

    int get(Option option) { return values[option.ordinal()]; }
}
