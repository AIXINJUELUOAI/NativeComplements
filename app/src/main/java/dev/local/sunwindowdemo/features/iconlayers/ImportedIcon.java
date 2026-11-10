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
    private final Bitmap background, foreground, monochrome;
    public ImportedIcon(Resources resources, Bitmap background, Bitmap foreground) {
        this(resources, background, foreground, null);
    }
    public ImportedIcon(Resources resources, Bitmap background, Bitmap foreground, Bitmap monochrome) {
        super(layer(resources, background), layer(resources, foreground), monochrome == null ? null : layer(resources, monochrome));
        this.resources = resources; this.background = background; this.foreground = foreground; this.monochrome = monochrome;
    }
    private static Drawable layer(Resources resources, Bitmap bitmap) {
        BitmapDrawable drawable = new BitmapDrawable(resources, bitmap);
        drawable.setGravity(Gravity.FILL); drawable.setFilterBitmap(true);
        return drawable;
    }
    @Override public ConstantState getConstantState() {
        return new ConstantState() {
            @Override public Drawable newDrawable() { return new ImportedIcon(resources, background, foreground, monochrome); }
            @Override public Drawable newDrawable(Resources r) { return new ImportedIcon(r, background, foreground, monochrome); }
            @Override public int getChangingConfigurations() { return 0; }
        };
    }
}
