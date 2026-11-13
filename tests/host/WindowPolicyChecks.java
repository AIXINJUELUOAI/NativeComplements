package dev.local.sunwindowdemo;

public final class WindowPolicyChecks {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        require(!WindowPolicy.entryBusy(false,0));
        require(WindowPolicy.entryBusy(true,0));
        for(int rotation:new int[]{-3,-1,1,2,3})require(WindowPolicy.entryBusy(false,rotation));
        require(WindowPolicy.morphEntry(false,true,true,1));
        require(!WindowPolicy.morphEntry(true,true,true,1));
        require(!WindowPolicy.morphEntry(false,false,true,1));
        require(!WindowPolicy.morphEntry(false,true,false,1));
        require(!WindowPolicy.morphEntry(false,true,true,5));
        require(WindowPolicy.failureMessage("BLOCKED：IllegalStateException: 等待当前任务转场结束后再试\n\nmultiWindow=true\nfreeform=true").equals("系统转场尚未结束，请稍后重试"));
        require(WindowPolicy.failureMessage("GESTURE_FAILED：默认窗已被占用").equals("请先关闭当前默认小窗"));
        require(WindowPolicy.failureMessage("BLOCKED：先解锁屏幕").equals("请先解锁屏幕"));
        require(!WindowPolicy.failureMessage(null).contains("Exception"));
        for(float scale:new float[]{.18f,.28f,.6f,.75f,1f})for(float origin:new float[]{0,90,350})for(float logical:new float[]{0,42,1200}){
            float physical=origin+logical*scale;
            require(Math.abs(WindowPolicy.logicalRaw(physical,origin,scale)-logical)<.001f);
            // Moving the window must not turn a stationary finger in app coordinates into a drag.
            require(Math.abs(WindowPolicy.logicalRaw(physical+80,origin+80,scale)-logical)<.001f);
        }
        for(int landscape:new int[]{0,6,8,11})require(WindowPolicy.landscape(landscape,false));
        for(int portrait:new int[]{1,7,9,12})require(!WindowPolicy.landscape(portrait,true));
        for(int unspecified:new int[]{-1,2,4,5,10,14}){require(WindowPolicy.landscape(unspecified,true));require(!WindowPolicy.landscape(unspecified,false));}
        for(int horizontal:new int[]{0,6,8,11}){
            require(WindowPolicy.initialLandscape(horizontal,horizontal));
            require(!WindowPolicy.initialLandscape(horizontal,-1)); // flexible launcher, fixed video page
            require(!WindowPolicy.initialLandscape(-1,horizontal));
        }
        for(int flexible:new int[]{-1,2,4,5,10,14})require(!WindowPolicy.initialLandscape(flexible,flexible));
        require(!WindowPolicy.initialLandscape(1,1));
        require(WindowPolicy.currentLandscape(2,1,false));
        require(!WindowPolicy.currentLandscape(1,0,true));
        require(WindowPolicy.currentLandscape(0,0,false));
        require(!WindowPolicy.currentLandscape(0,1,true));
        require(WindowPolicy.clientMode("com.tencent.mm")==5);
        require(WindowPolicy.clientMode("tv.danmaku.bili")==1);
        require(WindowPolicy.clientMode("com.example.media")==1);
        require(WindowPolicy.clientMode(null)==1);
        require(WindowPolicy.steadyMiniTransition(true,false,6,true,true,false,false));
        for(int blocker=0;blocker<7;blocker++)require(!WindowPolicy.steadyMiniTransition(
            blocker!=0,blocker==1,blocker==2?1:6,blocker!=3,blocker!=4,blocker==5,blocker==6));
        require(WindowPolicy.mayRestoreNormalFocus(1,false,1));
        require(!WindowPolicy.mayRestoreNormalFocus(2011,false,1)); // IME
        require(!WindowPolicy.mayRestoreNormalFocus(2040,false,1)); // notification shade
        require(!WindowPolicy.mayRestoreNormalFocus(1,true,1)); // modal permission/dialog
        require(!WindowPolicy.mayRestoreNormalFocus(1,false,2)); // PiP
        require(!WindowPolicy.mayRestoreNormalFocus(1,false,5)); // other floating task
        Object owned=new Object();
        require(WindowPolicy.sameLiveTask(owned,owned,10,10,0,0));
        require(!WindowPolicy.sameLiveTask(new Object(),owned,10,10,0,0));
        require(!WindowPolicy.sameLiveTask(owned,owned,11,10,0,0));
        require(!WindowPolicy.sameLiveTask(owned,owned,10,10,10,0));
        require(!WindowPolicy.sameLiveTask(null,null,10,10,0,0));
        // A focused floating window covering a fullscreen app: starting that exact app task stays native.
        Object floating=new Object(),covered=new Object(),other=new Object(),display=new Object();
        require(WindowPolicy.coveredFullscreen(covered,floating,covered,display,display));
        require(!WindowPolicy.coveredFullscreen(covered,floating,other,display,display)); // a different task may still open linked
        require(!WindowPolicy.coveredFullscreen(covered,covered,covered,display,display)); // focused fullscreen keeps the exact-package rule
        require(!WindowPolicy.coveredFullscreen(null,floating,covered,display,display)); // Home or nothing beneath
        require(!WindowPolicy.coveredFullscreen(covered,floating,covered,display,new Object()));
        require(!WindowPolicy.coveredFullscreen(covered,floating,covered,null,null));
        // Background touch gate behind a normal window: entry (capped at max), then grace, never without a shown normal window.
        require(WindowPolicy.backgroundTouchGuard(true,true,false,0,0,5000,3000)); // first input update before any frame
        require(WindowPolicy.backgroundTouchGuard(true,true,true,1000,0,3999,3000));
        require(!WindowPolicy.backgroundTouchGuard(true,true,true,1000,0,4000,3000)); // stuck entry fails open
        require(WindowPolicy.backgroundTouchGuard(true,false,false,0,1250,1249,3000)); // grace while overlays reappear
        require(!WindowPolicy.backgroundTouchGuard(true,false,false,0,1250,1250,3000));
        require(!WindowPolicy.backgroundTouchGuard(false,true,true,1000,9999,1001,3000)); // mini/closing/parked: native touch
        // A replacement receives a fresh cap; repeated frames cannot extend the same owner's cap.
        Object guardA=new Object(),guardB=new Object();
        require(WindowPolicy.startsTouchGuard(guardA,null,true,false));
        require(!WindowPolicy.startsTouchGuard(guardA,guardA,true,true));
        require(WindowPolicy.startsTouchGuard(guardB,guardA,true,true));
        require(!WindowPolicy.startsTouchGuard(guardB,guardA,false,true));
        require(WindowPolicy.startsTouchGuard(guardA,guardA,true,false));
        long guardSince=1000;
        if(WindowPolicy.startsTouchGuard(guardB,guardA,true,true))guardSince=4001;
        require(WindowPolicy.backgroundTouchGuard(true,true,true,guardSince,0,4001,3000));
        require(WindowPolicy.backgroundTouchGuard(true,true,true,guardSince,0,7000,3000));
        require(!WindowPolicy.backgroundTouchGuard(true,true,true,guardSince,0,7001,3000));
        require(WindowPolicy.sameWindowCaller(10229,10229,10229,"sample.app","sample.app"));
        require(WindowPolicy.sameWindowCaller(10229,-1,10229,"sample.app","sample.app"));
        require(!WindowPolicy.sameWindowCaller(10229,1000,10229,"sample.app","sample.app"));
        require(!WindowPolicy.sameWindowCaller(10230,10230,10229,"sample.app","sample.app"));
        require(!WindowPolicy.sameWindowCaller(10229,10229,10229,"other.app","sample.app"));
        require(!WindowPolicy.sameWindowCaller(10229,10229,10229,null,"sample.app"));
        require(!WindowPolicy.sameWindowCaller(1000,1000,1000,"android","android"));
        require(WindowPolicy.refreshRootLayout(true,true,false,false,false,"RESUMED"));
        require(WindowPolicy.refreshRootLayout(true,true,false,false,false,"STOPPED"));
        require(!WindowPolicy.refreshRootLayout(false,true,false,false,false,"RESUMED"));
        require(!WindowPolicy.refreshRootLayout(true,false,false,false,false,"RESUMED"));
        require(!WindowPolicy.refreshRootLayout(true,true,true,false,false,"RESUMED"));
        require(!WindowPolicy.refreshRootLayout(true,true,false,true,false,"RESUMED"));
        require(!WindowPolicy.refreshRootLayout(true,true,false,false,true,"RESUMED"));
        require(!WindowPolicy.refreshRootLayout(true,true,false,false,false,"PAUSING"));
        System.out.println("PASS: coordinates, orientation, task identity, caller inheritance and entry-layout eligibility");
    }
}
