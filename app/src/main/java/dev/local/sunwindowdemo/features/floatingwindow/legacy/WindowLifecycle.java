package dev.local.sunwindowdemo;

/** Lifecycle decisions over sampled values; clocks, task identity, leases and WM effects stay native. */
final class WindowLifecycle {
    enum Tick { STOP, HIDDEN, VISIBLE }
    static Tick tick(boolean managed,boolean restoring,boolean recovering,boolean visible){
        return !managed||restoring||recovering?Tick.STOP:visible?Tick.VISIBLE:Tick.HIDDEN;
    }
    enum Pause {
        HIDDEN(1000), PRIORITY(250), SCREEN(500), LIVE(250);
        final long delay;
        Pause(long delay){this.delay=delay;}
    }
    static Pause pause(boolean priority,boolean screen){return priority?Pause.PRIORITY:screen?Pause.SCREEN:Pause.LIVE;}
    record Continuation(boolean installed,boolean available,boolean enabled,boolean visible,boolean restoring,
        boolean closing,boolean parked,boolean fullscreen,boolean priority,boolean edgeHidden,String form,int uid) {}
    /** This eligibility must succeed BEFORE reading the final native control lease. */
    static boolean continuation(Continuation f){
        return f.installed&&f.available&&f.enabled&&f.visible&&!f.restoring&&!f.closing&&!f.parked
            &&!f.fullscreen&&!f.priority&&f.edgeHidden&&"mini".equals(f.form)&&f.uid>=10000;
    }
    static int priority(boolean sameDisplay,boolean sameTask,int uid,int windowUid,boolean visible){
        return !visible||!sameDisplay||uid<0||windowUid<0||windowUid==uid?0:sameTask?1:2;
    }
    record KeyguardShell(boolean locked,boolean sameDisplay,int userId,int currentUserId,int systemUiUid,int ownerUid,
        boolean hasTask,boolean policyHost,boolean hidesOverlays,int type,int flags,int privateFlags,int inputFeatures) {}
    /** Only a sampled native priority candidate; policyHost means the display policy's exact shade WindowState. */
    static boolean keyguardShell(KeyguardShell f){
        // TYPE_NOTIFICATION_SHADE; privileged HIDE_NON_SYSTEM_OVERLAY_WINDOWS; reject secure/bouncer input.
        return f!=null&&f.locked&&f.sameDisplay&&f.userId>=0&&f.userId==f.currentUserId
            &&f.systemUiUid>=0&&f.ownerUid==f.systemUiUid&&f.ownerUid/100000==f.userId
            &&!f.hasTask&&f.policyHost&&f.hidesOverlays&&f.type==2040&&(f.privateFlags&0x80000)!=0
            &&(f.flags&0x2000)==0&&(f.inputFeatures&~2)==0;
    }
    /** Presentation/input keep their original priority; only hidden continuation uses this result. */
    static int continuationPriority(int priority,KeyguardShell shell){return priority==2&&keyguardShell(shell)?0:priority;}
    static boolean screenshotIntent(String action,String mime,String scheme,String source){
        return ("android.intent.action.EDIT".equals(action)||"android.intent.action.VIEW".equals(action))
            &&"image/png".equals(mime)&&"content".equals(scheme)&&"screenshot".equals(source);
    }
    /** SystemUI itself, or the system chooser (SYSTEM_UID 1000) relaying SystemUI's start through startActivityAsCaller. */
    static boolean screenshotCaller(int systemUiUid,int realUid,int launchedFromUid,boolean systemUiPackage){
        return systemUiUid>=0&&launchedFromUid==systemUiUid&&systemUiPackage&&(realUid==systemUiUid||realUid==1000);
    }
    static boolean raiseNormal(int normalIndex,int miniIndex){return normalIndex>=0&&miniIndex>normalIndex;}
    record Priority(boolean blocked,boolean hide) {
        boolean changed(boolean previousBlocked,boolean previousHide){return blocked!=previousBlocked||hide!=previousHide;}
        boolean focusable(boolean previousFocusable,boolean parked,String form){
            return blocked?!hide:previousFocusable&&!parked&&"normal".equals(form);
        }
    }
    static Priority priority(int priority){return new Priority(priority!=0,priority==2);}
    enum Input { ALLOW, BLOCK, STATE }
    static Input input(boolean managed,int priority){
        return !managed||priority==1?Input.ALLOW:priority!=0?Input.BLOCK:Input.STATE;
    }
    record Content(boolean parked,boolean restoring,boolean priority,boolean screen,boolean rotation,boolean closing,String form,boolean startMask) {}
    static boolean blocksContent(Content f){
        return f.parked||f.restoring||f.priority||f.screen||f.rotation||f.closing||"mini".equals(f.form)||f.startMask;
    }
    record Resume(boolean visible,boolean restoring,boolean closing,boolean fullscreen,boolean parked,boolean priority) {}
    /** Screen/lease/sleep checks deliberately follow this cheap value gate in native order. */
    static boolean resumeCandidate(Resume f){return f.visible&&!f.restoring&&!f.closing&&!f.fullscreen&&!f.parked&&!f.priority;}
    record Top(boolean present,boolean finishing,boolean visible,boolean attached,int visibility) {}
    static boolean retainTop(Top f){return f.present&&!f.finishing&&f.visible&&f.attached&&f.visibility==0;}
    static boolean resumeMini(boolean installed,boolean visible,String form,boolean transitioning,boolean backRequested,boolean pendingStart){
        return installed&&visible&&"mini".equals(form)&&!transitioning&&!backRequested&&!pendingStart;
    }
    static boolean protectsFromBackground(String form, boolean edgeHidden, boolean parked, boolean closing,
                                          boolean restoring, boolean restoreForeground, boolean presentationSuspended) {
        return !parked && !closing && (!restoring || restoreForeground)
            && (!presentationSuspended || edgeHidden || "mini".equals(form));
    }
    static boolean resumableState(String state){
        return "PAUSED".equals(state)||"STARTED".equals(state)||"STOPPED".equals(state)||"STOPPING".equals(state);
    }
}
