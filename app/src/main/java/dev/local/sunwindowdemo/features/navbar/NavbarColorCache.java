package dev.local.sunwindowdemo.features.navbar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Last confirmed sampled color per Activity page, so a page switch can show the expected color
 * before the first GPU readback finishes. Sampling still corrects the value afterwards.
 */
final class NavbarColorCache {
    static final int CAPACITY = 64;
    private final LinkedHashMap<String, Integer> colors = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) { return size() > CAPACITY; }
    };

    /** Returns null when the window does not belong to an Activity. */
    static String key(String component, int mode, int navHeight, boolean night, boolean landscape) {
        if (component == null || navHeight <= 0) return null;
        return component + "|" + mode + "|" + navHeight + "|" + (night ? 'n' : 'd') + "|" + (landscape ? 'l' : 'p');
    }

    synchronized Integer get(String key) { return key == null ? null : colors.get(key); }

    synchronized void put(String key, int color) {
        if (key == null || (color >>> 24) != 255) return;
        colors.put(key, color);
    }

    synchronized void clear() { colors.clear(); }

    synchronized int size() { return colors.size(); }
}
