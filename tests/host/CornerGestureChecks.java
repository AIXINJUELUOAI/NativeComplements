package dev.local.sunwindowdemo;

public final class CornerGestureChecks {
    private static final CornerGesture.Area AREA=new CornerGesture.Area(1200,2670,110,50,500);
    private static void check(boolean v){if(!v)throw new AssertionError();}
    private static int event(CornerGesture g,int action,float x,float y,long elapsed){
        return g.event(action,x,y,true,1,0,9,100,100+elapsed,AREA);
    }
    public static void main(String[] args){
        CornerGesture g=new CornerGesture();
        check(event(g,0,40,2620,0)==CornerGesture.BEGIN);
        check(event(g,1,40,2620,30)==CornerGesture.END); // app keeps real DOWN/UP; no pilfer or replay
        check(event(g,0,40,2620,0)==CornerGesture.BEGIN);
        check(event(g,2,80,2580,550)==CornerGesture.TRACK); // preserve long press, including later drag
        check(event(g,1,80,2580,600)==CornerGesture.END);
        check(event(g,0,40,2620,0)==CornerGesture.BEGIN);
        check(event(g,2,43,2618,10)==CornerGesture.TRACK);
        check(event(g,2,90,2570,50)==CornerGesture.CLAIM); // the only point that calls pilferPointers
        check(event(g,2,110,2550,80)==CornerGesture.MOVE);
        check(event(g,1,110,2550,90)==CornerGesture.FINISH);
        check(event(g,2,110,2550,100)==CornerGesture.PASS);
        check(event(g,0,1160,2620,0)==CornerGesture.BEGIN);
        check(g.right());
        check(event(g,2,1110,2570,50)==CornerGesture.CLAIM);
        check(event(g,3,1110,2570,60)==CornerGesture.CANCEL);
        check(event(g,0,600,2620,0)==CornerGesture.PASS);
        check(event(g,2,40,2620,10)==CornerGesture.PASS); // entering corner mid-stream is not a candidate
        check(g.event(0,40,2620,true,1,1,9,100,100,AREA)==CornerGesture.PASS);
        check(g.event(0,40,2620,false,1,0,9,100,100,AREA)==CornerGesture.PASS);
        check(event(g,0,40,2620,0)==CornerGesture.BEGIN);
        check(g.event(5,40,2620,true,2,0,9,100,110,AREA)==CornerGesture.TRACK);
        check(event(g,2,110,2550,50)==CornerGesture.TRACK);
        check(event(g,1,110,2550,60)==CornerGesture.END); // multi-touch stays with app
        check(event(g,0,40,2620,0)==CornerGesture.BEGIN);
        check(g.event(2,90,2570,true,1,0,9,100,150,null)==CornerGesture.TRACK);
        check(event(g,1,90,2570,70)==CornerGesture.END); // disabled candidates never create orphan native events
        System.out.println("PASS: genuine click/long-press passthrough, diagonal ownership, cancel, multi-touch and display isolation");
    }
}
