package dev.local.sunwindowdemo.features.edgetouch;

import android.view.InputDevice;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class EdgeInputPolicyTest {
    private final EdgeInputPolicy policy=new EdgeInputPolicy();
    private MotionEvent event(int action,float... xs){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[xs.length];
        for(int i=0;i<xs.length;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=xs[i];coords[i].y=200;coords[i].pressure=1;
        }
        return MotionEvent.obtain(1,2,action,xs.length,properties,coords,0,0,1,1,7,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    }
    private int accept(int action,float... xs){
        MotionEvent event=event(action,xs);
        try{return policy.accepted(event,0);}finally{event.recycle();}
    }
    private MotionEvent split(MotionEvent event,int accepted){
        return org.robolectric.util.ReflectionHelpers.callInstanceMethod(event,"split",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,accepted));
    }

    @Test public void downInsideStaysSuppressedAfterMovingOutAndSettingsChange(){
        policy.regions(new int[]{0,0,20,1000});
        assertEquals(0,accept(MotionEvent.ACTION_DOWN,5));assertTrue(policy.holding());
        policy.regions(new int[0]);
        assertEquals(0,accept(MotionEvent.ACTION_MOVE,200));
        assertEquals(0,accept(MotionEvent.ACTION_UP,200));assertFalse(policy.holding());
        assertEquals(1,accept(MotionEvent.ACTION_DOWN,5));
    }
    @Test public void movingIntoTheEdgeNeverCancelsAnAdmittedFinger(){
        policy.regions(new int[]{0,0,20,1000});
        assertEquals(1,accept(MotionEvent.ACTION_DOWN,200));
        assertEquals(1,accept(MotionEvent.ACTION_MOVE,5));
        assertEquals(1,accept(MotionEvent.ACTION_UP,5));
    }
    @Test public void secondEdgeFingerIsRemovedWhileFirstFingerKeepsItsStream(){
        policy.regions(new int[]{0,0,20,1000});
        assertEquals(1,accept(MotionEvent.ACTION_DOWN,200));
        assertEquals(1,accept(MotionEvent.ACTION_POINTER_DOWN|(1<<8),200,5));
        MotionEvent source=event(MotionEvent.ACTION_POINTER_DOWN|(1<<8),200,5);
        MotionEvent kept=split(source,policy.accepted(source,0));
        assertEquals(1,kept.getPointerCount());assertEquals(MotionEvent.ACTION_MOVE,kept.getActionMasked());
        source.recycle();kept.recycle();
        assertEquals(1,accept(MotionEvent.ACTION_POINTER_UP|(1<<8),200,100));
        assertFalse(policy.holding());assertEquals(1,accept(MotionEvent.ACTION_UP,200));
    }
    @Test public void firstBlockedFingerDoesNotPreventASecondValidFingerFromStarting(){
        policy.regions(new int[]{0,0,20,1000});
        assertEquals(0,accept(MotionEvent.ACTION_DOWN,5));
        MotionEvent source=event(MotionEvent.ACTION_POINTER_DOWN|(1<<8),5,200);
        MotionEvent kept=split(source,policy.accepted(source,0));
        assertEquals(1,kept.getPointerId(0));assertEquals(MotionEvent.ACTION_DOWN,kept.getActionMasked());
        source.recycle();kept.recycle();
        accept(MotionEvent.ACTION_CANCEL,5,200);assertFalse(policy.holding());
    }
    @Test public void otherDisplaysAndMouseRemainNative(){
        policy.regions(new int[]{0,0,20,1000});
        MotionEvent event=event(MotionEvent.ACTION_DOWN,5);
        assertEquals(1,policy.accepted(event,1));
        event.setSource(InputDevice.SOURCE_MOUSE);assertEquals(1,policy.accepted(event,0));event.recycle();
    }
    private boolean inside(int[] boxes,int x,int y){
        for(int i=0;i<boxes.length;i+=4)if(x>=boxes[i]&&x<boxes[i+2]&&y>=boxes[i+1]&&y<boxes[i+3])return true;
        return false;
    }
    @Test public void gripCornersFollowRotationAndProtectedControlsRemainAvailable(){
        for(int rotation=0;rotation<4;rotation++){
            int[] boxes=EdgeTouchGeometry.buildSuppression(1000,2000,50,50,1,true,true,8,8,60,50,new int[0],rotation,24);
            assertEquals(rotation==1||rotation==2,inside(boxes,15,65));
            assertEquals(rotation==2||rotation==3,inside(boxes,985,65));
            assertEquals(rotation==0||rotation==1,inside(boxes,15,1935));
            assertEquals(rotation==0||rotation==3,inside(boxes,985,1935));
            int[] safe=EdgeTouchGeometry.buildSuppression(1000,2000,50,50,1,true,true,8,8,60,50,new int[]{0,100,40,300},rotation,24);
            assertFalse(inside(safe,4,200));
            assertFalse(inside(boxes,4,25));assertFalse(inside(boxes,4,1975));
        }
    }
}
