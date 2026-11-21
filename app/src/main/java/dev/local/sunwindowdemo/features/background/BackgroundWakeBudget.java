package dev.local.sunwindowdemo.features.background;

/** One wall-clock budget for the union of an app's effective partial wake locks. */
final class BackgroundWakeBudget {
    final BackgroundStore.WakeLockRule rule;
    final long foregroundChange;
    private final long allowance;
    private final long period;
    private long windowStart;
    private long last;
    private long spent;
    private boolean holding;

    BackgroundWakeBudget(BackgroundStore.WakeLockRule rule, long foregroundChange, long now) {
        this.rule = rule;
        this.foregroundChange = foregroundChange;
        allowance = rule.budgetSeconds() * 1000L;
        period = rule.periodSeconds() * 1000L;
        windowStart = now + rule.delaySeconds() * 1000L;
        last = now;
    }

    boolean exhausted(long now) {
        account(now);
        return spent >= allowance;
    }

    void holding(boolean active, long now) {
        account(now);
        holding = active;
    }

    long deadline(long now, boolean hasLocks) {
        if (!hasLocks) return Long.MAX_VALUE;
        if (spent >= allowance) return windowStart + period;
        if (holding) return Math.min(windowStart + period, Math.max(now, windowStart) + allowance - spent);
        return Long.MAX_VALUE;
    }

    private void account(long now) {
        if (now >= windowStart + period) {
            windowStart += (now - windowStart) / period * period;
            spent = 0;
        }
        if (holding) spent = Math.min(allowance, spent + Math.max(0, now - Math.max(last, windowStart)));
        last = now;
    }
}
