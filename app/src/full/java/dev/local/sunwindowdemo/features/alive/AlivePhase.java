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
        public boolean begin(long now){
            if(signalled)return false;
            signalled=true;deadline=now+SLEEP_WAIT_MS;hardDeadline=deadline+SLEEP_WINDOW_MS;return true;
        }
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
        /** Animation completion is not permission to drop the already presented buffer before native DOZE. */
        public boolean holding(long now){return signalled&&started&&now<hardDeadline;}
        public long holdRemaining(long now){return holding(now)?hardDeadline-now:0;}
        /** Stop this sleep episode without allowing duplicate sleep signals to restart it. */
        public void end(){signalled=true;deadline=hardDeadline=0;hiddenAt=-1;}
        public void wake(){signalled=false;presented=started=false;deadline=hardDeadline=0;hiddenAt=-1;}
    }
    /** Resource-retention cap only, not a wake delay or animation duration. */
    public static final long WAKE_RETAIN_MS=2000;
    public static final class WakeWindow {
        private long deadline,epoch;
        private boolean eligible;
        public long epoch(){return epoch;}
        public void presented(long renderEpoch){if(renderEpoch==epoch)eligible=true;}
        /** Invalidate only wake retention, never the original sleep lifecycle. */
        public void invalidate(){clear();eligible=false;epoch++;}
        public boolean arm(long now,boolean ownAmbient,SleepWindow sleep){
            boolean allowed=eligible;eligible=false;
            if(deadline!=0||!allowed||!ownAmbient||!sleep.holding(now))return false;
            deadline=now+WAKE_RETAIN_MS;return true;
        }
        public boolean active(long now){return deadline!=0&&now<deadline;}
        public long remaining(long now){return active(now)?deadline-now:0;}
        public void clear(){deadline=0;}
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
    /** AOD off/unknown never selects AMBIENT just because a native ambient flag is late. */
    public static int phase(boolean ownAmbient,boolean interactive,boolean ambient,boolean locked,boolean sleeping){
        return ownAmbient&&(sleeping||!interactive||ambient)?AMBIENT:locked?LOCK:HOME;
    }
    public static boolean animate(boolean ownAmbient,int previous,int next){
        return ownAmbient||previous!=AMBIENT&&next!=AMBIENT;
    }
    /** Always derive from current platform facts; commands never leave an unlocked phase latched. */
    public static int phase(boolean interactive,boolean ambient,boolean locked){
        return !interactive||ambient?AMBIENT:locked?LOCK:HOME;
    }
}
