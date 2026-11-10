package dev.local.sunwindowdemo.features.iconlayers;

import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LayeredTestIconTest {
    @Test public void nativeAnimationCopiesKeepIndependentLayers() {
        LayeredTestIcon original = new LayeredTestIcon(RuntimeEnvironment.getApplication().getResources(), 1);
        LayeredTestIcon copy = (LayeredTestIcon) original.getConstantState().newDrawable();
        assertNotSame(original.getBackground(), original.getForeground());
        assertNotSame(original.getBackground(), copy.getBackground());
        assertNotSame(original.getForeground(), copy.getForeground());
        assertNotSame(original.getMonochrome(), copy.getMonochrome());
        assertEquals(0xFFFCE4EC, ((ColorDrawable) copy.getBackground()).getColor());
        original.setBounds(0, 0, 120, 120);
        Rect before = new Rect(original.getForeground().getBounds());
        copy.setBounds(0, 0, 60, 60);
        assertEquals(before, original.getForeground().getBounds());
        ((ColorDrawable) copy.getBackground()).setColor(0xFF000000);
        copy.getForeground().setAlpha(0);
        assertEquals(0xFFFCE4EC, ((ColorDrawable) original.getBackground()).getColor());
        assertEquals(255, original.getForeground().getAlpha());
    }

    @Test public void alternateBackgroundSurvivesResourceAwareCopy() {
        var resources = RuntimeEnvironment.getApplication().getResources();
        LayeredTestIcon test = new LayeredTestIcon(resources, 2);
        Drawable copy = test.getConstantState().newDrawable(resources);
        assertTrue(copy instanceof LayeredTestIcon);
        assertEquals(0xFF9EE9D9, ((ColorDrawable) ((LayeredTestIcon) copy).getBackground()).getColor());
        assertNotNull(((LayeredTestIcon) copy).getForeground());
    }
}
