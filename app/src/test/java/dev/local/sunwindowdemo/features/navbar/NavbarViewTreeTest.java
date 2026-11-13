package dev.local.sunwindowdemo.features.navbar;

import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarViewTreeTest {
    private byte[] tree(String parentType, int top, int bottom, int visibility) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Class<?> type = Class.forName("android.view.ViewHierarchyEncoder");
        Object encoder = type.getConstructor(ByteArrayOutputStream.class).newInstance(output);
        begin(type, encoder, parentType, 0, 2000, 1, 0);
        type.getMethod("addPropertyKey", String.class).invoke(encoder, "meta:__child__0");
        begin(type, encoder, "android.widget.LinearLayout", top, bottom, 2, visibility);
        type.getMethod("endObject").invoke(encoder);
        type.getMethod("endObject").invoke(encoder);
        type.getMethod("endStream").invoke(encoder);
        return output.toByteArray();
    }
    private void begin(Class<?> type, Object encoder, String name, int top, int bottom, int children, int visibility) throws Exception {
        type.getMethod("beginObject", Object.class).invoke(encoder, new Object());
        type.getMethod("addProperty", String.class, String.class).invoke(encoder, "meta:__name__", name);
        String[] keys = {"layout:left", "layout:top", "layout:right", "layout:bottom", "meta:__childCount__", "misc:visibility"};
        int[] values = {0, top, 1000, bottom, children, visibility};
        for (int index = 0; index < keys.length; index++)
            type.getMethod("addProperty", String.class, int.class).invoke(encoder, keys[index], values[index]);
    }
    @Test public void detectsWideFixedBottomBarFromNativeEncodedStream() throws Exception {
        assertEquals(new Rect(0, 1760, 1000, 1920), NavbarViewTree.bottomControl(tree("android.widget.FrameLayout", 1760, 1920, 0), 1000, 2000, 80));
    }
    @Test public void scrollContentAndHiddenOrNonBottomRowsAreExcluded() throws Exception {
        assertNull(NavbarViewTree.bottomControl(tree("androidx.recyclerview.widget.RecyclerView", 1760, 1920, 0), 1000, 2000, 80));
        assertNull(NavbarViewTree.bottomControl(tree("android.widget.FrameLayout", 1760, 1920, 8), 1000, 2000, 80));
        assertNull(NavbarViewTree.bottomControl(tree("android.widget.FrameLayout", 1500, 1660, 0), 1000, 2000, 80));
    }
    @Test(expected = java.io.IOException.class) public void malformedDataFailsClosed() throws Exception {
        NavbarViewTree.bottomControl(new byte[]{'M', 'I', 0}, 1000, 2000, 80);
    }
}
