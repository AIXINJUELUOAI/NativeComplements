package dev.local.sunwindowdemo.core.backup;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.UserManager;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import dev.local.sunwindowdemo.QuickActionCatalog;
import dev.local.sunwindowdemo.Rules;
import dev.local.sunwindowdemo.core.LauncherTargets;
import dev.local.sunwindowdemo.core.ModuleIdentity;
import dev.local.sunwindowdemo.features.background.BackgroundPolicy;
import dev.local.sunwindowdemo.features.background.BackgroundStore;
import dev.local.sunwindowdemo.features.callblocking.CallBlockingRules;
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore;
import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingRules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User settings only. No permissions, backend eligibility, assets, history or host reports. */
public final class SettingsBackup {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final String MODULE = ModuleIdentity.FULL_PACKAGE;
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final Field BOOL = new Field("bool", value -> {
        if (!(value instanceof Boolean)) throw invalid("需要布尔值"); return value;
    });
    private static final Map<String, Map<String, Field>> SCHEMA = schema();

    private SettingsBackup() {}

    /** Opaque validated values: nested maps and sets are privately copied and never exposed. */
    public static final class Snapshot {
        private final Map<String, Map<String, Object>> groups;
        private Snapshot(Map<String, Map<String, Object>> values) {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            for (var group : values.entrySet()) {
                Map<String, Object> entries = new LinkedHashMap<>();
                for (var entry : group.getValue().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Set<?>) value = Collections.unmodifiableSet(new LinkedHashSet<>((Set<?>) value));
                    entries.put(entry.getKey(), value);
                }
                copy.put(group.getKey(), Collections.unmodifiableMap(entries));
            }
            groups = Collections.unmodifiableMap(copy);
        }
        public int groupCount() { return groups.size(); }
        public int settingCount() { return groups.values().stream().mapToInt(Map::size).sum(); }
    }

    /** Persistence succeeded; only the subsequent host refresh was incomplete. Do not retry import. */
    public static final class NotificationException extends IllegalStateException {
        private NotificationException(Throwable cause) { super("设置已保存，但宿主刷新未完成，请重新打开相关功能页面", cause); }
    }

    public static byte[] export(Context context) {
        Context app = ownContext(context);
        // ponytail: one app-context lock serializes backup operations, not unrelated store setters.
        synchronized (app) {
            requireWindowInitialized(app); // Backup must never create legacy evidence that enables the backend.
            dev.local.sunwindowdemo.features.barrage.BarrageStore.read(app);
            Map<String, Object> groups = new LinkedHashMap<>();
            for (var group : SCHEMA.entrySet()) {
                SharedPreferences prefs = prefs(app, group.getKey());
                Map<String, Object> entries = new LinkedHashMap<>();
                for (var field : group.getValue().entrySet()) if (prefs.contains(field.getKey())) {
                    Object value = field.getValue().read(prefs, field.getKey());
                    // Round-trip through the same validator; never export a malformed local value.
                    entries.put(field.getKey(), field.getValue().validate(jsonValue(value)));
                }
                validateGroup(group.getKey(), entries);
                groups.put(group.getKey(), entries);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1); root.put("module", MODULE); root.put("groups", groups);
            byte[] bytes = json(root).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw invalid("备份超过 1 MiB");
            return bytes;
        }
    }

    public static Snapshot parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("备份大小无效（最多 1 MiB）");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("备份不是有效 UTF-8", failure); }
        Map<String, Object> root = object(strictJson(text));
        keys(root, Set.of("version", "module", "groups"));
        if (root.size() != 3 || integer(root.get("version"), 1, 1) != 1 || !MODULE.equals(root.get("module")))
            throw invalid("不支持的备份版本或模块");
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (var group : object(root.get("groups")).entrySet()) {
            Map<String, Field> fields = SCHEMA.get(group.getKey());
            if (fields == null) throw invalid("未知设置组: " + group.getKey());
            Map<String, Object> entries = new LinkedHashMap<>();
            for (var entry : object(group.getValue()).entrySet()) {
                Field field = fields.get(entry.getKey());
                if (field == null) throw invalid("未知设置键: " + group.getKey() + "/" + entry.getKey());
                entries.put(entry.getKey(), field.validate(entry.getValue()));
            }
            validateGroup(group.getKey(), entries);
            groups.put(group.getKey(), entries);
        }
        return new Snapshot(groups);
    }

    /** Does not close the caller-owned SAF stream, and reads at most MAX_BYTES + 1 bytes. */
    public static Snapshot read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法读取备份");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - out.size()));
            if (count == -1) break;
            if (count == 0) {
                int next = input.read();
                if (next == -1) break;
                out.write(next);
            } else out.write(buffer, 0, count);
            if (out.size() > MAX_BYTES) throw new IOException("备份超过 1 MiB");
        }
        return parse(out.toByteArray());
    }

    /** Replaces only supported keys of present groups. This is NOT a cross-store atomic transaction. */
    public static void restore(Context context, Snapshot snapshot) {
        Context app = ownContext(context);
        if (snapshot == null) throw invalid("缺少备份");
        synchronized (app) {
            if (snapshot.groups.containsKey("corner")) requireWindowInitialized(app);
            Map<String, SharedPreferences> stores = new LinkedHashMap<>();
            Map<String, Map<String, Object>> before = new LinkedHashMap<>();
            // Read every affected store before making the first edit (including absent supported keys).
            for (String group : snapshot.groups.keySet()) {
                SharedPreferences store = prefs(app, group);
                Map<String, Object> values = new LinkedHashMap<>();
                for (var field : SCHEMA.get(group).entrySet()) if (store.contains(field.getKey()))
                    values.put(field.getKey(), field.getValue().read(store, field.getKey()));
                if (group.equals("barrage") && store.contains("migrated")) values.put("migrated", store.getBoolean("migrated", false));
                stores.put(group, store); before.put(group, values);
            }
            List<String> attempted = new ArrayList<>();
            try {
                for (var group : snapshot.groups.entrySet()) {
                    attempted.add(group.getKey()); // commit(false) can still have changed in-memory values.
                    Map<String, Object> values = new LinkedHashMap<>(group.getValue());
                    // Missing means legacy-on to Config; explicit false is today's default, not a migration request.
                    if (group.getKey().equals("corner")) values.putIfAbsent("longBackWindow", false);
                    // Derived local compatibility state, never accepted from the backup document.
                    if (group.getKey().equals("barrage")) values.put("migrated", true);
                    if (!replace(stores.get(group.getKey()), group.getKey(), values))
                        throw new IllegalStateException("设置写入失败: " + group.getKey());
                }
            } catch (RuntimeException failure) {
                IllegalStateException error = new IllegalStateException("恢复失败，已尝试回滚；跨设置存储不保证原子性", failure);
                for (int i = attempted.size() - 1; i >= 0; i--) {
                    String group = attempted.get(i);
                    try {
                        if (!replace(stores.get(group), group, before.get(group)))
                            error.addSuppressed(new IllegalStateException("回滚写入失败: " + group));
                    } catch (RuntimeException rollback) { error.addSuppressed(rollback); }
                }
                throw error;
            }
            RuntimeException notifyFailure = null;
            for (String group : snapshot.groups.keySet()) try { notifyChanged(app, group); }
            catch (RuntimeException failure) {
                if (notifyFailure == null) notifyFailure = failure; else notifyFailure.addSuppressed(failure);
            }
            if (notifyFailure != null) throw new NotificationException(notifyFailure);
        }
    }

    private static Context ownContext(Context context) {
        if (context == null) throw new SecurityException("缺少应用上下文");
        Context app = context.getApplicationContext();
        if (app == null || context.isDeviceProtectedStorage() || app.isDeviceProtectedStorage()
                || !MODULE.equals(context.getPackageName()) || !MODULE.equals(app.getPackageName())
                || app.getApplicationInfo().uid != Process.myUid()) throw new SecurityException("仅模块自身已解锁的管理界面可备份设置");
        UserManager users = app.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) throw new IllegalStateException("请先解锁当前用户");
        return app;
    }

    private static void requireWindowInitialized(Context context) {
        SharedPreferences corner = prefs(context, "corner");
        if (!corner.contains("enabled")) throw new IllegalStateException("请先打开管理界面完成设置初始化");
        corner.getBoolean("enabled", false); // Reject a corrupt local type before touching any settings.
    }

    private static SharedPreferences prefs(Context context, String group) {
        return (group.equals("corner") ? context : context.createDeviceProtectedStorageContext())
                .getSharedPreferences(group, Context.MODE_PRIVATE);
    }

    private static boolean replace(SharedPreferences prefs, String group, Map<String, Object> values) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : SCHEMA.get(group).keySet()) edit.remove(key);
        if (group.equals("barrage")) edit.remove("migrated");
        for (var entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) strings.add((String) item);
                edit.putStringSet(key, strings);
            } else throw invalid("无效偏好类型");
        }
        return edit.commit();
    }

    private static void notifyChanged(Context context, String group) {
        switch (group) {
            case "corner": broadcast(context, MODULE + ".SETTINGS_CHANGED", SYSTEM_UI); break;
            case "dark_aod": context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.darkaod.DarkAodStore.URI, null); break;
            case "navbar_immersive": context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.navbar.NavbarProvider.URI, null); break;
            case "landscape_shade":
                context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.shade.ShadeStore.CALL_REFRESH_URI, null);
                context.getContentResolver().notifyChange(android.net.Uri.parse("content://" +
                        dev.local.sunwindowdemo.features.shade.ShadeStore.AUTHORITY + "/app_volume"), null);
                broadcast(context, MODULE + ".SHADE_CHANGED", SYSTEM_UI);
                for (String host : LauncherTargets.targets(context)) broadcast(context, MODULE + ".SHADE_CHANGED", host);
                IconLayerStore.refresh(context); break;
            case "edge_touch": broadcast(context, MODULE + ".EDGE_TOUCH_CHANGED", SYSTEM_UI); break;
            case "game_mode":
                broadcast(context, MODULE + ".GAMEMODE_CHANGED", SYSTEM_UI);
                broadcast(context, MODULE + ".BARRAGE_CHANGED", SYSTEM_UI); break;
            case "barrage": broadcast(context, MODULE + ".BARRAGE_CHANGED", SYSTEM_UI); break;
            case "suggestions": broadcast(context, MODULE + ".SUGGESTIONS_CHANGED", SYSTEM_UI); break;
            case "motion_access": context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.motion.MotionStore.URI, null); break;
            case "background_management": context.getContentResolver().notifyChange(BackgroundStore.URI, null); break;
            case "icon_layers": IconLayerStore.refresh(context); break;
            case "call_blocking": context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.callblocking.CallBlockingStore.CONFIG_URI, null); break;
            case "sms_blocking": context.getContentResolver().notifyChange(dev.local.sunwindowdemo.features.smsblocking.SmsBlockingStore.CONFIG_URI, null); break;
            default: throw invalid("未知设置组");
        }
    }

    private static void broadcast(Context context, String action, String host) {
        context.sendBroadcast(new Intent(action).setPackage(host), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private interface Check { Object validate(Object value); }
    private static final class Field {
        final String type; final Check check;
        Field(String type, Check check) { this.type = type; this.check = check; }
        Object validate(Object value) { return check.validate(value); }
        Object read(SharedPreferences prefs, String key) {
            switch (type) {
                case "bool": return prefs.getBoolean(key, false);
                case "int": return prefs.getInt(key, 0);
                case "float":
                    try { return prefs.getFloat(key, 0); }
                    catch (ClassCastException legacy) {
                        // The first game-corner build stored this single numeric setting as int.
                        if (key.equals("corner_radius_percent")) return prefs.getInt(key, 0);
                        throw legacy;
                    }
                case "string": return prefs.getString(key, "");
                case "set": return new LinkedHashSet<>(prefs.getStringSet(key, Set.of()));
                default: throw invalid("无效偏好类型");
            }
        }
    }

    private static Field number(int min, int max) { return new Field("int", value -> integer(value, min, max)); }
    private static Field decimal(float min, float max) { return new Field("float", value -> {
        if (!(value instanceof JsonNumber)) throw invalid("需要数字");
        float result;
        try {
            java.math.BigDecimal exact = new java.math.BigDecimal(((JsonNumber) value).raw);
            if (exact.compareTo(new java.math.BigDecimal(Float.toString(min))) < 0
                    || exact.compareTo(new java.math.BigDecimal(Float.toString(max))) > 0) throw invalid("数字超出范围");
            result = exact.floatValue();
            if (!Float.isFinite(result) || (result == 0 && exact.signum() != 0)) throw invalid("数字超出浮点范围");
        } catch (NumberFormatException failure) { throw invalid("无效数字"); }
        return result;
    }); }
    private static Field string(Check check) { return new Field("string", value -> check.validate(text(value, 131072))); }
    private static Field packages(int max) { return new Field("set", value -> {
        Set<String> result = new LinkedHashSet<>();
        for (Object item : array(value, max)) {
            String pkg = packageName(item); if (!result.add(pkg)) throw invalid("重复包名");
        }
        return result;
    }); }
    private static Field choice(String... values) { return string(value -> {
        if (!Set.of(values).contains(value)) throw invalid("未知枚举值"); return value;
    }); }
    private static Field csv(int max, boolean actions) { return string(value -> {
        String raw = (String) value;
        if (raw.isEmpty()) return raw;
        String[] entries = raw.split(",", -1);
        if (entries.length > max) throw invalid("列表过长");
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : entries) {
            if (actions) { if (entry.length() > 32 || !entry.matches("[a-z0-9]+")) throw invalid("无效快捷操作"); }
            else packageName(entry);
            if (!seen.add(entry)) throw invalid("重复列表项");
        }
        return raw;
    }); }
    private static Field structured(Check check) { return string(value -> {
        check.validate(strictJson((String) value)); return value;
    }); }

    private static Map<String, Map<String, Field>> schema() {
        Map<String, Map<String, Field>> groups = new LinkedHashMap<>();
        Map<String, Field> corner = group(groups, "corner");
        bools(corner, "longBackWindow", "hideTopHint", "landscape", "keyboard", "hideRecents", "autoMinimizeOnOpen",
                "endOnClose", "keepEdgeAwake", "doubleClose", "notificationWindow", "landscapeShareWindow", "swipeMini", "swipeMiniHint", "showHeat", "menuBlur");
        corner.put("heat", number(36, 72)); corner.put("distance", number(24, 100));
        corner.put("selectionDistance", number(50, 100)); corner.put("radius", number(120, 260));
        corner.put("icon", number(32, 56)); corner.put("fanCount", number(1, 5)); corner.put("windowDim", number(0, 100));
        corner.put("apps", csv(12, false)); corner.put("targets", structured(SettingsBackup::targets));
        corner.put("targetLabels", structured(SettingsBackup::labels));
        corner.put("blocked", packages(512)); corner.put("windowExcludedPackages", packages(512));
        Map<String, Field> dark = group(groups, "dark_aod"); bools(dark, "enabled"); dark.put("dark_lux", decimal(0, 5));
        Map<String, Field> shade = group(groups, "landscape_shade");
        bools(shade, "enabled", "signal_display", "drawer_monet", "cover_monet", "desktop_double_tap_sleep", "lockscreen_double_tap_sleep", "floating_call_refresh", "app_volume");
        shade.put("status_bar_offset", number(-32, 32)); shade.put("notification_icon_scale", number(80, 120)); shade.put("system_icon_scale", number(80, 120));
        bools(shade, "hide_udfps_background", "hide_udfps_foreground");
        shade.put("udfps_size", number(50, 200));
        bools(group(groups, "navbar_immersive"), "enabled");
        Map<String, Field> edge = group(groups, "edge_touch"); bools(edge, "enabled", "left_enabled", "right_enabled");
        edge.put("left_width_dp", number(1, 48)); edge.put("right_width_dp", number(1, 48));
        edge.put("height_percent", number(20, 100)); edge.put("position_percent", number(0, 100)); edge.put("corner_dp", number(0, 64));
        Map<String, Field> game = group(groups, "game_mode");
        bools(game, "enabled", "panel", "brightness", "corner_rejection", "corner_show_regions");
        for (String key : new String[]{"side", "handle_side_landscape", "handle_side_portrait"}) game.put(key, choice("left", "right"));
        game.put("handle_position_landscape", decimal(0, 1)); game.put("handle_position_portrait", decimal(0, 1));
        game.put("corner_radius_percent", decimal(0, 100)); game.put("apps", packages(512)); game.put("games", packages(512));
        game.put("quick_apps", csv(512, false)); game.put("quick", csv(12, true));
        Map<String, Field> barrage = group(groups, "barrage"); bools(barrage, "enabled");
        barrage.put("lines", new Field("int", value -> { int n = integer(value, 0, 5); if (n != 0 && n != 3 && n != 5) throw invalid("无效弹幕行数"); return n; }));
        barrage.put("color", new Field("int", value -> { int n = integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE); if ((n >>> 24) != 255) throw invalid("弹幕颜色必须不透明"); return n; }));
        barrage.put("speed", number(40, 240)); barrage.put("size", number(12, 32));
        Map<String, Field> suggestion = group(groups, "suggestions");
        bools(suggestion, "enabled", "torch", "bluetooth", "otp", "links", "maps", "awake", "hide_sms", "window", "rotate", "right");
        suggestion.put("delay", number(50, 500)); suggestion.put("duration", number(5, 300)); suggestion.put("size", number(32, 80));
        suggestion.put("bluetooth_app", string(value -> ((String) value).isEmpty() ? value : packageName(value)));
        Map<String, Field> motion = group(groups, "motion_access"); bools(motion, "enabled"); motion.put("default_mode", number(0, 2));
        motion.put("rules", structured(value -> { Map<String, Object> map = packageMap(value, 300); for (Object mode : map.values()) integer(mode, 0, 2); return value; }));
        Map<String, Field> background = group(groups, "background_management"); bools(background, "enabled", "background_wake_locks_enabled");
        background.put("rules", structured(SettingsBackup::backgroundRules)); background.put("background_wake_locks", structured(SettingsBackup::wakeLocks));
        Map<String, Field> icon = group(groups, "icon_layers"); bools(icon, "enabled", "notifications"); icon.put("mode", number(0, 2)); icon.put("originals", packages(512));
        for (String name : new String[]{"call_blocking", "sms_blocking"}) {
            Map<String, Field> blocking = group(groups, name); bools(blocking, "enabled", "sim2_follow_sim1");
            for (String slot : new String[]{"sim1_", "sim2_"}) {
                bools(blocking, slot + "reject_all", slot + "reject_strangers", slot + (name.equals("call_blocking") ? "allow_repeat" : "allow_verification"));
                blocking.put(slot + "patterns", structured(value -> ruleList(value, name.equals("sms_blocking"), false)));
                if (name.equals("sms_blocking")) blocking.put(slot + "keywords", structured(value -> ruleList(value, true, true)));
            }
        }
        return groups;
    }

    private static Map<String, Field> group(Map<String, Map<String, Field>> groups, String name) {
        Map<String, Field> fields = new LinkedHashMap<>(); groups.put(name, fields); return fields;
    }
    private static void bools(Map<String, Field> fields, String... names) { for (String name : names) fields.put(name, BOOL); }

    private static boolean target(String value) {
        return Rules.validPackage(value) || (value.startsWith("quick:") && QuickActionCatalog.find(value.substring(6)) != null);
    }
    private static Object targets(Object value) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : array(value, 12)) {
            String key = text(item, 240);
            if (!target(key) || !seen.add(key)) throw invalid("无效或重复快捷目标");
        }
        return value;
    }
    private static Object labels(Object value) {
        Map<String, Object> map = object(value);
        if (map.size() > 12) throw invalid("快捷名称过多");
        for (var entry : map.entrySet()) {
            if (!target(entry.getKey())) throw invalid("无效快捷名称目标"); text(entry.getValue(), 200);
        }
        return value;
    }
    private static void validateGroup(String name, Map<String, Object> values) {
        if (!name.equals("corner") || !values.containsKey("targetLabels")) return;
        Map<String, Object> labels = object(strictJson((String) values.get("targetLabels")));
        List<Object> targets = values.containsKey("targets") ? array(strictJson((String) values.get("targets")), 12) : List.of();
        if (!targets.containsAll(labels.keySet())) throw invalid("快捷名称缺少对应目标");
    }
    private static Object ruleList(Object value, boolean sms, boolean keyword) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : array(value, 100)) {
            String raw = text(item, keyword ? 40 : 64);
            String normalized = keyword ? SmsBlockingRules.normalizeKeyword(raw)
                    : sms ? SmsBlockingRules.normalizePattern(raw) : CallBlockingRules.normalizePattern(raw);
            if (!raw.equals(normalized) || !seen.add(raw)) throw invalid("规则未规范化或重复");
        }
        return value;
    }
    private static Object backgroundRules(Object value) {
        for (var entry : packageMap(value, 512).entrySet()) {
            Map<String, Object> row = object(entry.getValue());
            keys(row, Set.of("mode", "batch", "processes", "protect_playback", "keep_active"));
            if (row.containsKey("mode")) enumValue(BackgroundPolicy.Mode.class, row.get("mode"));
            if (row.containsKey("batch") && !Set.of("ALLOW", "FREEZE").contains(row.get("batch"))) throw invalid("无效批量动作");
            for (String key : new String[]{"protect_playback", "keep_active"}) if (row.containsKey(key)) BOOL.validate(row.get(key));
            if (row.containsKey("protect_playback") && !BackgroundPolicy.supportsPlaybackOverride(entry.getKey())) throw invalid("该应用不支持播放保护覆盖");
            if (row.containsKey("processes")) {
                Map<String, Object> processes = object(row.get("processes"));
                if (processes.size() > 64) throw invalid("进程规则过多");
                for (var process : processes.entrySet()) {
                    String processName = process.getKey();
                    if (processName.length() > 255 || !processName.matches("[a-zA-Z0-9_.:]+")) throw invalid("无效进程名");
                    enumValue(BackgroundPolicy.Action.class, process.getValue());
                }
            }
        }
        return value;
    }
    private static Object wakeLocks(Object value) {
        for (Object item : packageMap(value, 512).values()) {
            if (Boolean.FALSE.equals(item)) continue;
            Map<String, Object> rule = object(item); keys(rule, Set.of("delay", "budget", "period"));
            if (rule.size() != 3) throw invalid("唤醒锁规则字段不完整");
            new BackgroundStore.WakeLockRule(integer(rule.get("delay"), 0, 120), integer(rule.get("budget"), 1, 60), integer(rule.get("period"), 30, 300));
        }
        return value;
    }
    private static <E extends Enum<E>> void enumValue(Class<E> type, Object value) {
        try { Enum.valueOf(type, text(value, 32)); }
        catch (IllegalArgumentException failure) { throw invalid("未知枚举值"); }
    }
    private static Map<String, Object> packageMap(Object value, int max) {
        Map<String, Object> map = object(value);
        if (map.size() > max) throw invalid("应用规则过多");
        for (String pkg : map.keySet()) packageName(pkg);
        return map;
    }
    private static String packageName(Object value) {
        String pkg = text(value, 240); if (!Rules.validPackage(pkg)) throw invalid("无效包名"); return pkg;
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String) || ((String) value).length() > max) throw invalid("字符串类型或长度无效");
        String text = (String) value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid("无效 Unicode");
            } else if (Character.isLowSurrogate(c)) throw invalid("无效 Unicode");
        }
        return text;
    }
    private static int integer(Object value, int min, int max) {
        if (!(value instanceof JsonNumber) || !((JsonNumber) value).raw.matches("-?(0|[1-9][0-9]*)")) throw invalid("需要整数");
        try {
            long number = Long.parseLong(((JsonNumber) value).raw);
            if (number < min || number > max) throw invalid("整数超出范围"); return (int) number;
        } catch (NumberFormatException failure) { throw invalid("整数超出范围"); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("需要 JSON 对象"); return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value, int max) {
        if (!(value instanceof List<?>) || ((List<?>) value).size() > max) throw invalid("数组类型或长度无效"); return (List<Object>) value;
    }
    private static void keys(Map<String, Object> map, Set<String> allowed) {
        for (String key : map.keySet()) if (!allowed.contains(key)) throw invalid("未知字段: " + key);
    }

    private static final class JsonNumber {
        final String raw;
        JsonNumber(String raw) { this.raw = raw; }
    }
    private static Object strictJson(String text) {
        if (text == null || text.length() > MAX_BYTES || text.startsWith("\ufeff")) throw invalid("无效 JSON");
        // Android's strict reader still accepts unknown escapes, uppercase literals and object ';'.
        // Validate tokens here; JsonReader below remains responsible for the document structure.
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c < 0x20) throw invalid("JSON 字符串包含非法控制字符");
                if (c == '"') quoted = false;
                else if (c == '\\') {
                    if (++i >= text.length()) throw invalid("JSON 转义不完整");
                    char escape = text.charAt(i);
                    if (escape == 'u') {
                        if (i + 4 >= text.length()) throw invalid("JSON Unicode 转义不完整");
                        for (int end = i + 4; i < end;) {
                            char hex = text.charAt(++i);
                            if (!(hex >= '0' && hex <= '9') && !(hex >= 'a' && hex <= 'f')
                                    && !(hex >= 'A' && hex <= 'F')) throw invalid("JSON Unicode 转义无效");
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid("JSON 转义无效");
                }
            } else if (c == '"') quoted = true;
            else if ("{}[]:, \t\r\n".indexOf(c) < 0) {
                int start = i;
                while (i + 1 < text.length() && "{}[]:, \t\r\n\"".indexOf(text.charAt(i + 1)) < 0) i++;
                String token = text.substring(start, i + 1);
                if (!token.equals("true") && !token.equals("false") && !token.equals("null")
                        && (token.length() > 64 || !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    throw invalid("JSON 包含非法词法标记");
            }
        }
        if (quoted) throw invalid("JSON 字符串未结束");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = readJson(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("JSON 尾部存在多余内容");
            return value;
        } catch (IOException | IllegalStateException failure) { throw new IllegalArgumentException("无效 JSON", failure); }
    }
    private static Object readJson(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 16 || ++count[0] > 10000) throw invalid("JSON 结构过大");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> map = new LinkedHashMap<>(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = text(reader.nextName(), 255);
                    if (map.size() >= 1024 || map.containsKey(name)) throw invalid("对象过大或包含重复键");
                    map.put(name, readJson(reader, depth + 1, count));
                }
                reader.endObject(); return map;
            case BEGIN_ARRAY:
                List<Object> list = new ArrayList<>(); reader.beginArray();
                while (reader.hasNext()) {
                    if (list.size() >= 512) throw invalid("数组过长"); list.add(readJson(reader, depth + 1, count));
                }
                reader.endArray(); return list;
            case STRING: return text(reader.nextString(), 131072);
            case BOOLEAN: return reader.nextBoolean();
            case NUMBER:
                String raw = reader.nextString();
                if (raw.length() > 64 || !raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid("无效 JSON 数字");
                return new JsonNumber(raw);
            default: throw invalid("不支持的 JSON 值");
        }
    }
    private static Object jsonValue(Object value) {
        if (value instanceof Number) return new JsonNumber(value.toString());
        if (value instanceof Set<?>) return new ArrayList<>((Set<?>) value);
        return value;
    }
    private static String json(Object value) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = new JsonWriter(out)) { writeJson(writer, value); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        return out.toString();
    }
    private static void writeJson(JsonWriter writer, Object value) throws IOException {
        if (value instanceof Map<?, ?>) {
            writer.beginObject();
            for (var entry : ((Map<?, ?>) value).entrySet()) { writer.name((String) entry.getKey()); writeJson(writer, entry.getValue()); }
            writer.endObject();
        } else if (value instanceof Iterable<?>) {
            writer.beginArray(); for (Object entry : (Iterable<?>) value) writeJson(writer, entry); writer.endArray();
        } else if (value instanceof Boolean) writer.value((Boolean) value);
        else if (value instanceof Number) writer.value((Number) value);
        else if (value instanceof String) writer.value((String) value);
        else throw invalid("无效 JSON 值");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
