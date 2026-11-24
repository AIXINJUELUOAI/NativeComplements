package dev.local.sunwindowdemo.features.alive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlivePhotoTest {
    @Test public void importKeepsScreenResolutionAndRenderHonorsTextureLimit() throws Exception {
        ImageDecoder.Source source = photo(1200, 2670);
        Bitmap imported = AlivePhoto.decodeImport(source);
        assertEquals(1200, imported.getWidth());
        assertEquals(2670, imported.getHeight());
        imported.recycle();

        Bitmap cropped = AlivePhoto.decodeCrop(source, AliveCrop.CENTER, 1200, 2670, 2048);
        assertEquals(2048, cropped.getHeight());
        assertEquals(1200f / 2670, (float) cropped.getWidth() / cropped.getHeight(), .001f);
        cropped.recycle();
    }

    @Test public void dragMovesTheVisibleCropAndStopsAtTheEdge() throws Exception {
        ImageDecoder.Source source = photo(1200, 600);
        AliveCrop crop = AliveCrop.CENTER.gesture(2f, .5f, .5f, .5f, -8f, 0f, 2f);
        AliveCrop.Window region = crop.window(2f, .5f);
        assertEquals(1f, region.left() + region.width(), .0001f);
        Bitmap visible = AlivePhoto.decodeCrop(source, crop, 150, 300, 4096);
        assertEquals(150, visible.getWidth());
        assertEquals(300, visible.getHeight());
        assertEquals(Color.BLUE, visible.getPixel(0, 150));
        visible.recycle();
    }

    private static ImageDecoder.Source photo(int width, int height) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.RED);
            Paint paint = new Paint();
            paint.setColor(Color.BLUE);
            new Canvas(bitmap).drawRect(width / 2f, 0, width, height, paint);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        // Robolectric's Windows native runtime supports byte buffers, but not Android file descriptors.
        return ImageDecoder.createSource(ByteBuffer.wrap(output.toByteArray()));
    }
}
