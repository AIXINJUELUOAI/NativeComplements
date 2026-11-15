package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedLaunchPolicyTest {
    private static boolean ownedLaunch(int targetUid,int ownerUid,int targetUser,int ownerUser,Object request,Object target){
        return WindowPolicy.ownedLaunch(targetUid,ownerUid,targetUser,ownerUser,target!=null,request==null||request==target);
    }
    @Test public void onlyExactOwnerIdentityKeepsItsMode(){
        Object display=new Object();
        assertTrue(ownedLaunch(10146,10146,0,0,display,display));
        assertTrue(ownedLaunch(10146,10146,0,0,null,display));
        assertFalse(ownedLaunch(30118,10146,0,0,display,display));
        assertFalse(ownedLaunch(10146,10146,10,0,display,display));
        assertFalse(ownedLaunch(10146,10146,0,0,new Object(),display));
        assertFalse(ownedLaunch(1000,1000,0,0,display,display));
        assertFalse(ownedLaunch(10146,10146,0,0,null,null));
        assertFalse(WindowPolicy.sameWindowCaller(30118,10146,10146,"com.google.android.as","com.android.chrome"));
    }
}
