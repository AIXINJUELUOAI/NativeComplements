package dev.local.sunwindowdemo;

import android.os.Binder;
import java.io.File;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WindowNavigationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void delegatedNotificationBindsItsActualTargetOnlyForTheExactSend(){
        Binder original=new Binder(),unrelated=new Binder();
        WindowNavigation.Launch launch=new WindowNavigation.Launch(12,"dev.willdo.app",null,0,original,0);
        assertTrue(launch.accepts(12,original,null));
        assertTrue(launch.targetAllowed("com.example.ride",0));
        assertFalse(launch.accepts(12,unrelated,null));
        assertFalse(launch.accepts(13,original,null));
        assertFalse(launch.targetAllowed("com.example.ride",10));
        Object dispatchedTask=new Object();launch.task=dispatchedTask;launch.dispatched=true;
        assertTrue("An activity forwarding after PendingIntent completion is still part of this task's launch",launch.accepts(12,null,dispatchedTask));
        assertFalse(launch.accepts(12,null,new Object()));
        WindowNavigation.Launch suggestion=new WindowNavigation.Launch(12,"com.example.shop","com.example.shop",0,original,0);
        assertFalse(suggestion.targetAllowed("com.example.other",0));
    }

    @Test public void gestureUnfoldsOneStepWhileExplicitNavigationOpensContent(){
        assertEquals("restore-mini",WindowNavigation.wakeForm("mini",true,true));
        assertEquals("normal",WindowNavigation.wakeForm("mini",false,true));
        assertEquals("normal",WindowNavigation.wakeForm("mini",true,false));
        assertEquals("normal",WindowNavigation.wakeForm("normal",false,true));
    }

    @Test public void rememberedDirectionSurvivesRestartWithoutLeakingToOtherPagesOrUsers()throws Exception{
        File file=new File(temporary.getRoot(),"orientation.json");
        WindowOrientation old=new WindowOrientation(file);
        old.remember(0,"com.example.media","Player",true);old.flush();
        WindowOrientation current=new WindowOrientation(file);current.load();
        assertEquals(Boolean.TRUE,current.get(0,"com.example.media","Player"));
        assertNull(current.get(0,"com.example.media","Home"));
        assertNull(current.get(10,"com.example.media","Player"));
        assertTrue(WindowPolicy.initialLandscape(-1,-1,-1,current.get(0,"com.example.media","Player")));
        assertFalse("A new page's explicit portrait request wins over saved landscape",WindowPolicy.initialLandscape(-1,-1,1,true));
        assertFalse("An app without memory still starts in portrait",WindowPolicy.initialLandscape(-1,-1,0,null));
        assertTrue("Landscape-only apps retain the original rule",WindowPolicy.initialLandscape(0,0,0,null));
    }
}
