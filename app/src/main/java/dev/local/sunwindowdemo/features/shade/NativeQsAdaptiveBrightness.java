package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Places a compact button at the end of the native brightness slider's Box. */
final class NativeQsAdaptiveBrightness {
    private static final int TRACK_MODIFIER = 9;
    private static final int COMPOSER = 14;
    private static final float BUTTON_GAP_DP = 8f;
    private static final int BUTTON_GROUP = 0x545c3186;

    private final QsSliderPreferences preferences;
    private final ThreadLocal<Boolean> inPanel = new ThreadLocal<>();
    private final Map<Object, Object> trackModifiers = new WeakHashMap<>();
    private final Map<Object, WeakReference<Object>> paddedModifiers = new WeakHashMap<>();
    private final Map<Object, Boolean> mirrors = new WeakHashMap<>();
    private boolean available;

    NativeQsAdaptiveBrightness(QsSliderPreferences preferences) { this.preferences = preferences; }

    void install(XposedInterface module, ClassLoader loader, Class<?> composer, Class<?> modifier,
                 Object emptyModifier, Method container, Supplier<Object> panelModel) {
        if (container == null) return;
        try {
            var function1 = loader.loadClass("kotlin.jvm.functions.Function1");
            var function2 = loader.loadClass("kotlin.jvm.functions.Function2");
            var brightness = loader.loadClass(ShadeStore.SYSTEM_UI + ".brightness.ui.compose.BrightnessSliderKt");
            var slider = Arrays.stream(brightness.getDeclaredMethods()).filter(method -> {
                var args = method.getParameterTypes();
                return method.getName().equals("BrightnessSlider") && args.length == 18
                        && args[TRACK_MODIFIER] == modifier && args[COMPOSER] == composer;
            }).findFirst().orElseThrow(NoSuchMethodException::new);
            var scope = loader.loadClass("androidx.compose.foundation.layout.BoxScopeInstance");
            var box = scope.getField("INSTANCE").get(null);
            var alignment = loader.loadClass("androidx.compose.ui.Alignment");
            var end = loader.loadClass("androidx.compose.ui.Alignment$Companion").getField("CenterEnd").get(null);
            var size = loader.loadClass("androidx.compose.foundation.layout.SizeKt")
                    .getMethod("size-3ABfNKs", modifier, float.class);
            var align = scope.getMethod("align", modifier, alignment);
            var buttonModifier = align.invoke(box, size.invoke(null, emptyModifier, QsAdaptiveBrightnessButton.SIZE_DP), end);
            var padding = loader.loadClass("androidx.compose.foundation.layout.PaddingKt")
                    .getMethod("padding-qDBjuR0", modifier, float.class, float.class, float.class, float.class);
            var reserveSpace = padding.invoke(null, emptyModifier, 0f, 0f, QsAdaptiveBrightnessButton.SIZE_DP + BUTTON_GAP_DP, 0f);
            var then = modifier.getMethod("then", modifier);
            var fold = modifier.getMethod("foldIn", Object.class, function2);
            var hasOverlay = NativeQsVolumeSlider.proxy(function2, (ignored, method, args) -> {
                if (Boolean.TRUE.equals(args[0])) return true;
                var name = args[1].getClass().getName();
                return name.startsWith("com.android.compose.ui.graphics.") && (name.contains("DrawInOverlay")
                        || name.contains("DrawInContainer") || name.endsWith(".ContainerElement"));
            });
            var interop = loader.loadClass("androidx.compose.ui.viewinterop.AndroidView_androidKt")
                    .getMethod("AndroidView", function1, modifier, function1, composer, int.class, int.class);
            var unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            var factory = NativeQsVolumeSlider.proxy(function1, (ignored, method, args) -> new QsAdaptiveBrightnessButton((Context) args[0]));
            var update = NativeQsVolumeSlider.proxy(function1, (ignored, method, args) -> unit);
            var begin = composer.getMethod("startReplaceGroup", int.class);
            var endGroup = composer.getMethod("endReplaceGroup");

            module.hook(container).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (chain.getArg(0) != panelModel.get()) return chain.proceed();
                inPanel.set(true);
                try { return chain.proceed(); }
                finally { inPanel.remove(); }
            });
            module.hook(slider).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!Boolean.TRUE.equals(inPanel.get())) return chain.proceed();
                var incoming = chain.getArg(TRACK_MODIFIER);
                var original = trackModifiers.getOrDefault(incoming, incoming);
                if (original == null) return chain.proceed();

                boolean show = preferences.get(QsSliderPreferences.Option.ADAPTIVE_BRIGHTNESS_SHORTCUT) != 0;
                boolean renderButton = show && !isMirror(fold, original, hasOverlay);
                var currentComposer = chain.getArg(COMPOSER);
                var modified = show ? padded(then, reserveSpace, original) : original;
                Object result;
                if (modified == incoming) {
                    result = chain.proceed();
                } else {
                    var args = chain.getArgs().toArray();
                    args[TRACK_MODIFIER] = modified;
                    result = chain.proceed(args);
                }
                begin.invoke(currentComposer, BUTTON_GROUP);
                try {
                    if (renderButton) interop.invoke(null, factory, buttonModifier, update, currentComposer, 0, 0);
                } finally { endGroup.invoke(currentComposer); }
                return result;
            });
            module.deoptimize(container);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i("PixelOSControlCenter", "Adaptive brightness shortcut unavailable", error);
        }
    }

    boolean available() { return available; }

    private boolean isMirror(Method fold, Object original, Object operation) throws ReflectiveOperationException {
        var cached = mirrors.get(original);
        if (cached != null) return cached;
        boolean mirrored = Boolean.TRUE.equals(fold.invoke(original, false, operation));
        mirrors.put(original, mirrored);
        return mirrored;
    }

    private Object padded(Method then, Object reserveSpace, Object original) throws ReflectiveOperationException {
        var reference = paddedModifiers.get(original);
        var result = reference == null ? null : reference.get();
        if (result != null) return result;
        result = then.invoke(reserveSpace, original);
        paddedModifiers.put(original, new WeakReference<>(result));
        trackModifiers.put(result, original);
        return result;
    }
}
