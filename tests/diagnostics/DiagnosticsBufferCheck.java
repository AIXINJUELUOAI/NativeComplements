package dev.local.sunwindowdemo.features.diagnostics;

/** Standalone javac/java check, no Gradle or device needed. */
public final class DiagnosticsBufferCheck {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(DiagnosticsBuffer.valid("systemui", "hook_failed", "SecurityException"));
        for (String raw : new String[]{"hello", "123456789", "wifi_ssid", "java.lang.RuntimeException", "RuntimeException: secret", "a\nb"}) {
            check(!DiagnosticsBuffer.valid("systemui", raw, ""));
            check(!DiagnosticsBuffer.valid("systemui", "hook_failed", raw));
            check(!DiagnosticsBuffer.valid(raw, "hook_failed", ""));
        }
        DiagnosticsBuffer b = new DiagnosticsBuffer();
        check(!b.add(1000, "app", "process_loaded", ""));
        b.start(1000);
        check(!b.add(1001, "app", "raw content", ""));
        check(b.add(1002, "app", "process_loaded", ""));
        check(b.snapshot(1003).equals("2\tapp\tprocess_loaded\t\n"));
        check(!b.active(1000 + DiagnosticsBuffer.MAX_DURATION_MS));
        check(!b.snapshot(1000 + DiagnosticsBuffer.MAX_DURATION_MS).isEmpty());
        check(b.snapshot(1000 + DiagnosticsBuffer.MAX_DURATION_MS + DiagnosticsBuffer.RETENTION_MS).isEmpty());
        b.start(10);
        int count = 0;
        while (b.add(11, "systemui", "hook_failed", "SecurityException")) count++;
        check(count > 0 && b.size(12) <= DiagnosticsBuffer.MAX_BYTES && !b.active(12));
        b.start(20); check(b.size(21) == 0);
        check(b.add(22, "launcher", "enabled", ""));
        b.stop(23); check(!b.add(24, "launcher", "disabled", ""));
        b.clear(); check(b.snapshot(25).isEmpty() && !b.active(25));
        System.out.println("diagnostics policy, privacy, byte/time/retention checks passed");
    }
}
