package dev.local.sunwindowdemo.core.adaptation;

import java.lang.reflect.Method;

/** Attribution and feedback only: never use this record to grant or disable a feature. */
public record CommunityAdaptation(String id, String systemName, String maintainer,
                                  String authorUrl, String maintainerFeedbackUrl) {
    public static final String PROJECT_URL = "https://github.com/AIXINJUELUOAI/NativeComplements";
    public static final String PROJECT_ISSUES = PROJECT_URL + "/issues";

    // PR #4 author/head verified through GitHub's read-only API on 2026-11-17:
    // LFRon/Super-NativeComplements has_issues=false; no confirmed maintainer feedback URL.
    private static final CommunityAdaptation LINEAGE_24 = new CommunityAdaptation(
            "lineageos-24", "LineageOS 24.0", "LFRon", "https://github.com/LFRon", null);

    public String feedbackUrl() {
        return maintainerFeedbackUrl == null ? PROJECT_ISSUES : maintainerFeedbackUrl;
    }

    /** Stable record identity, deliberately unrelated to the app version. */
    public String seenKey() {
        return "seen." + id;
    }

    /** Null means unknown/no community record, not unsupported. Never infer a ROM from brand. */
    public static CommunityAdaptation current() {
        try {
            Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return match((String) get.invoke(null, "ro.lineage.build.version"),
                    (String) get.invoke(null, "ro.lineage.version"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    public static CommunityAdaptation match(String buildVersion, String version) {
        // vendor/lineage/config/version.mk publishes both properties. Require agreement;
        // an absent/blocked property or a different release must remain unknown.
        return "24.0".equals(buildVersion) && version != null
                && version.matches("24\\.0-[A-Za-z0-9][A-Za-z0-9._-]*") ? LINEAGE_24 : null;
    }
}
