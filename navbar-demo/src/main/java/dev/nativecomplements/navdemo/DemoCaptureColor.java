package dev.nativecomplements.navdemo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;

/** Explicit color-managed SDR conversion, never relabeling P3 bytes as sRGB. */
final class DemoCaptureColor {
    static Bitmap toSrgb(Bitmap source) {
        if (source == null) throw new IllegalArgumentException("bitmap_null");
        ColorSpace color = source.getColorSpace();
        if (color == null) throw new IllegalArgumentException("colorspace_unknown");
        if (color.getModel() != ColorSpace.Model.RGB) throw new IllegalArgumentException("colorspace_non_rgb: " + color.getName());
        if (color.getId() < 0) throw new IllegalArgumentException("colorspace_unregistered: " + color.getName());
        for (int channel = 0; channel < 3; channel++)
            if (color.getMinValue(channel) < 0 || color.getMaxValue(channel) > 1)
                throw new IllegalArgumentException("colorspace_extended_range: " + color.getName());
        Bitmap software = source.copy(Bitmap.Config.ARGB_8888, false);
        if (software == null) throw new IllegalStateException("software_copy_null");
        ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
        if (srgb.equals(color)) return software;
        Bitmap target = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888, true, srgb);
        try {
            new Canvas(target).drawBitmap(software, 0, 0, null);
            return target;
        } catch (RuntimeException error) {
            target.recycle();
            throw error;
        } finally { software.recycle(); }
    }
}
