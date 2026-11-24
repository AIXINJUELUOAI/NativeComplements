package dev.local.sunwindowdemo;

/** Per-window checkpoint for the white-bar gesture and its outstanding form animation. */
final class ResizeNavigation {
    private long gesture,blockedThrough;
    private Geometry.Placement before;
    private boolean navigating;

    boolean accepts(long id){
        return !navigating&&id>blockedThrough&&id>=gesture;
    }

    boolean navigating(){return navigating;}

    void track(long id,Geometry.Placement placement){
        if(id!=gesture){gesture=id;before=placement;}
    }

    Geometry.Placement cancel(long id){
        blockedThrough=Math.max(blockedThrough,id);
        if(id!=gesture)return null;
        Geometry.Placement restore=before;
        complete();
        return restore;
    }

    void complete(){
        blockedThrough=Math.max(blockedThrough,gesture);
        gesture=0;
        before=null;
    }

    Geometry.Placement start(){
        navigating=true;
        return cancel(gesture);
    }

    void finish(long now){
        navigating=false;
        blockedThrough=Math.max(blockedThrough,now);
    }
}
