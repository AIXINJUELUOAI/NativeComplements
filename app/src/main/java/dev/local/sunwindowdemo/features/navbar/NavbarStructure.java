package dev.local.sunwindowdemo.features.navbar;

import android.os.Bundle;
import android.os.IBinder;

/** Bounded, text-free app observation. A report belongs to one live window, never an Activity cache. */
final class NavbarStructure {
    static final long MAX_AGE_MS = 1500;
    final IBinder token;
    final long sampledAt;
    final String kind, node, reason, bounds;
    final int mode, nodes;
    final long nanos;

    private NavbarStructure(IBinder token, long sampledAt, String kind, String node, String reason,
                            String bounds, int mode, int nodes, long nanos) {
        this.token = token; this.sampledAt = sampledAt; this.kind = kind; this.node = node;
        this.reason = reason; this.bounds = bounds; this.mode = mode; this.nodes = nodes; this.nanos = nanos;
    }

    static NavbarStructure read(Bundle extras, long now) {
        if (extras == null) return null;
        IBinder token = extras.getBinder("window");
        long at = extras.getLong("sampledAt", -1), ns = extras.getLong("nanos", -1);
        int mode = extras.getInt("suggested", -1), nodes = extras.getInt("nodes", -1);
        if (token == null || at < 0 || now < at || now - at > MAX_AGE_MS || ns < 0 || ns > 1_000_000_000L
                || nodes < 0 || nodes > 512 || mode < NavbarStore.ORIGINAL || mode > NavbarStore.FLOATING) return null;
        String kind = extras.getString("container"), node = extras.getString("node"),
                reason = extras.getString("reason"), bounds = extras.getString("bounds");
        if (kind == null || node == null || reason == null || bounds == null
                || kind.length() > 40 || node.length() > 120 || reason.length() > 160 || bounds.length() > 80) return null;
        if (!java.util.Set.of("固定操作区", "滚动内容区", "未确定", "无结构信息", "应用自绘导航区").contains(kind)) return null;
        return new NavbarStructure(token, at, kind, node, reason, bounds, mode, nodes, ns);
    }

    boolean matches(IBinder window, long now) {
        return token.equals(window) && now >= sampledAt && now - sampledAt <= MAX_AGE_MS;
    }

    String summary() {
        return kind + (node.isEmpty() ? "" : " · " + node) + (bounds.isEmpty() ? "" : " · " + bounds)
                + "\n检查：" + String.format(java.util.Locale.ROOT, "%.2fms", nanos / 1e6) + " · " + nodes + " 节点"
                + (reason.isEmpty() ? "" : " · " + reason);
    }
}
