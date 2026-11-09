package dev.local.sunwindowdemo;

public final class WindowPolicyChecks {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        for(float scale:new float[]{.18f,.28f,.6f,.75f,1f})for(float origin:new float[]{0,90,350})for(float logical:new float[]{0,42,1200}){
            float physical=origin+logical*scale;
            require(Math.abs(WindowPolicy.logicalRaw(physical,origin,scale)-logical)<.001f);
            // Moving the window must not turn a stationary finger in app coordinates into a drag.
            require(Math.abs(WindowPolicy.logicalRaw(physical+80,origin+80,scale)-logical)<.001f);
        }
        for(int landscape:new int[]{0,6,8,11})require(WindowPolicy.landscape(landscape,false));
        for(int portrait:new int[]{1,7,9,12})require(!WindowPolicy.landscape(portrait,true));
        for(int unspecified:new int[]{-1,2,4,5,10,14}){require(WindowPolicy.landscape(unspecified,true));require(!WindowPolicy.landscape(unspecified,false));}
        Object owned=new Object();
        require(WindowPolicy.sameLiveTask(owned,owned,10,10,0,0));
        require(!WindowPolicy.sameLiveTask(new Object(),owned,10,10,0,0));
        require(!WindowPolicy.sameLiveTask(owned,owned,11,10,0,0));
        require(!WindowPolicy.sameLiveTask(owned,owned,10,10,10,0));
        require(!WindowPolicy.sameLiveTask(null,null,10,10,0,0));
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
