package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowHostPolicyTest {
    @Test public void cornerConfigurationRejectsEveryIndependentBlock(){
        assertTrue(WindowHostPolicy.cornerConfigured(true,true,false,false,false));
        for(int blocked=0;blocked<5;blocked++){
            boolean[] facts={true,true,false,false,false};facts[blocked]=!facts[blocked];
            assertFalse(WindowHostPolicy.cornerConfigured(facts[0],facts[1],facts[2],facts[3],facts[4]));
        }
    }
    @Test public void defaultSlotAllowsRecoveryButNeverUnknownOrOccupiedNormal(){
        for(String state:new String[]{"idle","recovery"}){
            assertTrue(WindowHostPolicy.defaultSlot(state,true));assertTrue(WindowHostPolicy.defaultSlot(state,false));
        }
        assertTrue(WindowHostPolicy.defaultSlot("active",false));assertFalse(WindowHostPolicy.defaultSlot("active",true));
        for(String state:new String[]{null,"","conflict","error"})assertFalse(WindowHostPolicy.defaultSlot(state,false));
    }
    @Test public void notificationAndCurrentAdmissionIntentionallyDiffer(){
        assertFalse(WindowHostPolicy.notificationConfigured(true,true,true,"recovery"));
        assertTrue(WindowHostPolicy.slotConfigured(true,true,WindowHostPolicy.defaultSlot("recovery",true)));
        assertTrue(WindowHostPolicy.notificationConfigured(true,true,true,"active"));
        assertFalse(WindowHostPolicy.slotConfigured(true,true,WindowHostPolicy.defaultSlot("active",true)));
        assertFalse(WindowHostPolicy.notificationConfigured(false,true,true,"idle"));
        assertFalse(WindowHostPolicy.notificationConfigured(true,false,true,"idle"));
        assertFalse(WindowHostPolicy.notificationConfigured(true,true,false,"idle"));
    }
    @Test public void longBackNeedsItsOwnSwitchAndFreeDefaultSlot(){
        assertTrue(WindowHostPolicy.slotConfigured(true,true,true));assertFalse(WindowHostPolicy.slotConfigured(false,true,true));
        assertFalse(WindowHostPolicy.slotConfigured(true,false,true));assertFalse(WindowHostPolicy.slotConfigured(true,true,false));
    }
    @Test public void targetExclusionsKeepTheirBoundaries(){
        assertTrue(WindowHostPolicy.currentTarget(0,false,false,false,true));
        assertFalse(WindowHostPolicy.currentTarget(1,false,false,false,true));
        assertFalse(WindowHostPolicy.currentTarget(0,true,false,false,true));
        assertFalse(WindowHostPolicy.currentTarget(0,false,true,false,true));
        assertFalse(WindowHostPolicy.currentTarget(0,false,false,true,true));
        assertFalse(WindowHostPolicy.currentTarget(0,false,false,false,false));
    }
}
