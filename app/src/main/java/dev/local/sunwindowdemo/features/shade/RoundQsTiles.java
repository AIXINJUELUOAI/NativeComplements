package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps stock tile layout and animation while centering single-cell circular tiles. */
final class RoundQsTiles {
    private static final String TAG = "PixelOSControlCenter";
    private static final String SYSTEM_UI = ShadeStore.SYSTEM_UI;
    private final XposedInterface module;

    RoundQsTiles(XposedInterface module) { this.module = module; }
    void setEnabled(boolean value) { enabled = value; }
    private static final String TILE_PACKAGE = SYSTEM_UI + ".qs.panels.ui.compose.infinitegrid.";
    private static final int TILE_ICON_ONLY = 2;
    private static final int TILE_MODIFIER = 5;
    private final ThreadLocal<Boolean> roundShape = new ThreadLocal<>();
    private final Map<Object, Object> originalModifiers = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean enabled;
    private float diameterDp = 72f;

    @SuppressWarnings("DiscouragedApi")
    void load(Context context) {
        var resources = context.getResources();
        int id = resources.getIdentifier("common_tile_default_tile_height", "dimen", SYSTEM_UI);
        if (id != 0) diameterDp = resources.getDimension(id) / resources.getDisplayMetrics().density;
    }

    void install(ClassLoader loader) {
        try {
            var tiles = loader.loadClass(TILE_PACKAGE + "TileKt");
            var defaults = loader.loadClass(TILE_PACKAGE + "TileDefaults");
            var composer = loader.loadClass("androidx.compose.runtime.Composer");
            var modifier = loader.loadClass("androidx.compose.ui.Modifier");
            var emptyModifier = modifier.getField("Companion").get(null);
            var size = loader.loadClass("androidx.compose.foundation.layout.SizeKt");
            var center = size.getField("WrapContentSizeCenter").get(null);
            var then = modifier.getMethod("then", modifier);
            var heightIn = findMangled(size, "heightIn-", modifier, float.class, float.class);
            var ratio = loader.loadClass("androidx.compose.foundation.layout.AspectRatioKt")
                    .getMethod("aspectRatio", modifier, float.class, boolean.class);
            var animateShape = findMangled(defaults, "animateShapeAsState-", float.class, String.class, composer, int.class);
            var tile = Arrays.stream(tiles.getDeclaredMethods()).filter(method -> {
                var parameters = method.getParameterTypes();
                return method.getName().equals("Tile") && parameters.length == 15
                        && parameters[TILE_ICON_ONLY] == boolean.class
                        && parameters[11] == composer;
            }).findFirst().orElseThrow(NoSuchMethodException::new);
            var tileExpandable = Arrays.stream(tiles.getDeclaredMethods()).filter(method -> {
                var parameters = method.getParameterTypes();
                return method.getName().equals("TileExpandable") && parameters.length == 9
                        && parameters[TILE_MODIFIER] == modifier && parameters[7] == composer;
            }).findFirst().orElseThrow(NoSuchMethodException::new);

            module.hook(animateShape).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!Boolean.TRUE.equals(roundShape.get()) || !"QSTileCornerRadius".equals(chain.getArg(1))) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[] {diameterDp / 2f, chain.getArg(1), chain.getArg(2), chain.getArg(3)});
            });
            module.hook(tile).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || !Boolean.TRUE.equals(chain.getArg(TILE_ICON_ONLY))) return chain.proceed();
                roundShape.set(true);
                try { return chain.proceed(); }
                finally { roundShape.remove(); }
            });
            module.hook(tileExpandable).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var supplied = chain.getArg(TILE_MODIFIER);
                var original = supplied == null ? emptyModifier : originalModifiers.getOrDefault(supplied, supplied);
                if (!enabled && (supplied == null || original == supplied)) return chain.proceed();
                if (enabled && !Boolean.TRUE.equals(roundShape.get())) return chain.proceed();
                var args = chain.getArgs().toArray();
                args[TILE_MODIFIER] = original;
                if (!enabled) return chain.proceed(args);

                // Reserve the native row height before centering the square constrained by the column width.
                var cell = heightIn.invoke(null, original, diameterDp, diameterDp);
                var square = ratio.invoke(null, then.invoke(cell, center), 1f, false);
                originalModifiers.put(square, original);
                args[TILE_MODIFIER] = square;
                return chain.proceed(args);
            });
            Log.i(TAG, "Native circular quick settings tiles registered");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i(TAG, "Native tile shape unavailable", error);
        }
    }

    private Method findMangled(Class<?> owner, String prefix, Class<?>... parameters) throws NoSuchMethodException {
        for (var method : owner.getDeclaredMethods()) {
            if (method.getName().startsWith(prefix) && Arrays.equals(method.getParameterTypes(), parameters)) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + prefix);
    }
}
