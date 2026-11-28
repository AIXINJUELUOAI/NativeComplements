package dev.local.sunwindowdemo.features.minusone;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.graphics.Insets;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ScrollView;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MinusOneActivityTest {
    @Test public void coldStartDoesNotReadInsetsBeforeDecorExists() {
        try (var controller = Robolectric.buildActivity(MinusOneActivity.class)) {
            var activity = controller.create().start().resume().visible().get();
            assertNotNull(activity.getWindow().peekDecorView());
            var content = (ViewGroup) activity.findViewById(android.R.id.content);
            var overlay = (ViewGroup) content.getChildAt(0);
            assertNull(overlay.getForeground());
            var root = (ViewGroup) overlay.getChildAt(0);
            var scroll = (ScrollView) root.getChildAt(1);
            root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0,0,0,50)).build());
            assertEquals(0, root.getPaddingBottom());
            assertEquals(50, scroll.getPaddingBottom());
            assertFalse(scroll.getClipToPadding());
        }
    }
}
