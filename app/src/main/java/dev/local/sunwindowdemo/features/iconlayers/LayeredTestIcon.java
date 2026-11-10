package dev.local.sunwindowdemo.features.iconlayers;

import android.content.res.Resources;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import dev.local.sunwindowdemo.R;

/** Keep both layers real Drawables, including copies used by FloatingIconView. */
public final class LayeredTestIcon extends AdaptiveIconDrawable {
    private final Resources resources;
    private final int mode;

    public LayeredTestIcon(Resources resources, int mode) {
        super(new ColorDrawable(mode == 2 ? 0xFF9EE9D9 : 0xFFFCE4EC),
            resources.getDrawable(mode == 2 ? R.drawable.icon_lab_foreground_shifted : R.drawable.icon_lab_foreground, null).mutate(),
            resources.getDrawable(R.drawable.icon_lab_foreground, null).mutate());
        this.resources = resources;
        this.mode = mode;
    }

    @Override public ConstantState getConstantState() {
        return new ConstantState() {
            @Override public Drawable newDrawable() { return new LayeredTestIcon(resources, mode); }
            @Override public Drawable newDrawable(Resources ignored) { return newDrawable(); }
            @Override public int getChangingConfigurations() { return 0; }
        };
    }
}
