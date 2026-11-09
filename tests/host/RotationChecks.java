package dev.local.sunwindowdemo;

public final class RotationChecks {
    private static void require(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        RotationGate g=new RotationGate();require(!g.active()&&g.alpha(0)==1);
        g.begin(100);int first=g.generation();require(g.active()&&!g.fading()&&g.alpha(100)==0);
        require(!g.sample(100,false,"portrait"));require(!g.sample(132,true,"portrait"));
        require(!g.sample(164,true,"landscape"));require(!g.sample(196,true,"landscape"));
        require(g.sample(228,true,"landscape"));require(!g.expired(3099)&&g.expired(3100));
        require(g.startFade(228)==first);require(g.alpha(228)==0&&g.alpha(303)==.5f&&g.alpha(378)==1);
        g.begin(304);require(g.generation()!=first&&g.alpha(304)==0&&!g.fading());
        require(!g.sample(304,true,"portrait"));require(!g.sample(368,false,"portrait"));
        require(!g.sample(400,true,"portrait"));require(g.sample(464,true,"portrait"));
        g.defer(5000);require(!g.expired(7999)&&g.expired(8000));
        require(!g.sample(5000,true,"portrait")&&g.sample(5064,true,"portrait"));
        g.clear();require(!g.active()&&g.alpha(9000)==1&&!g.sample(9000,true,"portrait"));
        System.out.println("PASS: rotation stability, interruption, timeout, lock deferral and 150ms fade");
    }
}
