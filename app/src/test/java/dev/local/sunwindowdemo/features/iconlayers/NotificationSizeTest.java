package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NotificationSizeTest {
    @Test public void notificationUsesNativePixelDimensionsAndKeepsGlyphAspectRatio(){
        Context context=RuntimeEnvironment.getApplication();
        Bitmap source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        Paint paint=new Paint();paint.setColor(Color.WHITE);
        new Canvas(source).drawRect(40,52,88,76,paint);
        Bitmap normalized=IconImages.monochrome(source,true);
        Rect bounds=IconImages.contentBounds(normalized);
        assertTrue(bounds.width()>=59&&bounds.width()<=61);
        assertEquals(2f,(float)bounds.width()/bounds.height(),.15f);
        Drawable replacement=new BitmapDrawable(context.getResources(),normalized);
        Drawable original=new ColorDrawable(Color.WHITE){
            @Override public int getIntrinsicWidth(){return 48;}
            @Override public int getIntrinsicHeight(){return 48;}
        };
        Drawable result=IconImages.notificationSize(context,replacement,original);
        assertEquals(48,result.getIntrinsicWidth());assertEquals(48,result.getIntrinsicHeight());
        Rect rendered=IconImages.contentBounds(IconImages.render(result,48));
        assertTrue(rendered.width()>=44&&rendered.width()<=47);
        assertEquals(2f,(float)rendered.width()/rendered.height(),.2f);
        assertSame(replacement,IconImages.notificationSize(context,replacement,null));
    }
}
