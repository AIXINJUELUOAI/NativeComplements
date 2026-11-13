package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import android.view.WindowInsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarInsetsTest {
    @Test public void navigationRemovalCopiesStateAndRetainsKeyboardAndStatusBar() throws Exception {
        Class<?> stateClass = Class.forName("android.view.InsetsState");
        Class<?> sourceClass = Class.forName("android.view.InsetsSource");
        Object state = stateClass.getConstructor().newInstance();
        for (int type : new int[]{WindowInsets.Type.navigationBars(), WindowInsets.Type.ime(), WindowInsets.Type.statusBars()}) {
            Object source = sourceClass.getConstructor(int.class, int.class).newInstance(type, type);
            sourceClass.getMethod("setFrame", int.class, int.class, int.class, int.class).invoke(source, 0, 1800, 1000, 2000);
            sourceClass.getMethod("setVisible", boolean.class).invoke(source, true);
            stateClass.getMethod("addSource", sourceClass).invoke(state, source);
        }
        NavbarInsets tools = new NavbarInsets(stateClass);
        Object result = tools.strip(state);
        assertNotSame(state, result);
        assertEquals(3, stateClass.getMethod("sourceSize").invoke(state));
        assertEquals(2, stateClass.getMethod("sourceSize").invoke(result));
        for (int index = 0; index < 2; index++) {
            Object source = stateClass.getMethod("sourceAt", int.class).invoke(result, index);
            int type = (Integer) sourceClass.getMethod("getType").invoke(source);
            assertNotEquals(WindowInsets.Type.navigationBars(), type);
            assertEquals(new Rect(0, 1800, 1000, 2000), sourceClass.getMethod("getFrame").invoke(source));
        }
    }
}
