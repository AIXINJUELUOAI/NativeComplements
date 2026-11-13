package dev.local.sunwindowdemo.features.navbar;

import android.os.Bundle;
import org.json.JSONObject;

/** Immutable merged template snapshot. Explicit user rules always win, including ORIGINAL. */
public final class NavbarRules {
    public record Choice(int mode, Integer color, String source) {}
    private final JSONObject packages;
    private final Bundle settings;
    private final java.util.concurrent.ConcurrentHashMap<String, Choice> resolved = new java.util.concurrent.ConcurrentHashMap<>();

    public NavbarRules(Bundle snapshot) {
        settings = new Bundle(snapshot);
        JSONObject parsed;
        try { parsed = new JSONObject(snapshot.getString("templates", "{}")).optJSONObject("packages"); }
        catch (org.json.JSONException invalid) { parsed = null; }
        packages = parsed == null ? new JSONObject() : parsed;
    }

    public Choice resolve(String packageName, String activity, long version) {
        if (!settings.getBoolean("enabled", false)) return new Choice(0, null, "已关闭");
        String key = packageName + "/" + activity + "/" + version;
        return resolved.computeIfAbsent(key, ignored -> resolveEnabled(packageName, activity, version));
    }

    private Choice resolveEnabled(String packageName, String activity, long version) {
        String userKey = packageName + "/" + activity;
        if (settings.containsKey(userKey)) return new Choice(valid(settings.getInt(userKey)), null, "用户规则");
        String packageKey = packageName + "/*";
        if (settings.containsKey(packageKey)) return new Choice(valid(settings.getInt(packageKey)), null, "用户规则");
        JSONObject app = packages.optJSONObject(packageName);
        if (settings.getBoolean("templates_enabled", true) && app != null && !app.optBoolean("enabled"))
            return new Choice(0, null, "模板关闭适配");
        if (settings.getBoolean("templates_enabled", true) && app != null && app.optBoolean("enabled")
                && version > app.optLong("disableVersionCode", -1)) {
            JSONObject rules = app.optJSONObject("rules");
            if (rules != null) {
                String match = bestMatch(rules, activity);
                JSONObject rule = match == null ? null : rules.optJSONObject(match);
                if (rule != null) return new Choice(valid(rule.optInt("mode", 0)),
                        rule.has("color") ? rule.optInt("color") : null, rule.optString("source"));
            }
        }
        return new Choice(valid(settings.getInt("default_mode", 0)), null, "通用策略");
    }

    private static int valid(int mode) { return mode >= 0 && mode <= 2 ? mode : 0; }

    private static String bestMatch(JSONObject rules, String activity) {
        if (rules.has(activity)) return activity;
        String best = null;
        int specificity = -1;
        for (var keys = rules.keys(); keys.hasNext();) {
            String key = keys.next();
            int length = key.replace("*", "").length();
            if (matches(key, activity) && (length > specificity
                    || (length == specificity && best != null && key.compareTo(best) < 0))) {
                best = key;
                specificity = length;
            }
        }
        return best;
    }

    static boolean matches(String pattern, String value) {
        int p = 0, v = 0, star = -1, retry = 0;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) == value.charAt(v)) { p++; v++; }
            else if (p < pattern.length() && pattern.charAt(p) == '*') { star = p++; retry = v; }
            else if (star >= 0) { p = star + 1; v = ++retry; }
            else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
