package dev.local.sunwindowdemo.features.alive;

/** Scene target only; AMBIENT does not assert that a wallpaper surface is visible in AOD. */
public final class AlivePhase {
    public static final int AMBIENT=0, LOCK=1, HOME=2;
    private AlivePhase() {}
    /** Always derive from current platform facts; commands never leave an unlocked phase latched. */
    public static int phase(boolean interactive,boolean ambient,boolean locked){
        return !interactive||ambient?AMBIENT:locked?LOCK:HOME;
    }
}
