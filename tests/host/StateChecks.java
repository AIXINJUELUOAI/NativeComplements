package dev.local.sunwindowdemo;
public final class StateChecks {
    static void eq(String expected,String got){if(!expected.equals(got))throw new AssertionError(expected+" != "+got);}
    public static void main(String[] args){
        eq("normal",GestureMath.release(.75f,.75f,false,false));
        eq("mini",GestureMath.release(.59f,.75f,false,false));
        eq("full",GestureMath.release(.87f,.75f,false,false));
        eq("normal",GestureMath.release(.51f,.51f,false,true));
        eq("mini",GestureMath.release(.51f*.65f/.9f,.51f,false,true));
        eq("full",GestureMath.release(.51f*1.01f/.9f,.51f,false,true));
        eq("normal",GestureMath.release(.68f,.68f,true,true));
        eq("mini",GestureMath.release(.59f,.68f,true,true));
        eq("full",GestureMath.release(.87f,.68f,true,true));
        eq("mini",GestureMath.release(.5f*.65f/.9f,.5f,true,false));
        if(GestureMath.dragScale(.75f,-100,2133)>=.75f)throw new AssertionError("up must shrink");
        if(GestureMath.dragScale(.75f,100,2133)<=.75f)throw new AssertionError("down must enlarge");
        for(boolean app:new boolean[]{false,true})for(boolean display:new boolean[]{false,true}){
            float normal=app==display?(app?.68f:.75f):.50625f;
            float max=(app==display?.96f:1.1f)*normal/(app==display?(app?.68f:.75f):.9f);
            if(Math.abs(GestureMath.clampScale(.001f,normal,app,display)-.28f)>.0001f)throw new AssertionError("minimum lost");
            if(Math.abs(GestureMath.clampScale(50,normal,app,display)-max)>.0001f)throw new AssertionError("maximum lost");
            eq("mini",GestureMath.release(GestureMath.clampScale(.001f,normal,app,display),normal,app,display));
            eq("full",GestureMath.release(max,normal,app,display));
        }
        if(GestureMath.clampScale(.001f,.2f,false,false)!=.2f)throw new AssertionError("small safe-area fit");
        // Landscape fit can be .98: the old absolute 1.1 cap never reached full.
        for(boolean app:new boolean[]{false,true}){
            Geometry.Placement p=Geometry.place(1200,app,0,0,2670,1200,12);
            float right=GestureMath.clampScale(GestureMath.dragScale(p.scale(),300,p.width()),p.scale(),app,true);
            float left=GestureMath.clampScale(GestureMath.dragScale(p.scale(),-400,p.width()),p.scale(),app,true);
            eq("full",GestureMath.release(right,p.scale(),app,true));
            eq("mini",GestureMath.release(left,p.scale(),app,true));
        }
        boolean rejected=false;try{GestureMath.release(Float.NaN,.75f,false,false);}catch(IllegalArgumentException e){rejected=true;}
        if(!rejected)throw new AssertionError("reject NaN");
        System.out.println("PASS state thresholds: portrait/landscape/reversed orientation, drag directions, invalid scale");
    }
}
