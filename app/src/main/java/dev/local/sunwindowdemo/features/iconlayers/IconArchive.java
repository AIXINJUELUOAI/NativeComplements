package dev.local.sunwindowdemo.features.iconlayers;

import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Reads data only. Never extracts arbitrary paths or executes archive contents. */
public final class IconArchive implements AutoCloseable {
    static final long MAX_ARCHIVE = 512L * 1024 * 1024, MAX_IMAGE = 8L * 1024 * 1024;
    static final int MAX_ENTRIES = 60000;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    public interface Progress { void update(String message, int done, int total); }
    public static final class Pair {
        public final String packageName;
        final String[] paths = new String[2];
        boolean duplicate;
        Pair(String name) { packageName = name; }
        public boolean complete() { return !duplicate && paths[0] != null && paths[1] != null; }
    }
    public static final class Source {
        public final String key, title;
        public final List<Pair> pairs;
        Source(String key, List<Pair> pairs) {
            this.key = key; this.pairs = pairs;
            int theme = key.indexOf("themes/");
            title = theme >= 0 ? "主题 · " + key.substring(theme + 7).replaceAll("/icons/$", "")
                : key.isEmpty() ? "图标目录" : key.endsWith("/icons/") || key.equals("icons/") ? "基础图标 · " + key : key;
        }
        public int completeCount() { return (int) pairs.stream().filter(Pair::complete).count(); }
    }
    private final File directory;
    private ZipFile zip;
    public final List<Source> sources = new ArrayList<>();

    public IconArchive(File directory, AtomicBoolean cancel) throws IOException {
        this.directory = directory;
        try {
            zip = new ZipFile(new File(directory, "source.zip"));
            scan(cancel);
            // MIUI module/MTZ containers store the actual icon archive in a file named icons.
            if (sources.isEmpty()) {
                ZipEntry nested = null;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && (entry.getName().equals("icons") || entry.getName().endsWith("/icons"))) {
                        if (nested != null) throw new IOException("包含多个内层图标包，请先解压并选择其中一个");
                        nested = entry;
                    }
                }
                if (nested != null) {
                    File inner = new File(directory, "inner.zip");
                    try (InputStream in = zip.getInputStream(nested); OutputStream out = new FileOutputStream(inner)) {
                        copy(in, out, MAX_ARCHIVE, cancel);
                    }
                    zip.close(); zip = new ZipFile(inner); scan(cancel);
                }
            }
            if (sources.isEmpty()) throw new IOException("未找到按包名排列的 0.png 和 1.png 图标");
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    private void scan(AtomicBoolean cancel) throws IOException {
        Map<String, Map<String, Pair>> roots = new TreeMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries(); int count = 0;
        while (entries.hasMoreElements()) {
            check(cancel);
            if (++count > MAX_ENTRIES) throw new IOException("压缩包条目过多");
            ZipEntry e = entries.nextElement(); String name = e.getName();
            if (!safePath(name)) throw new IOException("压缩包包含异常路径");
            if (e.isDirectory()) continue;
            int layer = name.endsWith("/0.png") ? 0 : name.endsWith("/1.png") ? 1 : -1;
            if (layer < 0) continue;
            String parent = name.substring(0, name.length() - 6);
            int slash = parent.lastIndexOf('/');
            String pkg = parent.substring(slash + 1);
            if (!validPackage(pkg)) continue;
            String root = parent.substring(0, slash + 1);
            // Dynamic/fancy icon engines are deliberately outside this two-layer format.
            if (root.contains("layer_animating_icons/") || root.contains("fancy_icons/")) continue;
            Map<String, Pair> pairs = roots.computeIfAbsent(root, k -> new TreeMap<>());
            Pair pair = pairs.computeIfAbsent(pkg, Pair::new);
            if (pair.paths[layer] != null) pair.duplicate = true;
            pair.paths[layer] = name;
        }
        for (var root : roots.entrySet()) sources.add(new Source(root.getKey(), new ArrayList<>(root.getValue().values())));
    }

    byte[] image(Pair pair, int layer, AtomicBoolean cancel) throws IOException {
        ZipEntry e = zip.getEntry(pair.paths[layer]);
        if (e == null || e.getSize() > MAX_IMAGE) throw new IOException("图像超过大小限制");
        try (InputStream in = zip.getInputStream(e); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, MAX_IMAGE, cancel); return out.toByteArray();
        }
    }

    static long copy(InputStream in, OutputStream out, long limit, AtomicBoolean cancel) throws IOException {
        byte[] buffer = new byte[32768]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            check(cancel); total += n;
            if (total > limit) throw new IOException("文件超过导入大小限制");
            out.write(buffer, 0, n);
        }
        check(cancel); return total;
    }
    static boolean validPackage(String name) { return name != null && name.length() <= 240 && PACKAGE.matcher(name).matches(); }
    static boolean safePath(String path) {
        if (path.isEmpty() || path.length() > 1024 || path.startsWith("/") || path.contains("\\") || path.contains(":")) return false;
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".") || part.isEmpty()) return false;
        return true;
    }
    static void check(AtomicBoolean cancel) { if (cancel.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("已取消导入"); }
    @Override public void close() {
        try { if (zip != null) zip.close(); } catch (IOException ignored) {}
        delete(directory);
    }
    // All callers pass private, internally created staging directories; never a user-selected directory.
    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
