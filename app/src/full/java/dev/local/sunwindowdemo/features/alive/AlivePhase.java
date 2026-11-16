package dev.local.sunwindowdemo.features.alive;

/** Scene target only; AMBIENT does not assert that a wallpaper surface is visible in AOD. */
public final class AlivePhase {
    public static final int AMBIENT=0, LOCK=1, HOME=2;
    public enum DisplayState { ON, DOZE, DOZE_SUSPEND, OFF, UNKNOWN }
    // Wait for native visibility, not a guessed screen-off delay. No hidden draw or init.
    public static final long SLEEP_WAIT_MS=6000,SLEEP_WINDOW_MS=1800;
    public static final class SleepWindow {
        private long deadline,hardDeadline,hiddenAt=-1;
        private boolean signalled,presented,started;
        public void begin(long now){if(!signalled){signalled=true;deadline=now+SLEEP_WAIT_MS;hardDeadline=deadline+SLEEP_WINDOW_MS;}}
        /** Start the finite collapse once the platform actually exposes our Engine. */
        public boolean present(long now){
            if(!active(now)||presented)return false;
            presented=true;return true;
        }
        /** The GL worker acknowledges the first successfully presented frame before animation starts. */
        public boolean start(long now){
            if(!active(now)||!presented||started)return false;
            started=true;deadline=now+SLEEP_WINDOW_MS;return true;
        }
        public void visibility(boolean visible,long now){
            if(!started||!active(now))return;
            if(!visible&&hiddenAt<0){hiddenAt=now;return;}
            if(visible&&hiddenAt>=0){deadline=Math.min(hardDeadline,deadline+now-hiddenAt);hiddenAt=-1;}
        }
        public boolean presented(){return presented;}
        public boolean active(long now){return signalled&&now<(hiddenAt>=0?hardDeadline:deadline);}
        public long remaining(long now){return active(now)?(hiddenAt>=0?hardDeadline:deadline)-now:0;}
        /** Stop this sleep episode without allowing duplicate sleep signals to restart it. */
        public void end(){signalled=true;deadline=hardDeadline=0;hiddenAt=-1;}
        public void wake(){signalled=false;presented=started=false;deadline=hardDeadline=0;hiddenAt=-1;}
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
