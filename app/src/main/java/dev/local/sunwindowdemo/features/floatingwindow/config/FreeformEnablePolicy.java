package dev.local.sunwindowdemo;

/** Manager-side enable decision. No Android objects, settings writes, or diagnostic-text parsing. */
public final class FreeformEnablePolicy {
    private FreeformEnablePolicy() {}
    public enum Decision { ALLOWED, SETTING_REQUIRED, SETTING_UNKNOWN, BACKEND_UNKNOWN, PREFLIGHT_FAILED, RUNTIME_UNAVAILABLE, OWNERSHIP_CONFLICT }
    public record Capability(boolean known, boolean multiWindow, boolean freeform, boolean preflightReady, boolean ownershipConflict) {}
    public static Decision evaluate(Integer globalSetting, Capability capability) {
        if (globalSetting == null) return Decision.SETTING_UNKNOWN;
        if (globalSetting != 1) return Decision.SETTING_REQUIRED;
        if (capability == null) return Decision.BACKEND_UNKNOWN;
        if (capability.ownershipConflict()) return Decision.OWNERSHIP_CONFLICT;
        if (!capability.known()) return Decision.BACKEND_UNKNOWN;
        if (!capability.preflightReady()) return Decision.PREFLIGHT_FAILED;
        if (!capability.multiWindow() || !capability.freeform()) return Decision.RUNTIME_UNAVAILABLE;
        return Decision.ALLOWED;
    }
    public static boolean matchesStatus(int senderUid, int protocol, String event, String expected, String received,
                                        long requestedAt, long now, long timeout) {
        return senderUid == 1000 && protocol == 16 && "STATUS".equals(event) && expected != null
                && expected.equals(received) && now >= requestedAt && now - requestedAt <= timeout;
    }
}
