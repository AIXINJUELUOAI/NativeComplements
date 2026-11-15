package dev.local.sunwindowdemo.features.edgetouch;

import java.util.HashMap;
import java.util.Map;

/** Value-only admission fixed at each finger's DOWN, including fingers added to an existing stream. */
public final class EdgeInputPolicy {
    public enum Action { DOWN, POINTER_DOWN, POINTER_UP, UP, CANCEL, OTHER }
    private final Map<Long,Integer> suppressed=new HashMap<>();
    private int[] rectangles=new int[0];

    public synchronized void regions(int[] value){rectangles=value.clone();}
    public synchronized void clear(){rectangles=new int[0];suppressed.clear();}
    public synchronized boolean holding(){return !suppressed.isEmpty();}

    /** Eligible touchscreen facts only; the adapter passes other displays/sources through without entering. */
    public synchronized int accepted(long stream,Action action,int all,int changedPointer,
                                     boolean finger,float downX,float downY){
        int blocked=suppressed.getOrDefault(stream,0);
        if(action==Action.DOWN)blocked=0;
        if((action==Action.DOWN||action==Action.POINTER_DOWN)&&finger&&inside(downX,downY))blocked|=changedPointer;
        int accepted=all&~blocked;
        // The lifting finger is still suppressed in this event; release its bit only for following events.
        if(action==Action.POINTER_UP)blocked&=~changedPointer;
        if(action==Action.UP||action==Action.CANCEL||blocked==0)suppressed.remove(stream);
        else suppressed.put(stream,blocked);
        return accepted;
    }

    private boolean inside(float x,float y){
        for(int i=0;i<rectangles.length;i+=4)
            if(x>=rectangles[i]&&x<rectangles[i+2]&&y>=rectangles[i+1]&&y<rectangles[i+3])return true;
        return false;
    }
}
