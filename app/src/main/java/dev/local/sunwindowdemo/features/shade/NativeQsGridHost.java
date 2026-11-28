package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import dev.local.sunwindowdemo.core.HostSnapshot;
import io.github.libxposed.api.XposedInterface;
import java.util.function.Consumer;

public final class NativeQsGridHost {
    private static final QsGridPreferences PREFERENCES = new QsGridPreferences();
    private static RoundQsTiles round;

    private NativeQsGridHost() {}

    public static void install(XposedInterface module, ClassLoader loader, Consumer<String> note) {
        if (Build.VERSION.SDK_INT != 37) return;
        try {
            var create = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            module.hook(create).setPriority(100).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var app = (Application) chain.getArg(0);
                if (round == null && ShadeStore.SYSTEM_UI.equals(app.getPackageName()) && PixelOsShade.supports(app)) {
                    PREFERENCES.load(HostSnapshot.open(app, "shade").cached());
                    var grid = new NativeQsGrid(module, PREFERENCES);
                    grid.install(app, loader);
                    grid.installLabels(loader);
                    round = new RoundQsTiles(module);
                    round.load(app);
                    round.install(loader);
                    round.setEnabled(PREFERENCES.get(QsGridPreferences.Option.ROUND_TILES) != 0);
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            note.accept("NATIVE_QS_GRID_UNAVAILABLE " + error.getClass().getSimpleName());
        }
    }

    static void update(Bundle config) {
        if (round == null) return;
        PREFERENCES.load(config);
        round.setEnabled(PREFERENCES.get(QsGridPreferences.Option.ROUND_TILES) != 0);
    }
}
