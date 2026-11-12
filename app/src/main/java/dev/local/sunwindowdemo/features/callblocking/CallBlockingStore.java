package dev.local.sunwindowdemo.features.callblocking;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** App/Provider-side storage only. System hooks obtain a snapshot through the authenticated provider. */
public final class CallBlockingStore {
    public static final Uri CONFIG_URI = Uri.parse("content://dev.nativecomplements.module.callblocking/config");
    public static final Uri RECORDS_URI = Uri.parse("content://dev.nativecomplements.module.callblocking/records");
    private static final int MAX_RECORDS = 200;
    private CallBlockingStore() {}

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("call_blocking", Context.MODE_PRIVATE);
    }

    public static Bundle readConfig(Context context) {
        SharedPreferences p = preferences(context);
        Bundle result = new Bundle();
        result.putInt("schema", 1);
        result.putBoolean("enabled", p.getBoolean("enabled", false));
        result.putBoolean("sim1_reject_all", p.getBoolean("sim1_reject_all", false));
        result.putStringArray("sim1_patterns", patterns(p, "sim1_patterns"));
        result.putBoolean("sim2_follow_sim1", p.getBoolean("sim2_follow_sim1", false));
        result.putBoolean("sim2_reject_all", p.getBoolean("sim2_reject_all", false));
        result.putStringArray("sim2_patterns", patterns(p, "sim2_patterns"));
        for (int slot = 0; slot < 2; slot++) {
            result.putBoolean(key(slot, "reject_strangers"), p.getBoolean(key(slot, "reject_strangers"), false));
            result.putBoolean(key(slot, "allow_repeat"), p.getBoolean(key(slot, "allow_repeat"), false));
        }
        result.putLong("updated_at", p.getLong("updated_at", 0));
        result.putLong("backend_seen_at", p.getLong("backend_seen_at", 0));
        result.putString("backend_version", p.getString("backend_version", ""));
        result.putString("backend_error", p.getString("backend_error", ""));
        return result;
    }

    private static String[] patterns(SharedPreferences prefs, String key) {
        try {
            JSONArray array = new JSONArray(prefs.getString(key, "[]"));
            ArrayList<String> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
            return result.toArray(new String[0]);
        } catch (Exception ignored) { return new String[0]; }
    }

    public static void setEnabled(Context context, boolean enabled) {
        save(context, preferences(context).edit().putBoolean("enabled", enabled));
    }

    private static String key(int slot, String suffix) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("仅支持 SIM 1 和 SIM 2");
        return "sim" + (slot + 1) + "_" + suffix;
    }

    public static void setRejectAll(Context context, int slot, boolean rejectAll) {
        save(context, preferences(context).edit().putBoolean(key(slot, "reject_all"), rejectAll));
    }

    public static void setRejectStrangers(Context context, int slot, boolean enabled) {
        save(context, preferences(context).edit().putBoolean(key(slot, "reject_strangers"), enabled));
    }

    public static void setAllowRepeat(Context context, int slot, boolean enabled) {
        save(context, preferences(context).edit().putBoolean(key(slot, "allow_repeat"), enabled));
    }

    public static void setFollowSim1(Context context, boolean follow) {
        // Do not overwrite SIM2's independent values; switching follow off restores them immediately.
        save(context, preferences(context).edit().putBoolean("sim2_follow_sim1", follow));
    }

    public static void setPatterns(Context context, int slot, String[] values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(CallBlockingRules.normalizePattern(value));
        if (normalized.size() > 100) throw new IllegalArgumentException("每张卡最多保存 100 条规则");
        save(context, preferences(context).edit().putString(key(slot, "patterns"), new JSONArray(normalized).toString()));
    }

    private static void save(Context context, SharedPreferences.Editor editor) {
        editor.putLong("updated_at", System.currentTimeMillis()).apply();
        context.getContentResolver().notifyChange(CONFIG_URI, null);
    }

    public static void updateBackend(Context context, String version, String error) {
        preferences(context).edit().putLong("backend_seen_at", System.currentTimeMillis())
                .putString("backend_version", version == null ? "" : version)
                .putString("backend_error", error == null ? "" : error).apply();
    }

    public static synchronized ArrayList<Bundle> readRecords(Context context) {
        ArrayList<Bundle> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences(context).getString("records", "[]"));
            for (int i = 0; i < Math.min(array.length(), MAX_RECORDS); i++) {
                JSONObject item = array.getJSONObject(i);
                Bundle value = new Bundle();
                value.putLong("time", item.optLong("time"));
                value.putInt("slot_index", item.optInt("slot_index", -1));
                value.putInt("subscription_id", item.optInt("subscription_id", -1));
                for (String field : new String[]{"number", "sim_label", "reason", "matched_pattern"})
                    value.putString(field, item.optString(field, ""));
                records.add(value);
            }
        } catch (Exception ignored) { }
        return records;
    }

    private static String bounded(Bundle value, String field, int length) {
        String text = value.getString(field, "");
        return text.substring(0, Math.min(text.length(), length));
    }

    public static synchronized boolean recordBlocked(Context context, Bundle value) {
        if (value == null) return false;
        try {
            JSONObject first = new JSONObject();
            first.put("time", value.getLong("time", System.currentTimeMillis()));
            first.put("slot_index", value.getInt("slot_index", -1));
            first.put("subscription_id", value.getInt("subscription_id", -1));
            first.put("number", bounded(value, "number", 128));
            first.put("sim_label", bounded(value, "sim_label", 80));
            first.put("reason", bounded(value, "reason", 128));
            first.put("matched_pattern", bounded(value, "matched_pattern", 64));
            JSONArray old = new JSONArray(preferences(context).getString("records", "[]"));
            JSONArray result = new JSONArray().put(first);
            for (int i = 0; i < Math.min(old.length(), MAX_RECORDS - 1); i++) result.put(old.get(i));
            boolean saved = preferences(context).edit().putString("records", result.toString()).commit();
            if (saved) context.getContentResolver().notifyChange(RECORDS_URI, null);
            return saved;
        } catch (Exception ignored) { return false; }
    }

    public static synchronized void clearRecords(Context context) {
        preferences(context).edit().remove("records").apply();
        context.getContentResolver().notifyChange(RECORDS_URI, null);
    }
}
