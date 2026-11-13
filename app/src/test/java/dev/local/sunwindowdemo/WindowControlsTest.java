package dev.local.sunwindowdemo;

import android.app.Activity;
import android.content.*;
import android.graphics.Region;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WindowControlsTest {
    private Intent frame(long sequence, String mode, boolean transition, boolean pending) {
        return new Intent().putExtra("protocol",Wire.PROTOCOL).putExtra("sequence",sequence)
            .putExtra("state","active").putExtra("presented",true).putExtra("taskId",10)
            .putExtra("token","sunwindow5:11111111-1111-1111-1111-111111111111")
            .putExtra("frame",mode).putExtra("transitioning",transition).putExtra("inputPending",pending)
            .putExtra("x",100f).putExtra("y",200f).putExtra("width",200f).putExtra("height",300f)
            .putExtra("scale",.5f).putExtra("logicalW",400).putExtra("logicalH",600);
    }
    private void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,1000,1800);
    }
    @Test public void oneWindowKeepsCompactInputRegionsAndSuppressesTransitionTouches() {
        var activity=Robolectric.buildActivity(Activity.class).setup();
        Context context=new ContextWrapper(activity.get()) {
            @Override public Context createWindowContext(int type, Bundle options){return this;}
        };
        WindowControls controls=new WindowControls(context);
        try {
            controls.update(frame(1,"mini",false,false));
            FrameLayout root=ReflectionHelpers.getField(controls,"controlsRoot");
            assertNotNull(root);assertEquals(6,root.getChildCount());layout(root);
            Region region=new Region();controls.fillTouchRegion(region);
            assertTrue(region.toString()+" child="+root.getChildAt(0).getLeft()+","+root.getChildAt(0).getTop()
                +","+root.getChildAt(0).getRight()+","+root.getChildAt(0).getBottom(),region.contains(150,250));
            assertFalse(region.contains(400,700));
            controls.update(frame(2,"normal",true,false));layout(root);
            controls.fillTouchRegion(region);assertTrue(region.isEmpty());
            assertSame(root,ReflectionHelpers.getField(controls,"controlsRoot"));
            controls.update(frame(3,"normal",true,true));layout(root);
            controls.fillTouchRegion(region);assertTrue(region.contains(150,250));
            controls.systemOverlay(true);layout(root);controls.fillTouchRegion(region);assertTrue(region.isEmpty());
            controls.systemOverlay(false);layout(root);controls.fillTouchRegion(region);assertFalse(region.isEmpty());
            controls.update(frame(4,"normal",false,false));layout(root);
            controls.fillTouchRegion(region);assertFalse(region.contains(150,250));
            assertSame(root,ReflectionHelpers.getField(controls,"controlsRoot"));
            controls.hide();assertNull(ReflectionHelpers.getField(controls,"controlsRoot"));
        } finally {controls.hide();activity.pause().stop().destroy();}
    }
}
