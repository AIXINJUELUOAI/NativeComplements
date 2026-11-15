package dev.local.sunwindowdemo.features.alive;

/** Scene target only; AMBIENT does not assert that a wallpaper surface is visible in AOD. */
public final class AlivePhase {
    public static final int AMBIENT=0, LOCK=1, HOME=2;
    public enum DisplayState { ON, DOZE, DOZE_SUSPEND, OFF, UNKNOWN }
    // Longest existing collapse is the 1500ms glass track; allow bounded dispatch/decode headroom.
    public static final long SLEEP_WINDOW_MS=1800;
    public static final class SleepWindow {
        private long deadline;
        private boolean signalled;
        public void begin(long now){if(!signalled){signalled=true;deadline=now+SLEEP_WINDOW_MS;}}
        public boolean active(long now){return signalled&&now<deadline;}
        public long remaining(long now){return active(now)?deadline-now:0;}
        /** Stop this sleep episode without allowing duplicate sleep signals to restart it. */
        public void end(){signalled=true;deadline=0;}
        public void wake(){signalled=false;deadline=0;}
    }
    private AlivePhase() {}
    /** Ambient drawing is restricted to our ambient service and a visible, actually dozing engine. */
    public static boolean drawable(boolean ownAmbient,boolean visible,boolean interactive,boolean ambient,DisplayState display){
        return drawable(ownAmbient,visible,interactive,ambient,display,false);
    }
    public static boolean drawable(boolean ownAmbient,boolean visible,boolean interactive,boolean ambient,DisplayState display,boolean sleeping){
        if(!visible)return false;
        return display==DisplayState.ON?interactive||ownAmbient&&sleeping:
            (display==DisplayState.DOZE||display==DisplayState.DOZE_SUSPEND)&&ownAmbient&&ambient;
    }
    /** Always derive from current platform facts; commands never leave an unlocked phase latched. */
    public static int phase(boolean interactive,boolean ambient,boolean locked){
        return !interactive||ambient?AMBIENT:locked?LOCK:HOME;
    }
}
