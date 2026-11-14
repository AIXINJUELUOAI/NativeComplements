package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedLaunchPolicyTest {
    @Test public void onlyExactOwnerIdentityKeepsItsMode(){
        Object display=new Object();
        assertTrue(WindowPolicy.ownedLaunch(10146,10146,0,0,display,display));
        assertTrue(WindowPolicy.ownedLaunch(10146,10146,0,0,null,display));
        assertFalse(WindowPolicy.ownedLaunch(30118,10146,0,0,display,display));
        assertFalse(WindowPolicy.ownedLaunch(10146,10146,10,0,display,display));
        assertFalse(WindowPolicy.ownedLaunch(10146,10146,0,0,new Object(),display));
        assertFalse(WindowPolicy.ownedLaunch(1000,1000,0,0,display,display));
        assertFalse(WindowPolicy.ownedLaunch(10146,10146,0,0,null,null));
        assertFalse(WindowPolicy.sameWindowCaller(30118,10146,10146,"com.google.android.as","com.android.chrome"));
    }
}
