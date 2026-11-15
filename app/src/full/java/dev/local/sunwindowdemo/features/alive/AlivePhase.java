package dev.local.sunwindowdemo.features.alive;

/** Scene target only; AMBIENT does not assert that a wallpaper surface is visible in AOD. */
public final class AlivePhase {
    public static final int AMBIENT=0, LOCK=1, HOME=2;
    public enum DisplayState { ON, DOZE, DOZE_SUSPEND, OFF, UNKNOWN }
    private AlivePhase() {}
    /** Ambient drawing is restricted to our ambient service and a visible, actually dozing engine. */
    public static boolean drawable(boolean ownAmbient,boolean visible,boolean interactive,boolean ambient,DisplayState display){
        if(!visible)return false;
        return display==DisplayState.ON?interactive:
            (display==DisplayState.DOZE||display==DisplayState.DOZE_SUSPEND)&&ownAmbient&&ambient;
    }
    /** Always derive from current platform facts; commands never leave an unlocked phase latched. */
    public static int phase(boolean interactive,boolean ambient,boolean locked){
        return !interactive||ambient?AMBIENT:locked?LOCK:HOME;
    }
}
