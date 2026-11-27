package dev.local.sunwindowdemo;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import dev.local.sunwindowdemo.core.HostSnapshot;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class LockscreenDoubleTapTest {
    @Test public void staleAndMissingSettingsNeverEnableSleep() {
        Bundle config = new Bundle();
        config.putBoolean("lockscreenDoubleTapSleep", true);
        assertFalse(LockscreenDoubleTap.configEnabled(null));
        assertFalse(LockscreenDoubleTap.configEnabled(new HostSnapshot.Result(config, false, false, "")));
        assertTrue(LockscreenDoubleTap.configEnabled(new HostSnapshot.Result(config, true, false, "")));
    }

    @Test public void fingerprintExclusionFollowsResolutionAndRotation() {
        Rect sensor = new Rect(400, 1600, 600, 1800);
        assertEquals(new Rect(200,800,300,900), LockscreenDoubleTap.fingerprintArea(sensor,.5f,500,1000,0));
        assertEquals(new Rect(800,200,900,300), LockscreenDoubleTap.fingerprintArea(sensor,.5f,500,1000,1));
        assertEquals(new Rect(200,100,300,200), LockscreenDoubleTap.fingerprintArea(sensor,.5f,500,1000,2));
        assertEquals(new Rect(100,200,200,300), LockscreenDoubleTap.fingerprintArea(sensor,.5f,500,1000,3));
    }

    @Test public void nativeInteractiveRegionsWinOverBlankTouches() {
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            FrameLayout root = new FrameLayout(activity.get());
            View button = new View(activity.get());
            button.setClickable(true);
            root.addView(button, new FrameLayout.LayoutParams(100,100));
            activity.get().setContentView(root);
            root.layout(0,0,400,600);
            button.layout(0,0,100,100);
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            assertFalse(LockscreenDoubleTap.blank(root,location[0]+50,location[1]+50,true));
            assertTrue(LockscreenDoubleTap.blank(root,location[0]+200,location[1]+300,true));
            assertFalse(LockscreenDoubleTap.blank(root,location[0]+500,location[1]+300,true));
        }
        assertTrue(LockscreenDoubleTap.protectedView("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"));
        assertTrue(LockscreenDoubleTap.protectedView("com.android.systemui.biometrics.UdfpsView"));
        assertTrue(LockscreenDoubleTap.protectedView("com.android.keyguard.KeyguardSecurityContainer"));
    }
}
