package dev.local.sunwindowdemo.features.edgetouch;

import org.junit.Test;
import static org.junit.Assert.*;

public class GameCornerAccessTest {
    @Test public void bothCurrentWindowPackageAndRealGameUidMustMatch(){
        assertTrue(GameCornerAccess.matches("example.game",10042,"example.game",10042));
        // A top game task beneath the shade or another app does not authorize its touches.
        assertFalse(GameCornerAccess.matches("example.game",10042,"com.android.systemui",1000));
        assertFalse(GameCornerAccess.matches("example.game",10042,"example.game",10043));
        assertFalse(GameCornerAccess.matches("example.game",10042,"example.game",110042));
        assertFalse(GameCornerAccess.matches("example.game",10042,null,10042));
        assertFalse(GameCornerAccess.matches(null,10042,"example.game",10042));
        assertFalse(GameCornerAccess.matches("example.game",-1,"example.game",-1));
    }

    @Test public void missingWindowHostFailsPreflightRatherThanInventingPermission(){
        assertThrows(ClassNotFoundException.class,()->GameCornerAccess.resolve(new ClassLoader(null){}));
    }
}
