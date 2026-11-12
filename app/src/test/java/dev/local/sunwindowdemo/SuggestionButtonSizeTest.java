package dev.local.sunwindowdemo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.drawable.ColorDrawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36)
public class SuggestionButtonSizeTest {
    @Test public void iconsAndContainersScaleWhileTouchTargetsStayAccessible() throws Exception {
        try(var controller=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=controller.get();
            SuggestionHost host=new SuggestionHost(activity);
            try {
                org.robolectric.util.ReflectionHelpers.setField(host,"windowContext",activity);
                org.robolectric.util.ReflectionHelpers.setField(host,"wm",activity.getSystemService(android.view.WindowManager.class));
                Bundle settings=new Bundle();
                org.robolectric.util.ReflectionHelpers.setField(host,"settings",settings);
                float density=activity.getResources().getDisplayMetrics().density;
                for(int size:new int[]{32,48,80}) {
                    settings.putInt("size",size);
                    for(String name:new String[]{"addButton","addAppButton"}) {
                        var method=SuggestionHost.class.getDeclaredMethod(name,LinearLayout.class,android.graphics.drawable.Drawable.class,String.class,Runnable.class,Runnable.class);
                        method.setAccessible(true);
                        ImageView button=(ImageView)method.invoke(host,new LinearLayout(activity),new ColorDrawable(0xff123456),"test",(Runnable)()->{},(Runnable)()->{});
                        if(name.equals("addButton")) {
                            var paint=SuggestionHost.class.getDeclaredMethod("paint",ImageView.class,int.class);
                            paint.setAccessible(true);paint.invoke(host,button,0xff00639b);
                        }
                        int touch=Math.round(Math.max(48,size)*density);
                        assertEquals(touch,button.getLayoutParams().width);
                        assertEquals(touch,button.getLayoutParams().height);
                        float ratio=name.equals("addButton")?.625f:.75f;
                        assertEquals(Math.round(size*ratio*density),touch-button.getPaddingLeft()-button.getPaddingRight(),1);
                        assertNotNull(button.getBackground());
                        button.getBackground().setBounds(0,0,touch,touch);
                        android.graphics.Rect padding=new android.graphics.Rect();
                        button.getBackground().getPadding(padding);
                        assertEquals(Math.round(size*density),touch-padding.left-padding.right,1);
                    }
                }
            } finally {host.close();}
        }
    }
}
