package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;

/** Independent per-app choices; replacing a library never replaces these overrides. */
public final class IconPolicy {
    public static final int AUTO = 0, MANUAL = 1, ALGORITHM = 2, ORIGINAL = 3;
    public static final int FOLLOW = 0, UPLOAD = 1, UNCHANGED = 2;
    public int color, monochrome, notification=UNCHANGED;
    public String background = "", foreground = "", monoImage = "", notificationImage = "";
    public String generatedBackground="",generatedForeground="",generatedVersion="";

    public static Map<String, IconPolicy> parse(String value) {
        Map<String, IconPolicy> result = new HashMap<>();
        try {
            JSONObject all = new JSONObject(value);
            if (all.length() > 10000) return result;
            for (Iterator<String> names = all.keys(); names.hasNext();) {
                String pkg = names.next();
                if (IconArchive.validPackage(pkg)) result.put(pkg, from(all.optJSONObject(pkg)));
            }
        } catch (Exception ignored) {}
        return result;
    }
    private static IconPolicy from(JSONObject json) {
        IconPolicy p = new IconPolicy();
        if (json == null) return p;
        p.color = range(json.optInt("color"), 3);
        p.monochrome = range(json.optInt("monochrome"), 2);
        p.notification = range(json.optInt("notification",UNCHANGED), 2);
        p.background = asset(json.optString("background")); p.foreground = asset(json.optString("foreground"));
        p.monoImage = asset(json.optString("monoImage")); p.notificationImage = asset(json.optString("notificationImage"));
        p.generatedBackground=asset(json.optString("generatedBackground"));p.generatedForeground=asset(json.optString("generatedForeground"));
        p.generatedVersion=json.optString("generatedVersion");
        return p;
    }
    private static int range(int n, int max) { return n >= 0 && n <= max ? n : 0; }
    public static String asset(String id) { return id != null && id.matches("[a-f0-9]{64}") ? id : ""; }
    public static IconPolicy read(Context c, String pkg) {
        IconPolicy p = parse(IconLayerStore.prefs(c).getString("policies", "{}")).getOrDefault(pkg, new IconPolicy());
        if (IconLayerStore.prefs(c).getStringSet("originals", Set.of()).contains(pkg)) p.color = ORIGINAL;
        return p;
    }
    public static synchronized void save(Context c, String pkg, IconPolicy p) {
        if (!IconArchive.validPackage(pkg)) throw new IllegalArgumentException("package");
        try {
            JSONObject all = new JSONObject(IconLayerStore.prefs(c).getString("policies", "{}"));
            JSONObject json = new JSONObject();
            json.put("color", range(p.color,3)); json.put("monochrome",range(p.monochrome,2));
            json.put("notification",range(p.notification,2));
            json.put("background",asset(p.background)); json.put("foreground",asset(p.foreground));
            json.put("monoImage",asset(p.monoImage)); json.put("notificationImage",asset(p.notificationImage));
            json.put("generatedBackground",asset(p.generatedBackground));json.put("generatedForeground",asset(p.generatedForeground));
            json.put("generatedVersion",p.generatedVersion);
            all.put(pkg,json);
            var originals = new HashSet<>(IconLayerStore.prefs(c).getStringSet("originals",Set.of()));
            originals.remove(pkg);
            IconLayerStore.prefs(c).edit().putString("policies",all.toString()).putStringSet("originals",originals).apply();
            IconLayerStore.refresh(c);
        } catch (Exception e) { throw new IllegalStateException("保存图标设置失败",e); }
    }
}
