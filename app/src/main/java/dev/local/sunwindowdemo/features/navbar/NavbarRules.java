package dev.local.sunwindowdemo.features.navbar;

import android.os.Bundle;
import org.json.JSONObject;

/** Immutable merged template snapshot. Explicit user rules always win, including ORIGINAL. */
public final class NavbarRules {
    public record Choice(int mode, Integer color, String source, int policyFlags) {
        public Choice(int mode, Integer color, String source) { this(mode, color, source, 0); }
        public boolean consumesNavigationInsets() {
            return mode == NavbarStore.FLOATING && (policyFlags == 0 || (policyFlags & 2) != 0);
        }
    }
    private final JSONObject packages;
    /** This device's learned pages: "package/Activity" -> ORIGINAL, COLOR or FLOATING (mixed pages are absent). */
    private final JSONObject learned;
    private final Bundle settings;
    private final java.util.concurrent.ConcurrentHashMap<String, Choice> resolved = new java.util.concurrent.ConcurrentHashMap<>();

    public NavbarRules(Bundle snapshot) {
        settings = new Bundle(snapshot);
        JSONObject parsed;
        try { parsed = new JSONObject(snapshot.getString("templates", "{}")).optJSONObject("packages"); }
        catch (org.json.JSONException invalid) { parsed = null; }
        packages = parsed == null ? new JSONObject() : parsed;
        JSONObject local;
        try { local = new JSONObject(snapshot.getString("learned", "{}")); }
        catch (org.json.JSONException invalid) { local = null; }
        learned = local == null ? new JSONObject() : local;
    }

    /**
     * Initial AUTO mode for a page without an in-memory decision: a bundled learned rule (any app version),
     * else this device's learned result. null = unknown, start with the native layout.
     */
    public Integer seed(String packageName, String activity) {
        JSONObject app = packages.optJSONObject(packageName);
        JSONObject rules = app == null ? null : app.optJSONObject("rules");
        JSONObject rule = rules == null ? null : rules.optJSONObject(activity);
        if (rule != null && rule.optBoolean("learned")) {
            int mode = rule.optInt("mode", -1);
            if (mode >= NavbarStore.ORIGINAL && mode <= NavbarStore.FLOATING) return mode;
        }
        int mode = learned.optInt(packageName + "/" + activity, -1);
        return mode >= NavbarStore.ORIGINAL && mode <= NavbarStore.FLOATING ? mode : null;
    }

    /**
     * Rule for one page inside an Activity ("Activity#signature"): the user's page rule, else (when no user
     * Activity/app rule exists) a bundled rule for exactly that page. null = use the Activity's resolution.
     */
    public Choice page(String packageName, String page, long version) {
        if (!settings.getBoolean("enabled", false)) return null;
        String userKey = packageName + "/" + page;
        if (settings.containsKey(userKey)) return new Choice(valid(settings.getInt(userKey)), null, "页面规则");
        int hash = page.indexOf('#');
        if (hash <= 0 || settings.containsKey(packageName + "/" + page.substring(0, hash))
                || settings.containsKey(packageName + "/*") || packageName.startsWith("com.google.android.")) return null;
        JSONObject app = packages.optJSONObject(packageName);
        JSONObject rules = app == null || !app.optBoolean("enabled") ? null : app.optJSONObject("rules");
        JSONObject rule = rules == null ? null : rules.optJSONObject(page);
        if (rule == null || rule.optBoolean("learned") && version != rule.optLong("versionCode", -2)) return null;
        return new Choice(valid(rule.optInt("mode", 0)), rule.has("color") ? rule.optInt("color") : null, rule.optString("source"));
    }

    public Choice resolve(String packageName, String activity, long version) {
        return resolve(packageName, activity, version, true);
    }

    public Choice resolve(String packageName, String activity, long version, boolean isBaseApplication) {
        if (!settings.getBoolean("enabled", false)) return new Choice(0, null, "已关闭");
        String key = packageName + "/" + activity + "/" + version + "/" + isBaseApplication;
        return resolved.computeIfAbsent(key, ignored -> resolveEnabled(packageName, activity, version, isBaseApplication));
    }

    private Choice resolveEnabled(String packageName, String activity, long version, boolean isBaseApplication) {
        String userKey = packageName + "/" + activity;
        if (settings.containsKey(userKey)) return new Choice(valid(settings.getInt(userKey)), null, "用户规则");
        String packageKey = packageName + "/*";
        if (settings.containsKey(packageKey)) return new Choice(valid(settings.getInt(packageKey)), null, "用户规则");
        // Google apps already draw their own navigation area.
        if (packageName.startsWith("com.google.android.")) return new Choice(NavbarStore.ORIGINAL, null, "白名单");
        JSONObject app = packages.optJSONObject(packageName);
        if (app != null && !app.optBoolean("enabled"))
            return new Choice(0, null, "模板关闭适配");
        if (app != null && app.optBoolean("enabled")
                && version > app.optLong("disableVersionCode", -1)) {
            JSONObject translucent = app.optJSONObject("translucent_rules");
            JSONObject transparent = translucent == null ? null : translucent.optJSONObject(activity);
            if (transparent != null && (isBaseApplication || !transparent.optBoolean("base_only", true))) {
                int flags = transparent.optInt("policy_flag", 4);
                if ((flags & 4) != 0) return new Choice(NavbarStore.FLOATING, null,
                        transparent.optString("source", "ColorOS 透明策略"), flags);
            }
            JSONObject rules = app.optJSONObject("rules");
            if (rules != null) {
                String match = bestMatch(rules, activity);
                JSONObject rule = match == null ? null : rules.optJSONObject(match);
                // A learned rule is fixed only for the app version it was learned on; otherwise it seeds AUTO.
                if (rule != null && rule.optBoolean("learned") && version != rule.optLong("versionCode", -2)) rule = null;
                if (rule != null && (isBaseApplication || !rule.optBoolean("base_only", true)))
                    return new Choice(valid(rule.optInt("mode", 0)),
                        rule.has("color") ? rule.optInt("color") : null, rule.optString("source"));
            }
        }
        return new Choice(NavbarStore.AUTO, null, "自动");
    }

    private static int valid(int mode) { return mode >= 0 && mode <= NavbarStore.AUTO ? mode : 0; }

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
