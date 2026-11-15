package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowAdmissionTest {
    @Test public void identityCropAndDiagnosticGuardsKeepExactTarget(){
        assertNull(WindowAdmission.identity("example.app","example.app"));
        assertEquals(WindowAdmission.Rejection.IDENTITY,WindowAdmission.identity("example.app",null));
        assertEquals(WindowAdmission.Rejection.IDENTITY,WindowAdmission.identity("example.app","other.app"));
        assertNull(WindowAdmission.diagnostic(false,"one","two"));
        assertNull(WindowAdmission.diagnostic(true,"one","one"));
        assertEquals(WindowAdmission.Rejection.DIAGNOSTIC_ID,WindowAdmission.diagnostic(true,"one",null));
        assertNull(WindowAdmission.crop(0,0));assertEquals(WindowAdmission.Rejection.CROP,WindowAdmission.crop(-1,100));
    }
    @Test public void eligibilityRejectsUnsupportedNativeFactsWithoutEnablingAnything(){
        assertNull(WindowAdmission.opaque(false));assertEquals(WindowAdmission.Rejection.OPAQUE,WindowAdmission.opaque(true));
        assertNull(WindowAdmission.capability(true,true));assertEquals(WindowAdmission.Rejection.CAPABILITY,WindowAdmission.capability(true,false));
        assertEquals(WindowAdmission.Rejection.CAPABILITY,WindowAdmission.capability(false,true));
        assertNull(WindowAdmission.hierarchy(new WindowAdmission.Hierarchy(true,true,true,true)));assertEquals(WindowAdmission.Rejection.HIERARCHY,WindowAdmission.hierarchy(new WindowAdmission.Hierarchy(true,true,false,true)));
        assertNull(WindowAdmission.displayUser(true,0,0));assertEquals(WindowAdmission.Rejection.DISPLAY_USER,WindowAdmission.displayUser(false,0,0));
        assertEquals(WindowAdmission.Rejection.DISPLAY_USER,WindowAdmission.displayUser(true,1,0));assertEquals(WindowAdmission.Rejection.DISPLAY_USER,WindowAdmission.displayUser(true,0,10));
        assertNull(WindowAdmission.activityType(1));assertEquals(WindowAdmission.Rejection.ACTIVITY_TYPE,WindowAdmission.activityType(2));
        assertNull(WindowAdmission.busy(false));assertEquals(WindowAdmission.Rejection.WAIT,WindowAdmission.busy(true));
        for(int bits=0;bits<16;bits++){
            boolean a=(bits&1)!=0,b=(bits&2)!=0,c=(bits&4)!=0,d=(bits&8)!=0;
            assertEquals(bits==15?null:WindowAdmission.Rejection.HIERARCHY,WindowAdmission.hierarchy(new WindowAdmission.Hierarchy(a,b,c,d)));
            assertEquals(bits==15?null:WindowAdmission.Rejection.RESIZE,WindowAdmission.resize(new WindowAdmission.Resize(a,b,c,d)));
        }
    }
    @Test public void onlyDirectLinkedFreeformCanInheritAndOnlyStandardFullscreenCanBeRestored(){
        assertTrue(WindowAdmission.inherited(true,5,false));assertFalse(WindowAdmission.inherited(false,5,false));
        assertFalse(WindowAdmission.inherited(true,5,true));assertFalse(WindowAdmission.inherited(true,1,false));
        assertNull(WindowAdmission.ownership(true,5,false,false));assertNull(WindowAdmission.ownership(false,1,false,true));
        assertEquals(WindowAdmission.Rejection.OWNERSHIP,WindowAdmission.ownership(false,5,false,true));
        assertEquals(WindowAdmission.Rejection.OWNERSHIP,WindowAdmission.ownership(false,1,true,true));
        assertEquals(WindowAdmission.Rejection.OWNERSHIP,WindowAdmission.ownership(false,1,false,false));
        for(int mode:new int[]{0,1})for(int top:new int[]{0,2})assertNull(WindowAdmission.override(mode,true,top));
        assertEquals(WindowAdmission.Rejection.OVERRIDE,WindowAdmission.override(5,true,2));
        assertEquals(WindowAdmission.Rejection.OVERRIDE,WindowAdmission.override(1,false,2));
        assertEquals(WindowAdmission.Rejection.OVERRIDE,WindowAdmission.override(1,true,1));
    }
}
