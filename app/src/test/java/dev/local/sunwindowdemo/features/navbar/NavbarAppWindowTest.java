package dev.local.sunwindowdemo.features.navbar;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarAppWindowTest {
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(target);
    }
    private static void call(Object target, String name, View root) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, View.class); method.setAccessible(true);
        method.invoke(target, root);
    }
    @Test public void attachedDialogRootIsTrackedIndependentlyAndRemovedOnDetach() throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        Dialog dialog = new Dialog(activity); dialog.show();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        NavbarApp bridge = new NavbarApp();
        View base = activity.getWindow().getDecorView(), popup = dialog.getWindow().getDecorView();
        call(bridge, "trackRoot", base);
        call(bridge, "trackRoot", popup);
        Map<?, ?> roots = (Map<?, ?>) field(bridge, "layoutWatch");
        assertEquals(2, roots.size());
        assertTrue(roots.containsKey(popup));
        assertTrue(((Map<?, ?>) field(bridge, "activities")).isEmpty());
        call(bridge, "trackRoot", popup);
        assertEquals(2, roots.size());
        dialog.dismiss();
        assertFalse(roots.containsKey(popup));
        assertTrue(roots.containsKey(base));
        controller.pause().stop().destroy();
        assertTrue(roots.isEmpty());
    }
    @Test public void unattachedRootIsNotScheduled() throws Exception {
        NavbarApp bridge = new NavbarApp();
        call(bridge, "trackRoot", new View(org.robolectric.RuntimeEnvironment.getApplication()));
        assertTrue(((Map<?, ?>) field(bridge, "layoutWatch")).isEmpty());
    }
}
