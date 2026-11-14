package dev.nativecomplements.navdemo;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;

/** System publishes metadata; only this APK can control observation, read, or annotate it. */
public final class DemoProvider extends ContentProvider {
    private static final Uri URI = Uri.parse("content://dev.nativecomplements.navdemo.data");
    private final LinkedHashMap<Long, Bundle> history = new LinkedHashMap<>();
    private Bundle latest = new Bundle();
    private long leaseUntil, request, generation;
    private static final Set<String> TEXT = Set.of("component", "decision", "reason", "algorithm", "crop", "frame", "bounds", "source", "colorSpace");
    private static final Set<String> LONGS = Set.of("id", "capturedAt", "elapsedMs", "captureMs", "classifyMs", "generation");
    private static final Set<String> INTS = Set.of("width", "height", "segments", "navHeight", "rotation", "displayId");
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        boolean own = uid == getContext().getApplicationInfo().uid;
        if ("config".equals(method)) {
            if (!own && uid != 1000) throw new SecurityException("config reader");
            Bundle config = new Bundle();
            config.putBoolean("enabled", SystemClock.elapsedRealtime() < leaseUntil);
            config.putLong("request", request);
            config.putLong("generation", generation);
            return config;
        }
        if ("sample".equals(method)) {
            if (uid != 1000) throw new SecurityException("system publisher only");
            if (SystemClock.elapsedRealtime() >= leaseUntil) return Bundle.EMPTY;
            if (extras == null || extras.getLong("generation", -1) != generation) return error("观察会话已变化，丢弃旧采样");
            Bundle sample = sanitize(extras);
            long id = sample.getLong("id", -1);
            if (id < 0) throw new IllegalArgumentException("missing sample id");
            latest = sample;
            history.put(id, sample.deepCopy());
            while (history.size() > 256) history.remove(history.keySet().iterator().next());
            Bundle result = log("sample", sample);
            getContext().getContentResolver().notifyChange(URI, null);
            return result;
        }
        if (!own) throw new SecurityException("demo owner only");
        switch (method) {
            case "control":
                boolean enabled = extras != null && extras.getBoolean("enabled", false);
                boolean wasEnabled = SystemClock.elapsedRealtime() < leaseUntil;
                leaseUntil = enabled ? SystemClock.elapsedRealtime() + 15000 : 0;
                if (!enabled || !wasEnabled) { latest = new Bundle(); history.clear(); request++; generation++; }
                return Bundle.EMPTY;
            case "request": request++; return Bundle.EMPTY;
            case "latest":
                if (SystemClock.elapsedRealtime() >= leaseUntil) {
                    Bundle stopped = new Bundle(); stopped.putString("reason", "观察已停止"); return stopped;
                }
                return latest.deepCopy();
            case "annotate":
                if (SystemClock.elapsedRealtime() >= leaseUntil) return error("观察租约已到期，请重新启动观察");
                Bundle provided = extras == null ? null : extras.getBundle("sample");
                String expected = extras == null ? "" : extras.getString("expected", "");
                if (provided == null || !Set.of("EXTEND", "NATIVE", "UNKNOWN").contains(expected))
                    throw new IllegalArgumentException("invalid annotation");
                Bundle original = history.get(provided.getLong("id", -1));
                if (original == null || !original.getBoolean("valid")) return error("样本已失效，请解锁后重新标注");
                Bundle annotation = original.deepCopy();
                annotation.putString("expected", expected);
                annotation.putLong("annotatedAt", SystemClock.elapsedRealtime());
                annotation.putBoolean("matches", expected.equals(original.getString("decision")));
                return log("annotation", annotation);
            case "clear":
                try { DemoStore.clear(getContext()); history.clear(); latest = new Bundle(); request++; generation++; return Bundle.EMPTY; }
                catch (IOException failure) { return error("日志清空失败"); }
            default: throw new IllegalArgumentException("unknown method");
        }
    }
    private Bundle log(String type, Bundle data) {
        try { DemoStore.append(getContext(), type, data); return Bundle.EMPTY; }
        catch (IOException failure) { latest.putString("logError", "日志写入失败"); return error("日志写入失败"); }
    }
    private static Bundle error(String message) { Bundle value = new Bundle(); value.putString("error", message); return value; }
    private static Bundle sanitize(Bundle input) {
        if (input == null) throw new IllegalArgumentException("missing sample");
        Bundle output = new Bundle();
        for (String key : TEXT) if (input.containsKey(key)) {
            String value = input.getString(key, ""); output.putString(key, value.substring(0, Math.min(2048, value.length())));
        }
        for (String key : LONGS) if (input.containsKey(key)) output.putLong(key, input.getLong(key));
        for (String key : INTS) if (input.containsKey(key)) output.putInt(key, input.getInt(key));
        for (String key : new String[]{"stableRatio", "edgeRatio"}) if (input.containsKey(key)) {
            double value = input.getDouble(key); if (Double.isFinite(value)) output.putDouble(key, value);
        }
        output.putFloat("density", input.getFloat("density", 0));
        output.putBoolean("valid", input.getBoolean("valid", false));
        return output;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
