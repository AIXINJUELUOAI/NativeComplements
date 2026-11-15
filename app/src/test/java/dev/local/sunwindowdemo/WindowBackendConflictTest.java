package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowBackendConflictTest {
    @Test public void losingHostCannotBecomeReadyOrAssumeSafeEnvironment() throws Exception {
        var constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        Context app=RuntimeEnvironment.getApplication();
        var display=app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        SystemUiHost host=constructor.newInstance(app.createDisplayContext(display));
        ReflectionHelpers.setField(host,"controls",new WindowControls(host));
        ReflectionHelpers.setField(host,"miniControls",new WindowControls(host));
        ReflectionHelpers.setField(host,"backendReady",true);
        var receive=SystemUiHost.class.getDeclaredMethod("receiveFrame",Intent.class);receive.setAccessible(true);
        receive.invoke(host,new Intent().putExtra("state","conflict").putExtra("ownershipConflict",true));
        assertFalse(ReflectionHelpers.getField(host,"backendReady"));
        assertTrue(ReflectionHelpers.getField(host,"systemFocus"));
        assertTrue(ReflectionHelpers.getField(host,"keyboard"));
        assertEquals("",ReflectionHelpers.getField(host,"foreground"));
    }
}
