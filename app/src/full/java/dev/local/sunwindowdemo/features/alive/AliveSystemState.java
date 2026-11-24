package dev.local.sunwindowdemo.features.alive;

import android.content.Context;
import android.provider.Settings;

/** Current app user's persistent AOD preference, not transient doze/power/occlusion state. */
public final class AliveSystemState {
    public static final String AOD_SETTING="doze_always_on";
    private AliveSystemState() {}
    /** -1 means unavailable/unsupported, never silently report that as disabled. */
    public static int aod(Context context) {
        try {
            int value=Settings.Secure.getInt(context.getContentResolver(),AOD_SETTING);
            return value==0||value==1?value:-1;
        } catch (Settings.SettingNotFoundException|RuntimeException unavailable) { return -1; }
    }
}
