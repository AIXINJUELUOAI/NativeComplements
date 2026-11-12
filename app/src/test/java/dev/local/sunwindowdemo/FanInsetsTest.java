package dev.local.sunwindowdemo;

import static org.junit.Assert.*;
import android.graphics.Insets;
import android.view.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36,manifest=Config.NONE)
public class FanInsetsTest {
    @Test public void landscapeDoesNotReserveHiddenNavigationBar()throws Exception {
        FanView fan=new FanView(RuntimeEnvironment.getApplication(),List.of(),true,48,200,80,90);
        fan.layout(0,0,1000,500);
        WindowInsets visible=new WindowInsets.Builder().setInsets(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,80))
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,80)).setVisible(WindowInsets.Type.navigationBars(),true).build();
        // An attached view's actual root insets are authoritative; without them the
        // host supplies the current visible inset, not the hidden inset reservation.
        Method bottom=FanView.class.getDeclaredMethod("bottomInset");bottom.setAccessible(true);
        assertEquals(80f,(Float)bottom.invoke(fan),.001f);
        FanView immersive=new FanView(RuntimeEnvironment.getApplication(),List.of(),true,48,200,0,90);
        immersive.layout(0,0,1000,500);
        Method oy=FanView.class.getDeclaredMethod("oy");oy.setAccessible(true);
        assertEquals(80f,(Float)oy.invoke(immersive)-(Float)oy.invoke(fan),.001f);
        assertEquals(80,visible.getInsets(WindowInsets.Type.navigationBars()).bottom);
    }
}
