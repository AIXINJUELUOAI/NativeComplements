package dev.nativecomplements.navdemo;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private local diagnostics. Images are the analysed region, saved only when the user labels a sample. */
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
    static synchronized void saveScreenshot(Context c, String token, String format, byte[] bytes, Bundle metadata) throws IOException {
        if (!token.matches("[a-f0-9-]{36}") || !("png".equals(format) || "jpg".equals(format))
                || bytes == null || bytes.length == 0 || bytes.length > DemoCapture.MAX_IMAGE)
            throw new IOException("截图参数无效");
        File directory = file(c, "pending-images");
        if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("无法创建截图目录");
        File[] existing = directory.listFiles();
        if (existing == null || existing.length >= 800) throw new IOException("标注截图已满（400张），请先导出并清空记录");
        File image = new File(directory, token + "." + format);
        File sidecar = new File(directory, token + ".json");
        File imageTemp = new File(directory, token + "." + format + ".tmp"), jsonTemp = new File(directory, token + ".json.tmp");
        if (image.exists() || sidecar.exists()) throw new IOException("截图编号重复");
        try {
            try (OutputStream out = new FileOutputStream(imageTemp)) { out.write(bytes); }
            JSONObject info = new JSONObject();
            for (String key : metadata.keySet()) {
                Object value = metadata.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean) info.put(key, value);
            }
            try (OutputStream out = new FileOutputStream(jsonTemp)) {
                out.write(info.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!jsonTemp.renameTo(sidecar) || !imageTemp.renameTo(image)) throw new IOException("提交截图失败");
        } catch (IOException | org.json.JSONException error) {
            imageTemp.delete(); jsonTemp.delete(); image.delete(); sidecar.delete();
            throw new IOException("保存截图失败", error);
        }
    }
    public static void exportZip(Context c, OutputStream output) throws IOException {
        File snapshot = File.createTempFile("navbar-export-", ".zip", c.getCacheDir());
        try {
            synchronized (DemoStore.class) {
                try (OutputStream local = new FileOutputStream(snapshot)) { writeZip(c, local); }
            }
            // Slow document/cloud destinations must not block Provider sampling or lease renewal.
            try (InputStream input = new FileInputStream(snapshot)) { input.transferTo(output); }
        } finally { snapshot.delete(); }
    }
    private static void writeZip(Context c, OutputStream output) throws IOException {
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output, StandardCharsets.UTF_8);
        zip.putNextEntry(new java.util.zip.ZipEntry("observations.ndjson"));
        export(c, zip); zip.closeEntry();
        File[] images = file(c, "pending-images").listFiles();
        if (images == null && file(c, "pending-images").exists()) throw new IOException("无法读取待定截图目录");
        if (images != null) {
            java.util.Arrays.sort(images, java.util.Comparator.comparing(File::getName));
            for (File image : images) {
                if (!image.isFile() || !image.getName().matches("[a-f0-9-]{36}\\.(jpg|png|json)")) continue;
                String stem = image.getName().substring(0, 36);
                boolean picture = new File(image.getParentFile(), stem + ".jpg").isFile() || new File(image.getParentFile(), stem + ".png").isFile();
                if (!picture || !new File(image.getParentFile(), stem + ".json").isFile())
                    throw new IOException("待定截图与样本记录不完整，请保留本地数据排查");
                zip.putNextEntry(new java.util.zip.ZipEntry("pending/" + image.getName()));
                try (InputStream input = new FileInputStream(image)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
        zip.finish(); zip.flush();
    }
    static synchronized void clear(Context c) throws IOException {
        File[] images = file(c, "pending-images").listFiles();
        if (images == null && file(c, "pending-images").exists()) throw new IOException("无法读取待定截图目录");
        if (images != null) for (File image : images) {
            if (!image.delete()) throw new IOException("无法清空待定截图");
        }
        for (String name : new String[]{"observations.previous.jsonl", "observations.jsonl"}) {
            File target = file(c, name);
            if (target.exists() && !target.delete()) throw new IOException("无法清空日志");
        }
    }
}
