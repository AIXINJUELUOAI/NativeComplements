package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class LongBackGestureTest {
    private LongBackGesture gesture(){
        LongBackGesture state=new LongBackGesture();state.begin(0,10,1200,2670,3,7);return state;
    }
    @Test public void admissionStartsOnlyWithLiveSingleFingerOnDefaultDisplay(){
        assertTrue(LongBackGesture.mayStart(true,1,true,0));
        assertFalse(LongBackGesture.mayStart(false,1,true,0));
        assertFalse(LongBackGesture.mayStart(true,2,true,0));
        assertFalse(LongBackGesture.mayStart(true,1,false,0));
        assertFalse(LongBackGesture.mayStart(true,1,true,1));
        assertFalse(LongBackGesture.mayStart(true,1,true,-1));
    }
    @Test public void ordinaryBackPassesAndHintsAreDeduplicated(){
        LongBackGesture state=gesture();
        assertEquals(10,state.down());assertEquals(7,state.revision());
        assertFalse(state.hintChanged(0));
        var below=state.event(2,200,10,1,true);
        assertTrue(below.retain());assertFalse(below.tryAdmit());assertEquals(0,below.hint());
        var hint=state.event(2,220,10,1,true);
        assertEquals(1,hint.hint());assertTrue(state.hintChanged(hint.hint()));
        assertFalse(state.hintChanged(hint.hint()));
        var up=state.event(1,220,10,1,true);
        assertFalse(up.retain());assertFalse(up.tryAdmit());assertEquals(0,up.hint());
        assertTrue(state.hintChanged(up.hint()));assertFalse(state.hintChanged(0));
        assertFalse(state.event(2,700,10,1,true).tryAdmit());
    }
    @Test public void armedReleaseUsesSameHysteresisAndClearsBeforeAdmission(){
        LongBackGesture state=gesture();
        var armed=state.event(2,600,10,1,true);
        assertEquals(1,armed.armChange());assertEquals(2,armed.hint());assertFalse(armed.tryAdmit());
        assertEquals(0,state.event(2,565,10,1,true).armChange());
        var withdrawn=state.event(2,563,10,1,true);
        assertEquals(-1,withdrawn.armChange());assertEquals(1,withdrawn.hint());
        var up=state.event(1,600,10,1,true);
        assertEquals(1,up.armChange());assertFalse(up.retain());assertTrue(up.tryAdmit());assertEquals(0,up.hint());
        assertFalse(state.event(1,600,10,1,true).tryAdmit());
    }
    @Test public void rightLandscapeUsesMirroredThresholdAndMayArmOnlyAtUp(){
        LongBackGesture state=new LongBackGesture();state.begin(2600,10,2670,1200,3,2);
        assertFalse(state.event(2,2000,10,1,true).tryAdmit());
        var up=state.event(1,1800,10,1,true);
        assertTrue(up.tryAdmit());assertEquals(0,up.hint());
    }
    @Test public void cancellationMismatchMultiTouchAndLostLeaseNeverAdmit(){
        for(int kind=0;kind<5;kind++){
            LongBackGesture state=gesture();state.event(2,700,10,1,true);
            var cancelled=switch(kind){
                case 0 -> state.event(3,700,10,1,true);
                case 1 -> state.event(2,700,11,1,true);
                case 2 -> state.event(2,700,10,2,true);
                case 3 -> state.event(5,700,10,1,true);
                default -> state.event(2,700,10,1,false);
            };
            assertFalse(cancelled.retain());assertFalse(cancelled.tryAdmit());assertEquals(0,cancelled.hint());
            assertFalse(state.event(1,700,10,1,true).tryAdmit());
        }
    }
}
