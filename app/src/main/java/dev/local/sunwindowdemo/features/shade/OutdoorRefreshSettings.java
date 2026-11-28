package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

final class OutdoorRefreshSettings {
    static final Uri URI = Uri.parse("content://" + ShadeStore.AUTHORITY + "/outdoor-refresh");
    static final String READ = "outdoorRefresh";
    private static final String ENABLED = "outdoor_refresh_enabled";
    private static final String TRIGGER = "outdoor_refresh_trigger";
    private static final String BRIGHTNESS = "outdoor_refresh_brightness";
    private static final String LIGHT = "outdoor_refresh_light";
    private static final int DEFAULT_BRIGHTNESS = 80;
    private static final int DEFAULT_LIGHT = 6000;

    enum Trigger { MANUAL, BRIGHTNESS, LIGHT }

    record Options(boolean enabled, Trigger trigger, int brightness, int light) {
        Options {
            brightness = Math.clamp(brightness, 1, 100);
            light = Math.clamp(light, 100, 30_000);
        }

        static Options from(Bundle values) {
            return new Options(values.getBoolean(ENABLED), readTrigger(values.getString(TRIGGER)),
                    values.getInt(BRIGHTNESS, DEFAULT_BRIGHTNESS), values.getInt(LIGHT, DEFAULT_LIGHT));
        }
    }

    private OutdoorRefreshSettings() {}

    static boolean supportedDevice() {
        return Build.VERSION.SDK_INT == 37 && "dada".equals(Build.DEVICE);
    }

    static Options read(Context context) {
        var prefs = ShadeStore.preferences(context);
        return new Options(prefs.getBoolean(ENABLED, false), readTrigger(prefs.getString(TRIGGER, null)),
                prefs.getInt(BRIGHTNESS, DEFAULT_BRIGHTNESS), prefs.getInt(LIGHT, DEFAULT_LIGHT));
    }

    static void save(Context context, Options options) {
        ShadeStore.preferences(context).edit().putBoolean(ENABLED, options.enabled())
                .putString(TRIGGER, options.trigger().name())
                .putInt(BRIGHTNESS, options.brightness()).putInt(LIGHT, options.light()).apply();
        context.getContentResolver().notifyChange(URI, null);
    }

    static Bundle snapshot(Context context) {
        var options = read(context);
        var values = new Bundle();
        values.putBoolean(ENABLED, options.enabled());
        values.putString(TRIGGER, options.trigger().name());
        values.putInt(BRIGHTNESS, options.brightness());
        values.putInt(LIGHT, options.light());
        return values;
    }

    private static Trigger readTrigger(String stored) {
        if (stored != null) {
            for (var trigger : Trigger.values()) {
                if (trigger.name().equals(stored)) return trigger;
            }
        }
        return Trigger.MANUAL;
    }
}
