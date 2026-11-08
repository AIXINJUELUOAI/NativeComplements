package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Config {
    final SharedPreferences prefs;
    Config(Context c) { prefs = c.getSharedPreferences("corner", Context.MODE_PRIVATE); }
    int number(String key, int def, int min, int max) { return Rules.clamp(prefs.getInt(key, def), min, max); }
    static final int HEAT_MIN=36,HEAT_MAX=72;
    int heat() { return number("heat", HEAT_MIN, HEAT_MIN, HEAT_MAX); }
    boolean hideTopHint() { return prefs.getBoolean("hideTopHint", false); }
    int distance() { return number("distance", 42, 24, 100); }
    int selectionDistance() { return number("selectionDistance", 90, 50, 100); }
    int radius() { return number("radius", 180, 120, 260); }
    int icon() { return number("icon", 44, 32, 56); }
    boolean landscapeOff() { return prefs.getBoolean("landscape", true); }
    boolean keyboardOff() { return prefs.getBoolean("keyboard", true); }
    boolean hideRecents() { return prefs.getBoolean("hideRecents", false); }
    boolean doubleClose() { return prefs.getBoolean("doubleClose", true); }
    boolean showHeat() { return prefs.getBoolean("showHeat", false); }
    List<String> apps() {
        Set<String> unique = new LinkedHashSet<>();
        for (String item : prefs.getString("apps", "").split(",")) {
            if (Rules.validPackage(item) && unique.size() < 5) unique.add(item);
        }
        return new ArrayList<>(unique);
    }
    void apps(List<String> apps) { prefs.edit().putString("apps", String.join(",", apps)).apply(); }
    Set<String> blocked() { return new LinkedHashSet<>(prefs.getStringSet("blocked", Set.of())); }
}
