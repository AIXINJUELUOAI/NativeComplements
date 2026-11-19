package dev.local.sunwindowdemo;

import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopDoubleTapTest {
    private final DesktopDoubleTap.Taps taps=new DesktopDoubleTap.Taps();
    private boolean event(int action,long time,float x,float y,int pointers,boolean eligible){
        return taps.event(action,time,x,y,pointers,eligible,8,100,300,500);
    }
    private boolean tap(long time,float x){
        assertFalse(event(MotionEvent.ACTION_DOWN,time,x,100,1,true));
        return event(MotionEvent.ACTION_UP,time+40,x,100,1,true);
    }
    @Test public void actsOnlyOnSecondReleaseAndDoesNotChainThirdTap(){
        assertFalse(tap(100,100));
        assertFalse(event(MotionEvent.ACTION_DOWN,250,105,100,1,true));
        assertTrue(event(MotionEvent.ACTION_UP,290,105,100,1,true));
        assertFalse(tap(400,105));
    }
    @Test public void rejectsLongPressMovementCancelMultiplePointersAndLostEligibility(){
        assertFalse(tap(100,100));
        event(MotionEvent.ACTION_DOWN,250,100,100,1,true);
        assertFalse(event(MotionEvent.ACTION_MOVE,280,120,100,1,true));
        assertFalse(event(MotionEvent.ACTION_UP,290,100,100,1,true));
        assertFalse(tap(400,100));
        event(MotionEvent.ACTION_DOWN,550,100,100,1,true);
        assertFalse(event(MotionEvent.ACTION_UP,1050,100,100,1,true));
        for(int action:new int[]{MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN}){
            taps.reset();assertFalse(tap(1100,100));
            event(action,1200,100,100,action==MotionEvent.ACTION_POINTER_DOWN?2:1,true);
            assertFalse(tap(1250,100));
        }
        taps.reset();assertFalse(tap(1500,100));
        event(MotionEvent.ACTION_DOWN,1600,100,100,1,false);
        assertFalse(event(MotionEvent.ACTION_UP,1640,100,100,1,true));
        assertFalse(tap(1700,100));
    }
    @Test public void rejectsFarApartSlowAndInvalidCoordinates(){
        assertFalse(tap(100,100));assertFalse(tap(250,250));
        assertFalse(tap(800,250));
        assertFalse(event(MotionEvent.ACTION_DOWN,900,Float.NaN,100,1,true));
        assertFalse(tap(950,250));
    }
}
