package dev.local.sunwindowdemo.features.shade;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

final class QsGridPreferences {
    enum Option {
        PORTRAIT_COLUMNS("portrait_columns", 0, 6, 0),
        PORTRAIT_ROWS("portrait_rows", 0, 6, 0),
        PORTRAIT_COMPACT_ROWS("portrait_compact_rows", 0, 3, 0),
        LANDSCAPE_COLUMNS("landscape_columns", 0, 8, 0),
        LANDSCAPE_ROWS("landscape_rows", 0, 6, 0),
        LANDSCAPE_COMPACT_ROWS("landscape_compact_rows", 0, 3, 0),
        LABEL_SIZE("label_size", 70, 130, 100),
        SECONDARY_LABEL_SIZE("secondary_label_size", 70, 130, 100),
        ROUND_TILES("round_tiles", 0, 1, 0);

        final String key;
        final int min, max, initial;

        Option(String id, int min, int max, int initial) {
            this.key = "qs_grid_" + id;
            this.min = min;
            this.max = max;
            this.initial = initial;
        }

        int normalize(int value) {
            if ((this == PORTRAIT_COLUMNS || this == LANDSCAPE_COLUMNS) && value == 1) return initial;
            return value >= min && value <= max ? value : initial;
        }
    }

    private volatile int[] values = defaults();

    private static int[] defaults() {
        var values = new int[Option.values().length];
        for (Option option : Option.values()) values[option.ordinal()] = option.initial;
        return values;
    }

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

    void load(Bundle settings) {
        var next = defaults();
        if (settings != null) {
            for (Option option : Option.values()) next[option.ordinal()] = option.normalize(settings.getInt(option.key, option.initial));
        }
        values = next;
    }

    int get(Option option) { return values[option.ordinal()]; }
}
