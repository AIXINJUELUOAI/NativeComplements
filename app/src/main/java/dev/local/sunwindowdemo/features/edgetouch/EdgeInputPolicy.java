package dev.local.sunwindowdemo.features.edgetouch;

import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.HashMap;
import java.util.Map;

/** Admission is fixed at each finger's DOWN, including fingers added to an existing stream. */
public final class EdgeInputPolicy {
    private final Map<Long,Integer> suppressed=new HashMap<>();
    private int[] rectangles=new int[0];

    public synchronized void regions(int[] value){rectangles=value.clone();}
    public synchronized void clear(){rectangles=new int[0];suppressed.clear();}
    public synchronized boolean holding(){return !suppressed.isEmpty();}

    /** Returns the accepted pointer-id bit mask. Non-touchscreen input passes through. */
    public synchronized int accepted(MotionEvent event,int display){
        int all=0;
        for(int i=0;i<event.getPointerCount();i++)all|=1<<event.getPointerId(i);
        if(display!=0||!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return all;
        long stream=((long)event.getDeviceId()<<32)|(display&0xffffffffL);
        int action=event.getActionMasked(),blocked=suppressed.getOrDefault(stream,0);
        if(action==MotionEvent.ACTION_DOWN)blocked=0;
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            int i=event.getActionIndex();
            if(event.getToolType(i)==MotionEvent.TOOL_TYPE_FINGER&&inside(event.getX(i),event.getY(i)))blocked|=1<<event.getPointerId(i);
        }
        int accepted=all&~blocked;
        if(action==MotionEvent.ACTION_POINTER_UP)blocked&=~(1<<event.getPointerId(event.getActionIndex()));
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL||blocked==0)suppressed.remove(stream);
        else suppressed.put(stream,blocked);
        return accepted;
    }

    private boolean inside(float x,float y){
        for(int i=0;i<rectangles.length;i+=4)
            if(x>=rectangles[i]&&x<rectangles[i+2]&&y>=rectangles[i+1]&&y<rectangles[i+3])return true;
        return false;
    }
}
