package dev.local.sunwindowdemo;

public final class SwapPolicyChecks {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        require(SwapPolicy.rejection(true,true,true,false,false,true,true)==null);
        for(int blocker=0;blocker<7;blocker++){
            String reason=SwapPolicy.rejection(blocker!=0,blocker!=1,blocker!=2,
                blocker==3,blocker==4,blocker!=5,blocker!=6);
            require(reason!=null&&!reason.isBlank());
        }
        // Slot placement is intentionally irrelevant: sole active and sole pinned mini share this policy.
        require(SwapPolicy.rejection(true,true,false,false,false,true,true).contains("其他小窗"));
        require(SwapPolicy.rejection(true,true,true,true,false,true,true).contains("关联任务"));
        require(SwapPolicy.rejection(true,true,true,false,true,true,true).contains("仍由小窗管理"));
        boolean[] journal={true};int[] forgotten={0};
        SwapPolicy.Forget forget=()->{journal[0]=false;forgotten[0]++;};
        for(int pending=0;pending<101;pending++)require(!SwapPolicy.restoreThenForget(()->false,forget));
        require(journal[0]&&forgotten[0]==0); // Pending recovery retains its journal indefinitely.
        try{SwapPolicy.restoreThenForget(()->{throw new IllegalStateException("injected restore failure");},forget);throw new AssertionError();}
        catch(IllegalStateException expected){require(journal[0]&&forgotten[0]==0);}
        require(SwapPolicy.restoreThenForget(()->true,forget));
        require(!journal[0]&&forgotten[0]==1);
    }
}
