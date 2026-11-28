package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;

public final class NativeQsSliders {
    private static final QsSliderPreferences PREFERENCES = new QsSliderPreferences();
    private static NativeQsVolumeSlider sliders;

    private NativeQsSliders() {}

    public static void install(XposedInterface module, ClassLoader loader) {
        if (sliders != null || Build.VERSION.SDK_INT != 37) return;
        PREFERENCES.load(null);
        sliders = new NativeQsVolumeSlider(module, PREFERENCES);
        sliders.install(loader);
    }

    public static void connect(Application app) {
        if (sliders != null && PixelOsShade.supports(app)) sliders.initialize(app, false);
    }

    static void update(Bundle settings) {
        if (sliders == null || !sliders.available() || !PREFERENCES.load(settings)) return;
        sliders.setEnabled(PREFERENCES.get(QsSliderPreferences.Option.MEDIA_SLIDER) != 0);
        sliders.configurationChanged();
    }
}
