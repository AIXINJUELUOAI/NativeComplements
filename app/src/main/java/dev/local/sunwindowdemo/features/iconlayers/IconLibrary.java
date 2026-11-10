package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** One active library. Immutable generations make publication a single preference transaction. */
public final class IconLibrary {
    private static String cachedGeneration = "";
    private static ArrayList<String> cachedPackages = new ArrayList<>();
    private IconLibrary() {}
    static File root(Context c) { return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "icon-libraries"); }
    static boolean validGeneration(String value) { return value != null && value.matches("g_[a-f0-9]{32}"); }
    static File generation(Context c, String value) throws IOException {
        if (!validGeneration(value)) throw new IOException("图标库标识无效");
        return new File(root(c), value);
    }
    public static synchronized ArrayList<String> packages(Context c, String generation) {
        if (generation.isEmpty()) return new ArrayList<>();
        if (!generation.equals(cachedGeneration)) {
            ArrayList<String> result = new ArrayList<>();
            try {
                File index = new File(generation(c, generation), "index.json");
                if (index.length() > 3 * 1024 * 1024) return result;
                JSONArray json = new JSONArray(Files.readString(index.toPath()));
                if (json.length() > 10000) return result;
                for (int i = 0; i < json.length(); i++) {
                    String pkg = json.getString(i);
                    if (IconArchive.validPackage(pkg)) result.add(pkg);
                }
            } catch (Exception ignored) { return result; }
            cachedPackages = result; cachedGeneration = generation;
        }
        return new ArrayList<>(cachedPackages);
    }

    public static IconArchive prepare(Context c, Uri uri, boolean tree, AtomicBoolean cancel, IconArchive.Progress progress) throws IOException {
        File temp = new File(c.getCacheDir(), "icon-import-" + UUID.randomUUID());
        if (!temp.mkdirs()) throw new IOException("创建导入目录失败");
        try {
            File source = new File(temp, "source.zip");
            progress.update(tree ? "读取本地图标目录…" : "读取本地图标 ZIP…", 0, 0);
            if (tree) {
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(source))) {
                    copyTree(c.getContentResolver(), uri, DocumentsContract.getTreeDocumentId(uri), "", out, cancel, new long[3], 0, progress);
                }
            } else {
                try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(source)) {
                    if (in == null) throw new IOException("读取所选文件失败");
                    IconArchive.copy(in, out, IconArchive.MAX_ARCHIVE, cancel);
                }
            }
            progress.update("识别图标和主题…", 0, 0);
            return new IconArchive(temp, cancel);
        } catch (IOException | RuntimeException failure) { IconArchive.delete(temp); throw failure; }
    }

    private static void copyTree(ContentResolver resolver, Uri tree, String id, String prefix, ZipOutputStream out,
        AtomicBoolean cancel, long[] limits, int depth, IconArchive.Progress progress) throws IOException {
        if (depth > 12) throw new IOException("目录层级过深，请选择图标所在目录");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("读取目录失败");
            while (cursor.moveToNext()) {
                IconArchive.check(cancel);
                if (++limits[0] > IconArchive.MAX_ENTRIES) throw new IOException("目录条目过多");
                String childId = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (name == null || name.contains("/") || !IconArchive.safePath(name)) continue;
                String path = prefix + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copyTree(resolver, tree, childId, path + "/", out, cancel, limits, depth + 1, progress);
                } else if (name.equals("0.png") || name.equals("1.png")) {
                    out.putNextEntry(new ZipEntry(path));
                    try (InputStream in = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, childId))) {
                        if (in == null) throw new IOException("读取图像失败");
                        limits[1] += IconArchive.copy(in, out, IconArchive.MAX_IMAGE, cancel);
                        if (limits[1] > IconArchive.MAX_ARCHIVE) throw new IOException("目录超过导入大小限制");
                    }
                    out.closeEntry();
                    if (++limits[2] % 20 == 0) progress.update("已读取 " + limits[2] + " 张图像…", 0, 0);
                }
            }
        }
    }

    public static final class Result {
        public final int imported, skipped, failed;
        Result(int imported, int skipped, int failed) { this.imported = imported; this.skipped = skipped; this.failed = failed; }
        @Override public String toString() { return "导入 " + imported + " 个 · 跳过 " + skipped + " 个 · 失败 " + failed + " 个"; }
    }

    public static Result install(Context c, IconArchive archive, IconArchive.Source source, AtomicBoolean cancel,
        IconArchive.Progress progress) throws IOException {
        if (source.pairs.size() > 10000) throw new IOException("单个图标源超过 10000 个应用");
        String id = UUID.randomUUID().toString().replace("-", "");
        File pending = new File(root(c), "pending-" + id), destination = generation(c, "g_" + id);
        if (!pending.mkdirs()) throw new IOException("创建图标库失败");
        ArrayList<String> packages = new ArrayList<>();
        int skipped = 0, failed = 0, done = 0; long bytes = 0;
        try {
            for (IconArchive.Pair pair : source.pairs) {
                IconArchive.check(cancel);
                progress.update("校验并导入 " + pair.packageName, done++, source.pairs.size());
                if (!pair.complete()) { skipped++; continue; }
                File folder = new File(pending, pair.packageName);
                try {
                    byte[] bg = archive.image(pair, 0, cancel), fg = archive.image(pair, 1, cancel);
                    bytes += bg.length + fg.length;
                    if (bytes > IconArchive.MAX_ARCHIVE) throw new ImportLimitException();
                    validate(bg); validate(fg);
                    if (!folder.mkdirs()) throw new IOException("创建图标目录失败");
                    Files.write(new File(folder, "0.png").toPath(), bg);
                    Files.write(new File(folder, "1.png").toPath(), fg);
                    packages.add(pair.packageName);
                } catch (ImportLimitException fatal) { throw fatal; }
                catch (IOException invalid) { failed++; IconArchive.delete(folder); }
            }
            IconArchive.check(cancel);
            if (packages.isEmpty()) throw new IOException("没有可用的完整双层图标，原图标库已保留");
            Files.writeString(new File(pending, "index.json").toPath(), new JSONArray(packages).toString(), StandardCharsets.UTF_8);
            synchronized (IconLibrary.class) {
                IconArchive.check(cancel);
                if (!pending.renameTo(destination)) throw new IOException("发布图标库失败");
                var restore = restorePackages(c);
                if (!IconLayerStore.prefs(c).edit().putString("generation", destination.getName())
                    .putString("title", source.title).putInt("count", packages.size()).putBoolean("enabled", true)
                    .putStringSet("restore", restore)
                    .putStringSet("originals", new HashSet<>()).commit()) {
                    IconArchive.delete(destination); throw new IOException("保存图标设置失败");
                }
            }
            IconLayerStore.refresh(c);
            progress.update("导入完成", source.pairs.size(), source.pairs.size());
            return new Result(packages.size(), skipped, failed);
        } finally { IconArchive.delete(pending); }
    }
    private static class ImportLimitException extends IOException { ImportLimitException() { super("图像总量超过导入限制"); } }
    private static void validate(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes[0] != (byte)137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71)
            throw new IOException("图像不是 PNG");
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (opts.outWidth < 1 || opts.outHeight < 1 || opts.outWidth > 2048 || opts.outHeight > 2048)
            throw new IOException("图像尺寸应在 1–2048 像素以内");
        opts.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bitmap == null) throw new IOException("PNG 解码失败");
        bitmap.recycle();
    }
    /** Called only after the launcher acknowledges loading the current immutable generation. */
    static synchronized void acknowledge(Context c, String loaded) {
        if (!loaded.equals(IconLayerStore.prefs(c).getString("generation", ""))) return;
        IconLayerStore.prefs(c).edit().remove("restore").apply();
        File[] files = root(c).listFiles();
        if (files != null) for (File f : files)
            if (validGeneration(f.getName()) && !f.getName().equals(loaded)) IconArchive.delete(f);
    }
    public static synchronized void remove(Context c) {
        var restore = restorePackages(c);
        IconLayerStore.prefs(c).edit().remove("generation").remove("title").remove("count")
            .remove("originals").putStringSet("restore", restore).putBoolean("enabled", false).commit();
        // Old files stay readable until the launcher has restored its cached original icons.
        IconLayerStore.refresh(c);
    }
    private static Set<String> restorePackages(Context c) {
        var prefs = IconLayerStore.prefs(c);
        var restore = new HashSet<>(prefs.getStringSet("restore", Collections.emptySet()));
        restore.addAll(packages(c, prefs.getString("generation", "")));
        return restore;
    }
}
