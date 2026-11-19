package dev.local.sunwindowdemo.features.edgetouch;

import dev.local.sunwindowdemo.features.gamemode.GameCornerGeometry;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameCornerPolicyTest {
    @Test public void onlyBottomCornersAreCirclesAndRadiusIsContinuous(){
        assertEquals(100,GameCornerGeometry.radius(2000,1000,10),0);
        assertEquals(0,GameCornerGeometry.radius(2000,1000,-1),0);
        assertEquals(500,GameCornerGeometry.radius(2000,1000,50),0);
        EdgeInputPolicy expanded=new EdgeInputPolicy();expanded.corners(2000,1000,50,new int[0]);
        assertEquals(0,expanded.accepted(8,EdgeInputPolicy.Action.DOWN,1,1,true,400,999));
        assertEquals(1,expanded.accepted(9,EdgeInputPolicy.Action.DOWN,1,1,true,501,999));
        assertEquals(103.75f,GameCornerGeometry.radius(2000,1000,10.375f),0.0001f);
        assertEquals(100,GameCornerGeometry.radius(2000,1000,Float.NaN),0);
        for(float[] p:new float[][]{{10,990},{1990,990},{60,920}})
            assertTrue(GameCornerGeometry.inside(p[0],p[1],2000,1000,10));
        for(float[] p:new float[][]{{10,10},{1990,10},{80,920}})
            assertFalse(GameCornerGeometry.inside(p[0],p[1],2000,1000,10));
        assertFalse(GameCornerGeometry.inside(1000,500,2000,1000,10));
        assertFalse(GameCornerGeometry.inside(Float.NaN,0,2000,1000,10));
        assertFalse(GameCornerGeometry.inside(-1,0,2000,1000,10));
    }
    @Test public void fractionalRadiusReachesPointerAdmissionWithoutIntegerRounding(){
        EdgeInputPolicy p=new EdgeInputPolicy();p.corners(2000,1000,10.375f,new int[0]);
        assertEquals(0,p.accepted(1,EdgeInputPolicy.Action.DOWN,1,1,true,102,999));
        p.corners(2000,1000,10f,new int[0]);
        assertEquals(1,p.accepted(2,EdgeInputPolicy.Action.DOWN,1,1,true,102,999));
    }
    @Test public void eachPointerKeepsItsBirthDecisionAcrossMovementSizeChangesAndEligibilityLoss(){
        EdgeInputPolicy p=new EdgeInputPolicy();p.corners(2000,1000,10,new int[0]);
        assertEquals(0,p.accepted(1,EdgeInputPolicy.Action.DOWN,1,1,true,20,980,true));
        assertEquals(4,p.accepted(1,EdgeInputPolicy.Action.POINTER_DOWN,5,4,true,500,500,true));
        p.corners(2000,1000,24,new int[0]);
        assertEquals(4,p.accepted(1,EdgeInputPolicy.Action.OTHER,5,0,true,10,10,true));
        // System panel or expiry stops new suppression, not ownership of the old rejected finger.
        assertEquals(12,p.accepted(1,EdgeInputPolicy.Action.POINTER_DOWN,13,8,true,10,990,false));
        p.corners(0,0,0,new int[0]);
        assertEquals(12,p.accepted(1,EdgeInputPolicy.Action.POINTER_UP,13,1,true,500,500,false));
        assertFalse(p.holding());
        assertEquals(12,p.accepted(1,EdgeInputPolicy.Action.CANCEL,12,0,true,0,0,false));
    }
    @Test public void protectedControlsAndOldEdgeRectanglesRemainIndependent(){
        EdgeInputPolicy p=new EdgeInputPolicy();p.corners(2000,1000,10,new int[]{0,960,40,1000});
        assertEquals(1,p.accepted(1,EdgeInputPolicy.Action.DOWN,1,1,true,10,990));
        assertEquals(0,p.accepted(2,EdgeInputPolicy.Action.DOWN,1,1,true,60,980));
        p.regions(new int[]{0,0,30,1000});
        assertEquals(0,p.accepted(3,EdgeInputPolicy.Action.DOWN,1,1,true,10,500,false));
    }
}
