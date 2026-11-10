package dev.local.sunwindowdemo.features.iconlayers;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;

/** Immutable bitmap pixels may be shared; native animation drawables must be independent. */
public final class ImportedIcon extends AdaptiveIconDrawable {
    private final Resources resources;
    private final Bitmap background, foreground;
    public ImportedIcon(Resources resources, Bitmap background, Bitmap foreground) {
        super(layer(resources, background), layer(resources, foreground));
        this.resources = resources; this.background = background; this.foreground = foreground;
    }
    private static Drawable layer(Resources resources, Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(resources, bitmap);
        drawable.setGravity(Gravity.FILL); drawable.setFilterBitmap(true);
        return drawable;
    }
    @Override public ConstantState getConstantState() {
        return new ConstantState() {
            @Override public Drawable newDrawable() { return new ImportedIcon(resources, background, foreground); }
            @Override public Drawable newDrawable(Resources r) { return new ImportedIcon(r, background, foreground); }
            @Override public int getChangingConfigurations() { return 0; }
        };
    }
}
