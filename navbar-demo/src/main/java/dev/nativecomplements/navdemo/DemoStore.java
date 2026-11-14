package dev.nativecomplements.navdemo;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Local bounded metadata only. No screenshots, pixels, or network transport. */
public final class DemoStore {
    private static final long LIMIT = 4 * 1024 * 1024;
    private static File file(Context c, String name) { return new File(c.getFilesDir(), name); }
    static synchronized void append(Context c, String type, Bundle data) throws IOException {
        JSONObject entry = new JSONObject();
        try {
            entry.put("type", type); entry.put("wallTime", System.currentTimeMillis());
            for (String key : data.keySet()) {
                Object value = data.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean) entry.put(key, value);
            }
        } catch (org.json.JSONException error) { throw new IOException(error); }
        File current = file(c, "observations.jsonl"), previous = file(c, "observations.previous.jsonl");
        if (current.length() >= LIMIT) {
            if (previous.exists() && !previous.delete()) throw new IOException("无法轮转旧日志");
            if (!current.renameTo(previous)) throw new IOException("无法轮转日志");
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) {
            out.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    public static synchronized void export(Context c, OutputStream output) throws IOException {
        for (String name : new String[]{"observations.previous.jsonl", "observations.jsonl"}) {
            File source = file(c, name);
            if (source.exists()) try (InputStream input = new FileInputStream(source)) { input.transferTo(output); }
        }
    }
    static synchronized void clear(Context c) throws IOException {
        for (String name : new String[]{"observations.previous.jsonl", "observations.jsonl"}) {
            File target = file(c, name);
            if (target.exists() && !target.delete()) throw new IOException("无法清空日志");
        }
    }
}
