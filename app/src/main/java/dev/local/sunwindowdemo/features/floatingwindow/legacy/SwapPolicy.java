package dev.local.sunwindowdemo;

/** Deliberately supports one independent mini, never evicts another managed task. */
final class SwapPolicy {
    private SwapPolicy() {}
    interface Restore { boolean run() throws Exception; }
    interface Forget { void run() throws Exception; }
    /** No journal release on a pending or throwing restore. */
    static boolean restoreThenForget(Restore restore,Forget forget) throws Exception {
        if(!restore.run())return false;
        forget.run();return true;
    }
    static String rejection(boolean mini, boolean ready, boolean soleSession, boolean linked,
                            boolean managedTarget, boolean sameDisplay, boolean fullscreenApp) {
        if (!mini || !ready) return "请等待迷你窗稳定后再互换";
        if (!soleSession || linked) return "互换暂不支持其他小窗或关联任务，请先手动处理";
        if (managedTarget) return "当前全屏任务仍由小窗管理，不能互换";
        if (!sameDisplay || !fullscreenApp) return "当前任务不是同屏普通全屏应用，不能互换";
        return null;
    }
}
