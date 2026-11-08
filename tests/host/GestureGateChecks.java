package dev.local.sunwindowdemo;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GestureGateChecks {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static final int[] RECTS={0,2560,110,2670,1090,2560,1200,2670};
    static boolean event(GestureGate g,int a,float x,float y,long down){return g.event(a,x,y,true,0,9,down);}
    public static void main(String[] args){
        GestureGate g=new GestureGate();
        check(!event(g,0,20,2620,1),"default navigation must remain enabled");
        AtomicBoolean alive=new AtomicBoolean(true);g.update(1200,2670,RECTS,alive::get);
        check(!event(g,0,600,2650,2),"center DOWN must pass");
        check(!event(g,2,20,2620,2),"entering corner mid-stream must pass");
        check(event(g,0,20,2620,3),"left DOWN");
        check(event(g,2,400,2200,3),"full owned stream outside rectangle");
        g.disable();check(event(g,1,400,2200,3),"disabled state must not send orphan UP");
        check(!event(g,0,20,2620,4),"disabled next DOWN must pass");
        g.update(1200,2670,RECTS,alive::get);
        check(event(g,0,1150,2620,5),"right DOWN");
        check(event(g,3,1150,2620,5),"cancel retained");
        check(!event(g,2,1150,2600,5),"cancel clears ownership");
        check(!g.event(0,20,2620,false,0,9,6),"mouse must pass");
        check(!g.event(0,20,2620,true,1,9,7),"other display must pass");
        check(event(g,0,20,2620,8),"new owned stream");
        check(!g.event(0,20,2620,true,1,9,99),"other display DOWN must pass");
        check(!g.event(2,20,2620,true,0,10,8),"other device must pass");
        check(!event(g,2,20,2620,7),"different stream must pass");
        check(event(g,1,200,2400,8),"matching release");
        alive.set(false);check(!event(g,0,20,2620,9),"dead service lease must pass");alive.set(true);
        check(event(g,0,20,2620,10),"revived lease");
        check(!event(g,0,600,2620,11),"new DOWN clears lost previous stream");
        g.update(1200,2670,new int[]{0,0,1200,2670},()->true);
        check(!event(g,0,20,2620,12),"broad region must be rejected");
        g.update(1200,2670,new int[]{0,2500,100,2600},()->true);
        check(!event(g,0,20,2550,13),"non-bottom region must be rejected");
        int[] copy=RECTS.clone();g.update(1200,2670,copy,()->true);copy[2]=1200;
        check(!event(g,0,600,2650,14),"state must defensively copy rectangles");
        check(!event(g,0,110,2620,15),"right edge is exclusive");
        check(!event(g,0,20,2670,16),"bottom edge is exclusive");
        int[] handle={450,2200,750,2400};
        check(g.update(1200,2670,RECTS,handle,()->true),"corner and handle accepted");
        check(event(g,0,600,2300,20),"handle DOWN protected from launcher");
        check(event(g,2,600,1600,20),"handle drag retains ownership");
        check(event(g,1,600,1600,20),"handle release retained");
        check(!event(g,0,600,2650,21),"central navigation remains outside handle");
        check(g.update(1200,2670,new int[0],handle,()->true),"handle works with corners disabled");
        handle[0]=0;check(!event(g,0,100,2300,22),"handle defensively copied");
        check(!g.update(1200,2670,new int[0],new int[]{0,0,1200,2670},()->true),"full display handle rejected");
        check(!event(g,0,600,2300,23),"invalid handle clears next ownership");
        System.out.println("PASS: corner/handle stream ownership, central navigation, cancellation, device/display isolation, bounds and defensive copies");
    }
}
