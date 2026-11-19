package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

/** Framework-free policy checks; the Android executor keeps native state and WM lock ownership. */
public class WindowPresentationTest {
    @Test public void hiddenEdgeSubtreeIsAbsentButEntryAnimationAndContentDragStayVisible(){
        assertTrue(WindowPresentation.edgeContentHidden(true,false,false,false));
        assertTrue(WindowPresentation.edgeContentHidden(true,false,true,true));
        assertFalse(WindowPresentation.edgeContentHidden(true,true,false,false));
        assertFalse(WindowPresentation.edgeContentHidden(true,false,true,false));
        assertFalse(WindowPresentation.edgeContentHidden(false,false,false,false));
        assertEquals(WindowPresentation.Route.SUSPENDED,WindowPresentation.gate(facts(false,false,false,
            WindowPresentation.edgeContentHidden(true,false,false,false),false,false)));
    }
    @Test public void handleHasContrastingDayAndNightColors(){
        assertEquals(0xff34363b,WindowPresentation.handleColor(false));
        assertEquals(0xfff0f2f2,WindowPresentation.handleColor(true));
    }
    private static WindowPresentation.Facts facts(boolean priority,boolean external,boolean parked,boolean screen,boolean start,boolean rotation){
        return new WindowPresentation.Facts(true,true,false,true,priority,external,parked,screen,start,rotation);
    }
    @Test public void presentationGatesPreservePriorityAndMaskPrecedence(){
        assertEquals(WindowPresentation.Route.PRIORITY_SAME_TASK,WindowPresentation.gate(facts(true,false,false,false,true,true)));
        assertEquals(WindowPresentation.Route.PRIORITY_HIDDEN,WindowPresentation.gate(facts(true,true,false,false,true,true)));
        assertEquals(WindowPresentation.Route.PRIORITY_HIDDEN,WindowPresentation.gate(facts(true,false,true,false,true,true)));
        assertEquals(WindowPresentation.Route.SUSPENDED,WindowPresentation.gate(facts(false,false,false,true,true,true)));
        assertEquals(WindowPresentation.Route.START_MASK,WindowPresentation.gate(facts(false,false,false,false,true,true)));
        assertEquals(WindowPresentation.Route.ROTATION_MASK,WindowPresentation.gate(facts(false,false,false,false,false,true)));
        assertEquals(WindowPresentation.Route.PROBE_NATIVE,WindowPresentation.gate(facts(false,false,false,false,false,false)));
        assertEquals(WindowPresentation.Route.NATIVE_LEASH,WindowPresentation.presentation(true,true));
        assertEquals(WindowPresentation.Route.COVER_REUSE,WindowPresentation.presentation(false,true));
        assertEquals(WindowPresentation.Route.LIVE,WindowPresentation.presentation(false,false));
    }
    @Test public void invalidAndRestoringSessionsNeverProbeNativePresentation(){
        assertEquals(WindowPresentation.Route.UNMANAGED,WindowPresentation.gate(new WindowPresentation.Facts(false,true,true,true,true,true,true,true,true,true)));
        assertEquals(WindowPresentation.Route.UNMANAGED,WindowPresentation.gate(new WindowPresentation.Facts(true,false,true,true,true,true,true,true,true,true)));
        assertEquals(WindowPresentation.Route.RESTORING,WindowPresentation.gate(new WindowPresentation.Facts(true,true,true,false,true,true,true,true,true,true)));
        assertEquals(WindowPresentation.Route.NO_PLACEMENT,WindowPresentation.gate(new WindowPresentation.Facts(true,true,false,false,true,true,true,true,true,true)));
    }
    private static WindowPresentation.Alpha alpha(boolean priority,boolean external,boolean parked,boolean screen,boolean restoring,boolean foreground,boolean start,boolean rotation,boolean cover,boolean exiting){
        return new WindowPresentation.Alpha(priority,external,parked,screen,restoring,foreground,start,rotation,cover,exiting,.8f,.5f);
    }
    @Test public void taskAndCoverReleaseRetainTheirDifferentRestorationSemantics(){
        assertEquals(.4f,WindowPresentation.alpha(alpha(false,false,false,false,false,false,false,false,false,false)),0);
        assertEquals(1,WindowPresentation.alpha(alpha(true,false,false,false,true,false,true,true,true,true)),0);
        assertEquals(0,WindowPresentation.alpha(alpha(true,true,false,false,false,false,false,false,false,false)),0);
        assertEquals(0,WindowPresentation.alpha(alpha(false,false,false,false,true,true,false,false,false,false)),0);
        assertEquals(1,WindowPresentation.coverReleaseAlpha(alpha(false,false,false,false,true,true,false,false,false,false)),0);
        assertEquals(0,WindowPresentation.coverReleaseAlpha(alpha(false,false,false,false,false,false,false,true,false,false)),0);
        assertEquals(.4f,WindowPresentation.alpha(alpha(false,false,false,false,false,false,false,true,false,false)),0);
        assertEquals(0,WindowPresentation.coverReleaseAlpha(alpha(false,false,false,false,false,false,false,false,false,true)),0);
        assertEquals(.4f,WindowPresentation.coverReleaseAlpha(alpha(false,false,false,false,false,false,false,false,true,false)),0);
        assertEquals(0,WindowPresentation.alpha(alpha(false,false,false,false,false,false,false,false,true,false)),0);
    }
    @Test public void cropOnlyTracksFullWidthNativeInsetNotTransparentClientContentOrDialogs(){
        assertEquals(new WindowPresentation.Crop(0,126,1200,2133),WindowPresentation.crop(1200,2133,true,0,126,1200,2133,0,0,1200,2133));
        assertEquals(new WindowPresentation.Crop(0,0,1200,2133),WindowPresentation.crop(1200,2133,true,0,0,1200,2133,0,0,1200,2133));
        assertEquals(new WindowPresentation.Crop(0,0,1200,2133),WindowPresentation.crop(1200,2133,true,100,126,1100,2133,0,0,1200,2133));
        assertEquals(new WindowPresentation.Crop(0,0,1200,2133),WindowPresentation.crop(1200,2133,true,0,126,1200,1500,0,0,1200,2133));
        assertEquals(new WindowPresentation.Crop(0,0,1200,2133),WindowPresentation.crop(1200,2133,false,0,126,1200,2133,0,0,1200,2133));
        Geometry.Placement p=new Geometry.Placement(1200,2133,.75f,100,200);
        assertEquals(20,WindowPresentation.radius(p,3,.75f,1,40),0);
    }
    @Test public void backingAndLeashHandleDoNotFollowCoverPoseOrMaskOpacity(){
        assertTrue(WindowPresentation.backing(false,false,false));assertFalse(WindowPresentation.backing(true,false,false));
        assertFalse(WindowPresentation.backing(false,true,false));assertFalse(WindowPresentation.backing(false,false,true));
        assertTrue(WindowPresentation.hideOnlyHandle(true,false,false,false));assertFalse(WindowPresentation.hideOnlyHandle(true,true,false,false));
        assertTrue(WindowPresentation.adjustForIme(true,false,false,false,false,false));assertFalse(WindowPresentation.adjustForIme(true,false,true,false,false,false));
    }
}
