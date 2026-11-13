package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads Android's DUMP_ENCODED geometry stream, without accessibility or application hooks. */
final class NavbarViewTree {
    private final DataInputStream input;
    private final Map<String, Short> names = new HashMap<>();
    private int entries;
    private NavbarViewTree(byte[] bytes) { input = new DataInputStream(new ByteArrayInputStream(bytes)); }

    static Rect bottomControl(byte[] bytes, int width, int height, int navHeight) throws IOException {
        if (bytes.length > 2_000_000) throw new IOException("view hierarchy too large");
        NavbarViewTree parser = new NavbarViewTree(bytes);
        List<Map<Short, Object>> maps = new ArrayList<>();
        while (parser.input.available() > 0) {
            Object value = parser.value(0);
            if (value instanceof Map<?, ?>) maps.add(cast(value));
        }
        if (maps.size() < 2) return null;
        Map<Short, Object> index = maps.get(maps.size() - 1);
        index.forEach((key, value) -> { if (value instanceof String) parser.names.put((String) value, key); });
        return parser.find(maps.get(0), 0, 0, width, height, navHeight, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<Short, Object> cast(Object value) { return (Map<Short, Object>) value; }

    private Object value(int depth) throws IOException {
        if (depth > 96 || ++entries > 100_000) throw new IOException("view hierarchy limit");
        return switch (input.readUnsignedByte()) {
            case 'M' -> {
                Map<Short, Object> map = new HashMap<>();
                while (true) {
                    if (input.readUnsignedByte() != 'S') throw new IOException("property key");
                    short key = input.readShort();
                    if (key == 0) break;
                    map.put(key, value(depth + 1));
                }
                yield map;
            }
            case 'Z' -> input.readBoolean();
            case 'B' -> input.readByte();
            case 'S' -> input.readShort();
            case 'I' -> input.readInt();
            case 'J' -> input.readLong();
            case 'F' -> input.readFloat();
            case 'D' -> input.readDouble();
            case 'R' -> {
                int length = input.readUnsignedShort();
                byte[] string = new byte[length];
                input.readFully(string);
                yield new String(string, StandardCharsets.UTF_8);
            }
            default -> throw new IOException("property type");
        };
    }

    private Object property(Map<Short, Object> node, String name) { return node.get(names.get(name)); }
    private int number(Map<Short, Object> node, String name, int fallback) {
        Object value = property(node, name);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private Rect find(Map<Short, Object> node, int parentX, int parentY, int width, int height, int nav, int depth) {
        if (depth > 64 || number(node, "misc:visibility", 0) != 0) return null;
        Object alpha = property(node, "drawing:alpha");
        if (alpha instanceof Number && ((Number) alpha).floatValue() <= 0) return null;
        String type = String.valueOf(property(node, "meta:__name__")).toLowerCase(Locale.ROOT);
        if (type.contains("scroll") || type.contains("recycler") || type.contains("viewpager") || type.contains("webview")) return null;
        int left = number(node, "layout:left", 0), top = number(node, "layout:top", 0);
        int x = parentX + left + number(node, "misc:transformation.translationX", 0);
        int y = parentY + top + number(node, "misc:transformation.translationY", 0);
        int right = x + number(node, "layout:right", left) - left;
        int bottom = y + number(node, "layout:bottom", top) - top;
        int children = Math.min(512, number(node, "meta:__childCount__", 0));
        Rect best = null;
        if (children >= 2 && right - x >= width * 0.8f && bottom - y >= Math.max(24, nav * 1.1f)
                && bottom - y <= height * 0.18f && Math.abs(bottom - (height - nav)) <= 4
                && x >= -4 && right <= width + 4) best = new Rect(x, y, right, bottom);
        int scrollX = number(node, "scrolling:scrollX", 0), scrollY = number(node, "scrolling:scrollY", 0);
        for (int index = 0; index < children; index++) {
            Object child = property(node, "meta:__child__" + index);
            if (child instanceof Map<?, ?>) {
                Rect candidate = find(cast(child), x - scrollX, y - scrollY, width, height, nav, depth + 1);
                if (candidate != null && (best == null || candidate.height() < best.height())) best = candidate;
            }
        }
        return best;
    }
}
