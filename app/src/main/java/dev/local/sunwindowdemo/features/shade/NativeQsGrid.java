package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import io.github.libxposed.api.XposedInterface;
import static dev.local.sunwindowdemo.features.shade.QsGridPreferences.Option.*;

/** Supplies the grid dimensions to the existing SystemUI repositories. */
final class NativeQsGrid {
    private final XposedInterface module;
    private final QsGridPreferences preferences;
    private final ThreadLocal<String> secondaryLabel = new ThreadLocal<>();

    NativeQsGrid(XposedInterface module, QsGridPreferences preferences) {
        this.module = module;
        this.preferences = preferences;
    }

    @SuppressWarnings("DiscouragedApi")
    void install(Context context, ClassLoader loader) {
        var resources = context.getResources();
        var systemUi = ShadeStore.SYSTEM_UI;
        int columns = resources.getIdentifier("quick_settings_infinite_grid_num_columns", "integer", systemUi);
        int splitColumns = resources.getIdentifier("quick_settings_split_shade_num_columns", "integer", systemUi);
        int rows = resources.getIdentifier("quick_settings_paginated_grid_num_rows", "integer", systemUi);
        int compactRows = resources.getIdentifier("quick_qs_paginated_grid_num_rows", "integer", systemUi);
        if (columns == 0 || rows == 0 || compactRows == 0) return;
        try {
            var getInteger = Resources.class.getMethod("getInteger", int.class);
            module.hook(getInteger).setPriority(100).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int id = (Integer) chain.getArg(0);
                if (id != columns && id != rows && id != compactRows && (splitColumns == 0 || id != splitColumns)) {
                    return chain.proceed();
                }
                var caller = (Resources) chain.getThisObject();
                if (!systemUi.equals(caller.getResourcePackageName(id))) return chain.proceed();
                int value = id == rows ? value(caller, PORTRAIT_ROWS, LANDSCAPE_ROWS)
                        : id == compactRows ? value(caller, PORTRAIT_COMPACT_ROWS, LANDSCAPE_COMPACT_ROWS)
                        : value(caller, PORTRAIT_COLUMNS, LANDSCAPE_COLUMNS);
                return value > 0 ? value : chain.proceed();
            });
            Log.i("PixelOSControlCenter", "Native Quick Settings grid settings registered");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i("PixelOSControlCenter", "Native Quick Settings grid unavailable", error);
        }
        installGridRepositories(loader, resources);
    }

    private int value(Resources resources, QsGridPreferences.Option portrait, QsGridPreferences.Option landscape) {
        return preferences.get(resources.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? landscape : portrait);
    }

    /** Also handles builds where ART inlines resource reads into the grid configuration flows. */
    private void installGridRepositories(ClassLoader loader, Resources resources) {
        var prefix = ShadeStore.SYSTEM_UI + ".qs.panels.data.repository.";
        try {
            var columns = loader.loadClass(prefix + "QSColumnsRepository$$ExternalSyntheticLambda0")
                    .getDeclaredMethod("invoke", Object.class);
            module.hook(columns).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                int count = value(resources, PORTRAIT_COLUMNS, LANDSCAPE_COLUMNS);
                return count > 0 && result instanceof Integer ? count : result;
            });
            var collector = loader.loadClass("kotlinx.coroutines.flow.FlowCollector");
            var continuation = loader.loadClass("kotlin.coroutines.Continuation");
            var rows = loader.loadClass(prefix + "QuickQuickSettingsRowRepository$special$$inlined$map$1")
                    .getDeclaredMethod("collect", collector, continuation);
            module.hook(rows).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var downstream = chain.getArg(0);
                var adjusted = Proxy.newProxyInstance(loader, new Class<?>[] {collector}, (instance, method, args) -> {
                    if (method.getName().equals("emit")) {
                        int count = value(resources, PORTRAIT_COMPACT_ROWS, LANDSCAPE_COMPACT_ROWS);
                        if (count > 0 && args[0] instanceof Integer) args[0] = count;
                    }
                    try { return method.invoke(downstream, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
                return chain.proceed(new Object[] {adjusted, chain.getArg(1)});
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.d("PixelOSControlCenter", "Additional Quick Settings grid hooks unavailable", error);
        }
    }

    void installLabels(ClassLoader loader) {
        try {
            var tiles = loader.loadClass(ShadeStore.SYSTEM_UI + ".qs.panels.ui.compose.infinitegrid.CommonTileKt");
            Method labels = Arrays.stream(tiles.getDeclaredMethods()).filter(method -> method.getName().equals("LargeTileLabels")
                    && method.getParameterCount() == 9 && method.getParameterTypes()[1] == String.class)
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            Method label = Arrays.stream(tiles.getDeclaredMethods()).filter(method -> method.getName().equals("TileLabel")
                    && method.getParameterCount() == 8).findFirst().orElseThrow(NoSuchMethodException::new);
            var style = label.getParameterTypes()[2];
            var span = style.getDeclaredField("spanStyle");
            span.setAccessible(true);
            var fontSize = span.getType().getDeclaredField("fontSize");
            fontSize.setAccessible(true);
            Method copyStyle = Arrays.stream(style.getDeclaredMethods()).filter(method -> {
                var types = method.getParameterTypes();
                return method.getName().startsWith("copy-") && method.getName().endsWith("$default")
                        && types.length == 11 && types[0] == style && types[1] == long.class && types[2] == long.class;
            }).findFirst().orElseThrow(NoSuchMethodException::new);
            copyStyle.setAccessible(true);

            module.hook(labels).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                secondaryLabel.set((String) chain.getArg(1));
                try { return chain.proceed(); }
                finally { secondaryLabel.remove(); }
            });
            module.hook(label).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int scale = preferences.get(Objects.equals(secondaryLabel.get(), chain.getArg(0))
                        ? SECONDARY_LABEL_SIZE : LABEL_SIZE);
                if (scale == 100) return chain.proceed();
                var original = chain.getArg(2);
                long encoded = fontSize.getLong(span.get(original));
                float size = Float.intBitsToFloat((int) encoded);
                if ((encoded >>> 32) == 0 || !Float.isFinite(size) || size <= 0) return chain.proceed();
                long resized = (encoded & 0xffffffff00000000L)
                        | Integer.toUnsignedLong(Float.floatToRawIntBits(size * scale / 100f));
                var args = chain.getArgs().toArray();
                args[2] = copyStyle.invoke(null, original, 0L, resized, null, null, 0L, 0, 0L, null, 0, ~2);
                return chain.proceed(args);
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i("PixelOSControlCenter", "Native Quick Settings label sizing unavailable", error);
        }
    }
}
