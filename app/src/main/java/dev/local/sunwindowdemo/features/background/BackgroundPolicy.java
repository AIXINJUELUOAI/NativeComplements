package dev.local.sunwindowdemo.features.background;

import dev.local.sunwindowdemo.BuildConfig;
import java.util.Map;

public final class BackgroundPolicy {
    public enum Mode { SYSTEM, DEFAULT, STRICT }
    public enum Action { ALLOW, FREEZE, RECLAIM, TRIM }
    public record AppRule(Mode mode, Action batch, Map<String, Action> actions,
                          boolean protectPlayback, boolean keepActive) {
        public AppRule { actions = Map.copyOf(actions); }

        public boolean playbackProtected() { return mode != Mode.STRICT || protectPlayback; }
    }

    public record Settings(boolean enabled, Map<String, AppRule> rules) {
        public Settings { rules = Map.copyOf(rules); }

        public AppRule rule(String pkg) {
            return rules.getOrDefault(pkg, defaultPlaybackProtection(pkg) ? STANDARD : DOUYIN_TEST);
        }
    }

    private static final String QQ = "com.tencent.mobileqq";
    private static final String WECHAT = "com.tencent.mm";
    private static final String DOUYIN = "com.ss.android.ugc.aweme";
    private static final AppRule STANDARD = new AppRule(Mode.STRICT, null, Map.of(), true, false);
    private static final AppRule DOUYIN_TEST = new AppRule(Mode.STRICT, null, Map.of(), false, false);

    private BackgroundPolicy() {}

    public static boolean supportsPlaybackOverride(String pkg) { return DOUYIN.equals(pkg); }

    static boolean defaultPlaybackProtection(String pkg) { return !BuildConfig.DEBUG || !supportsPlaybackOverride(pkg); }

    static boolean messageProcess(String pkg, String process, String main) {
        return (QQ.equals(pkg) || WECHAT.equals(pkg)) && (process.equals(main)
            || process.equals(QQ.equals(pkg) ? QQ + ":MSF" : WECHAT + ":push"));
    }

    static boolean canBatch(Action action) { return action == null || action == Action.ALLOW || action == Action.FREEZE; }

    public static Action action(AppRule rule, String pkg, String process, String main) {
        Action override = rule.actions().get(process);
        if (override != null && (canBatch(override) || !process.equals(main))) return override;
        if (rule.batch() != null && canBatch(rule.batch())) return rule.batch();
        if (rule.keepActive() && process.equals(main)) return Action.ALLOW;
        if (rule.mode() == Mode.STRICT) return Action.FREEZE;
        if (rule.mode() == Mode.SYSTEM) return null;
        return process.equals(main) || messageProcess(pkg, process, main) ? Action.ALLOW : Action.FREEZE;
    }
}
