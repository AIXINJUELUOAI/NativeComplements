package dev.local.sunwindowdemo.features.shade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import static dev.local.sunwindowdemo.features.shade.QsSliderPreferences.Option.*;

/** Hosts the native sliders in the compact and expanded Quick Settings layouts. */
final class NativeQsVolumeSlider {
    private static final String TAG = "PixelOSControlCenter";
    private static final String SYSUI = ShadeStore.SYSTEM_UI;
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String SLIDER_TAG = "pixelos_qs_media_volume";
    private static final int MEDIA = AudioManager.STREAM_MUSIC;

    private final XposedInterface module;
    private final QsSliderPreferences preferences;
    private final NativeQsAdaptiveBrightness adaptiveBrightness;
    private final ThreadLocal<Boolean> hideHeading = new ThreadLocal<>();
    private final ThreadLocal<Boolean> renderingBrightness = new ThreadLocal<>();
    private Method stateGet, stateSet, stateOf, function2Invoke, composableLambda;
    private Method drawSlider, platformColors, materialColors, startGroup, endGroup;
    private Class<?> function0, function1, function2, function3, sliderState;
    private Method floatRange;
    private Constructor<?> loadedIcon;
    private Object unit, sliderModifier, dimensions, compositionState, volumeIcon, mutedIcon;
    private Object changeVolume, noOp, lastWrappedBrightness;
    private Method drawBrightness, colorResource;
    private Constructor<?> brightnessColors;
    private Field brightnessModelField;
    private Object brightnessModel, compactBrightness, volumeContent;
    private Object brightnessPalette;
    private Object lastCompactTiles, wrappedCompactTiles;
    private Object compactScope, expandedScope, emptyModifier, brightnessElement, volumeElement;
    private Method drawSceneElement;
    private SliderContents compactSliders, expandedSliders;
    private int scrimColorId, revision;
    private long transparentColor;
    private AudioManager audio;
    private boolean installed;
    private String label;

    private record State(boolean enabled, int min, int max, int volume, int revision) { }
    private record SliderContents(Object scope, Object source, Object brightness, Object volume) { }

    NativeQsVolumeSlider(XposedInterface module, QsSliderPreferences preferences) {
        this.module = module;
        this.preferences = preferences;
        adaptiveBrightness = new NativeQsAdaptiveBrightness(preferences);
    }

    void install(ClassLoader loader) {
        try {
            var composer = loader.loadClass("androidx.compose.runtime.Composer");
            var modifier = loader.loadClass("androidx.compose.ui.Modifier");
            emptyModifier = modifier.getField("Companion").get(null);
            sliderModifier = loader.loadClass("androidx.compose.foundation.layout.SizeKt")
                    .getMethod("fillMaxWidth", modifier, float.class).invoke(null, emptyModifier, 1f);
            var mutableState = loader.loadClass("androidx.compose.runtime.MutableState");
            stateGet = mutableState.getMethod("getValue");
            stateSet = mutableState.getMethod("setValue", Object.class);
            stateOf = loader.loadClass("androidx.compose.runtime.SnapshotStateKt")
                    .getMethod("mutableStateOf$default", Object.class);
            startGroup = composer.getMethod("startReplaceGroup", int.class);
            endGroup = composer.getMethod("endReplaceGroup");
            unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            function0 = loader.loadClass("kotlin.jvm.functions.Function0");
            function1 = loader.loadClass("kotlin.jvm.functions.Function1");
            function2 = loader.loadClass("kotlin.jvm.functions.Function2");
            function3 = loader.loadClass("kotlin.jvm.functions.Function3");
            function2Invoke = function2.getMethod("invoke", Object.class, Object.class);
            composableLambda = loader.loadClass("androidx.compose.runtime.internal.ComposableLambdaKt")
                    .getMethod("composableLambdaInstance", int.class, boolean.class, Object.class);
            var fragment = loader.loadClass(SYSUI + ".qs.composefragment.QSFragmentComposeKt");
            var layout = fragment.getDeclaredMethod("QuickSettingsLayout", function2, function2, function2,
                    boolean.class, composer, int.class);
            var pairedLayout = fragment.getMethod("QuickQuickSettingsLayout", function2, function2,
                    boolean.class, composer, int.class);
            var panel = SYSUI + ".volume.panel.component.volume.";
            sliderState = loader.loadClass(panel + "slider.ui.viewmodel.SliderState");
            var slider = loader.loadClass(panel + "ui.composable.VolumeSliderKt");
            drawSlider = Arrays.stream(slider.getDeclaredMethods()).filter(method -> {
                var types = method.getParameterTypes();
                return method.getName().equals("VolumeSlider") && types.length == 14
                        && types[0] == sliderState && types[4] == modifier && types[11] == composer;
            }).findFirst().orElseThrow(NoSuchMethodException::new);
            dimensions = drawSlider.getParameterTypes()[9].getField("Defaults").get(null);
            platformColors = loader.loadClass("com.android.compose.PlatformSliderDefaults")
                    .getMethod("defaultPlatformSliderColors", composer);
            materialColors = loader.loadClass("androidx.compose.material3.SliderDefaults").getMethod("colors", composer);
            floatRange = loader.loadClass("kotlin.ranges.RangesKt").getMethod("rangeTo", float.class, float.class);
            floatRange.setAccessible(true);
            loadedIcon = loader.loadClass(SYSUI + ".common.shared.model.Icon$Loaded")
                    .getDeclaredConstructor(Drawable.class);
            loadedIcon.setAccessible(true);
            var text = Arrays.stream(loader.loadClass("androidx.compose.material3.TextKt").getDeclaredMethods())
                    .filter(method -> {
                        var types = method.getParameterTypes();
                        return method.getName().startsWith("Text-") && types.length == 17
                                && types[0] == String.class && types[1] == modifier && types[13] == composer;
                    }).findFirst().orElseThrow(NoSuchMethodException::new);
            volumeContent = composableLambda.invoke(null, 0x65932104, true, kotlinFunction(function2, args -> {
                render(args[0]);
                return unit;
            }));
            captureBrightnessPalette(loader, composer, drawSlider.getParameterTypes()[10]);
            installSceneElements(loader, composer, modifier);
            installCompactBrightness(loader, composer, modifier);
            adaptiveBrightness.install(module, loader, composer, modifier, emptyModifier, drawBrightness, () -> brightnessModel);

            module.hook(text).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
                    Boolean.TRUE.equals(hideHeading.get()) && SLIDER_TAG.equals(chain.getArg(0)) ? null : chain.proceed());
            module.hook(drawSlider).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (changeVolume == null || chain.getArg(1) != changeVolume) return chain.proceed();
                hideHeading.set(true);
                try { return chain.proceed(); }
                finally { hideHeading.remove(); }
            });

            module.hook(layout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!available()) return chain.proceed();
                var args = chain.getArgs().toArray();
                if (!enabled() && preferences.get(BRIGHTNESS_VISIBILITY) == 0
                        && preferences.get(ADAPTIVE_BRIGHTNESS_SHORTCUT) == 0) {
                    if (args[0] != lastWrappedBrightness || expandedSliders == null) return chain.proceed();
                    args[0] = expandedSliders.source();
                    args[5] = 0;
                    return chain.proceed(args);
                }
                var original = expandedSliders != null && args[0] == lastWrappedBrightness
                        ? expandedSliders.source() : args[0];
                var sliders = bindSliders(expandedSliders, expandedScope, original);
                if (sliders != expandedSliders) {
                    var body = kotlinFunction(function2, content -> {
                        var state = current();
                        drawSliders(pairedLayout, content[0], sliders, preferences.get(BRIGHTNESS_VISIBILITY) != 2,
                                state.enabled() && state.max() > state.min());
                        return unit;
                    });
                    lastWrappedBrightness = call(composableLambda, null, 0x56a47e12, true, body);
                    expandedSliders = sliders;
                }
                args[0] = lastWrappedBrightness;
                args[5] = 0;
                return chain.proceed(args);
            });
            module.hook(pairedLayout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!available() || isVolumeContent(chain.getArg(1))) return chain.proceed();
                var original = chain.getArg(0);
                if (original == wrappedCompactTiles) original = lastCompactTiles;
                final var tiles = original;
                boolean includeBrightness = preferences.get(BRIGHTNESS_VISIBILITY) == 1 && supportsCompactBrightness();
                boolean includeVolume = preferences.get(VOLUME_COMPACT) != 0 && enabled();
                var args = chain.getArgs().toArray();
                if (!includeBrightness && !includeVolume) {
                    if (args[0] != wrappedCompactTiles) return chain.proceed();
                    args[0] = lastCompactTiles;
                    args[4] = 0;
                    return chain.proceed(args);
                }
                var sliders = bindSliders(compactSliders, compactScope, compactBrightness);
                if (tiles != lastCompactTiles || sliders != compactSliders) {
                    wrappedCompactTiles = call(composableLambda, null, 0x5e1f4c18, true,
                            kotlinFunction(function2, content -> {
                                var state = current();
                                drawSliders(pairedLayout, content[0], sliders,
                                        preferences.get(BRIGHTNESS_VISIBILITY) == 1 && supportsCompactBrightness() && brightnessModel != null,
                                        preferences.get(VOLUME_COMPACT) != 0 && state.enabled() && state.max() > state.min());
                                return call(function2Invoke, tiles, content[0], 0);
                            }));
                    lastCompactTiles = tiles;
                    compactSliders = sliders;
                }
                args[0] = wrappedCompactTiles;
                if (includeBrightness || includeVolume) args[2] = false;
                args[4] = 0;
                return chain.proceed(args);
            });
            installed = true;
            Log.i(TAG, "Native Quick Settings volume slider registered");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i(TAG, "Native Quick Settings volume slider unavailable", error);
        }
    }

    private void installSceneElements(ClassLoader loader, Class<?> composer, Class<?> modifier) {
        try {
            var scenePackage = "com.android.compose.animation.scene.";
            var key = loader.loadClass(scenePackage + "ElementKey");
            var createKey = key.getDeclaredConstructor(String.class, Object.class,
                    loader.loadClass(scenePackage + "ElementContentPicker"), boolean.class, int.class,
                    loader.loadClass("kotlin.jvm.internal.DefaultConstructorMarker"));
            brightnessElement = createKey.newInstance("PixelOSControlsBrightnessSlider", null, null, false, 14, null);
            volumeElement = createKey.newInstance("PixelOSControlsMediaVolumeSlider", null, null, false, 14, null);
            drawSceneElement = loader.loadClass(scenePackage + "BaseContentScope")
                    .getMethod("Element", key, modifier, function3, composer, int.class);
            var scope = loader.loadClass(scenePackage + "ContentScope");
            var fragment = loader.loadClass(SYSUI + ".qs.composefragment.QSFragmentCompose");
            var compact = fragment.getDeclaredMethod("QuickQuickSettingsElement", scope, modifier, composer, int.class);
            var expanded = fragment.getDeclaredMethod("QuickSettingsElement", scope, modifier, composer, int.class);
            module.hook(compact).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                compactScope = chain.getArg(0);
                return chain.proceed();
            });
            module.hook(expanded).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                expandedScope = chain.getArg(0);
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            drawSceneElement = null;
            Log.i(TAG, "Shared Quick Settings slider elements unavailable", error);
        }
    }

    private SliderContents bindSliders(SliderContents previous, Object scope, Object brightness) throws Throwable {
        if (previous != null && previous.scope() == scope && previous.source() == brightness) return previous;
        return new SliderContents(scope, brightness, sharedSlider(scope, brightnessElement, 0x262f0930, brightness),
                sharedSlider(scope, volumeElement, 0x262f0932, volumeContent));
    }

    private Object sharedSlider(Object scope, Object element, int composerKey, Object content) throws Throwable {
        if (drawSceneElement == null || scope == null || content == null) return content;
        var body = call(composableLambda, null, composerKey + 1, true,
                kotlinFunction(function3, args -> call(function2Invoke, content, args[1], 0)));
        return call(composableLambda, null, composerKey, true, kotlinFunction(function2, args -> {
            call(drawSceneElement, scope, element, emptyModifier, body, args[0], 0);
            return unit;
        }));
    }

    private boolean isVolumeContent(Object content) {
        return content == volumeContent || (compactSliders != null && content == compactSliders.volume())
                || (expandedSliders != null && content == expandedSliders.volume());
    }

    private void captureBrightnessPalette(ClassLoader loader, Class<?> composer, Class<?> colors) {
        try {
            var brightness = loader.loadClass(SYSUI + ".brightness.ui.compose.BrightnessSliderKt");
            var brightnessSlider = Arrays.stream(brightness.getDeclaredMethods()).filter(method -> {
                var types = method.getParameterTypes();
                return method.getName().equals("BrightnessSlider") && types.length == 18 && types[14] == composer;
            }).findFirst().orElseThrow(NoSuchMethodException::new);
            var slider = Arrays.stream(loader.loadClass("androidx.compose.material3.SliderKt").getDeclaredMethods())
                    .filter(method -> {
                        var types = method.getParameterTypes();
                        return method.getName().equals("Slider") && types.length == 14
                                && types[0] == float.class && types[5] == colors && types[10] == composer;
                    }).findFirst().orElseThrow(NoSuchMethodException::new);
            module.hook(slider).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (Boolean.TRUE.equals(renderingBrightness.get())) brightnessPalette = chain.getArg(5);
                return chain.proceed();
            });
            module.hook(brightnessSlider).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                renderingBrightness.set(true);
                try { return chain.proceed(); }
                finally { renderingBrightness.remove(); }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i(TAG, "Native brightness slider palette unavailable", error);
        }
    }

    private void drawSliders(Method row, Object composer, SliderContents sliders, boolean showBrightness,
                             boolean showVolume) throws Throwable {
        call(startGroup, composer, 0x759d3e19);
        try {
            if (showBrightness && showVolume && preferences.get(SLIDER_LAYOUT) == 0) {
                call(row, null, sliders.brightness(), sliders.volume(), true, composer, 0);
            } else {
                if (showBrightness) call(function2Invoke, sliders.brightness(), composer, 0);
                if (showVolume) call(function2Invoke, sliders.volume(), composer, 0);
            }
        } finally { call(endGroup, composer); }
    }

    private void installCompactBrightness(ClassLoader loader, Class<?> composer, Class<?> modifier) {
        try {
            var container = loader.loadClass(SYSUI + ".qs.ui.viewmodel.QuickSettingsContainerViewModel");
            brightnessModelField = container.getDeclaredField("brightnessSliderViewModel");
            brightnessModelField.setAccessible(true);
            var prefix = SYSUI + ".brightness.ui.compose.";
            var colors = loader.loadClass(prefix + "ContainerColors");
            brightnessColors = colors.getDeclaredConstructor(long.class, long.class);
            brightnessColors.setAccessible(true);
            drawBrightness = loader.loadClass(prefix + "BrightnessSliderKt").getDeclaredMethod("BrightnessSliderContainer",
                    brightnessModelField.getType(), modifier, colors, loader.loadClass(prefix + "BrightnessSliderDimensions"),
                    composer, int.class, int.class);
            colorResource = loader.loadClass("androidx.compose.ui.res.ColorResources_androidKt")
                    .getMethod("colorResource", composer, int.class);
            transparentColor = loader.loadClass("androidx.compose.ui.graphics.Color").getField("Transparent").getLong(null);
            compactBrightness = composableLambda.invoke(null, 0x5fdc6e11, true,
                    kotlinFunction(function2, args -> {
                        if (brightnessModel != null && scrimColorId != 0) {
                            var palette = brightnessColors.newInstance(transparentColor, call(colorResource, null, args[0], scrimColorId));
                            call(drawBrightness, null, brightnessModel, sliderModifier, palette, null, args[0], 0, 8);
                        }
                        return unit;
                    }));
            var onActivated = container.getDeclaredMethod("onActivated", loader.loadClass("kotlin.coroutines.Continuation"));
            module.hook(onActivated).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var model = brightnessModelField.get(chain.getThisObject());
                if (model != null && model != brightnessModel) {
                    brightnessModel = model;
                    configurationChanged();
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            compactBrightness = null;
            Log.i(TAG, "Compact brightness slider unavailable", error);
        }
    }

    @SuppressWarnings("DiscouragedApi")
    void initialize(Context context, boolean enabled) {
        if (!installed || available()) return;
        try {
            audio = context.getSystemService(AudioManager.class);
            if (audio == null) return;
            var locale = context.getResources().getConfiguration().getLocales().get(0);
            label = Locale.CHINESE.getLanguage().equals(locale.getLanguage()) ? "媒体音量" : "Media volume";
            var resources = context.getResources();
            scrimColorId = resources.getIdentifier("shade_panel_fallback", "color", SYSUI);
            int icon = resources.getIdentifier("ic_volume_media", "drawable", SYSUI);
            int muted = resources.getIdentifier("ic_volume_media_mute", "drawable", SYSUI);
            volumeIcon = loadedIcon.newInstance(context.getDrawable(icon));
            mutedIcon = loadedIcon.newInstance(context.getDrawable(muted));
            changeVolume = kotlinFunction(function1, args -> {
                var state = current();
                int step = Math.clamp(Math.round(((Number) args[0]).floatValue()), state.min(), state.max());
                if (step != audio.getStreamVolume(MEDIA)) {
                    try { audio.setStreamVolume(MEDIA, step, 0); }
                    catch (RuntimeException error) { Log.w(TAG, "Could not change media volume", error); }
                }
                refresh(state.enabled());
                return unit;
            });
            noOp = kotlinFunction(function0, args -> unit);
            compositionState = stateOf.invoke(null, readAudio(enabled));
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context unused, Intent intent) {
                    if (intent.getIntExtra(STREAM_TYPE, -1) == MEDIA) refresh(enabled());
                }
            }, new IntentFilter(VOLUME_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refresh(enabled()); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refresh(enabled()); }
            }, new Handler(context.getMainLooper()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            compositionState = null;
            Log.w(TAG, "Could not initialize native volume slider", error);
        }
    }

    boolean available() { return compositionState != null; }
    boolean enabled() { return available() && current().enabled(); }
    void setEnabled(boolean enabled) { refresh(enabled); }
    boolean supportsCompactBrightness() {
        return available() && drawSceneElement != null && compactBrightness != null && scrimColorId != 0;
    }
    boolean supportsAdaptiveBrightness() { return available() && adaptiveBrightness.available(); }

    void configurationChanged() {
        revision++;
        refresh(enabled());
    }

    private State readAudio(boolean enabled) {
        return new State(enabled, audio.getStreamMinVolume(MEDIA), audio.getStreamMaxVolume(MEDIA),
                audio.getStreamVolume(MEDIA), revision);
    }

    private State current() {
        try { return (State) stateGet.invoke(compositionState); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private void refresh(boolean enabled) {
        if (!available()) return;
        try { stateSet.invoke(compositionState, readAudio(enabled)); }
        catch (ReflectiveOperationException | RuntimeException error) { Log.w(TAG, "Could not refresh media volume", error); }
    }

    private void render(Object composer) throws Throwable {
        var state = current();
        if (!state.enabled() || state.max() <= state.min()) return;
        var range = call(floatRange, null, (float) state.min(), (float) state.max());
        var model = proxy(sliderState, (ignored, method, args) -> switch (method.getName()) {
            case "getValue" -> (float) state.volume();
            case "getValueRange" -> range;
            case "getStep" -> 1f;
            case "getIcon" -> state.volume() == 0 ? mutedIcon : volumeIcon;
            case "getLabel" -> SLIDER_TAG;
            case "getA11yContentDescription" -> label;
            case "getA11yStateDescription" -> Math.round((state.volume() - state.min()) * 100f
                    / (state.max() - state.min())) + "%";
            case "isEnabled" -> !audio.isVolumeFixed();
            case "getDisabledMessage", "getHapticFilter" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var fallbackColors = call(materialColors, null, composer);
        call(drawSlider, null, model, changeVolume, null, call(platformColors, null, composer), sliderModifier,
                null, noOp, null, false, dimensions, brightnessPalette == null ? fallbackColors : brightnessPalette,
                composer, 0, 128);
    }

    private interface KotlinCallback { Object invoke(Object[] args) throws Throwable; }

    private Object kotlinFunction(Class<?> type, KotlinCallback body) {
        return proxy(type, (ignored, method, args) -> body.invoke(args));
    }

    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) ->
                switch (method.getName()) {
                    case "equals" -> instance == args[0];
                    case "hashCode" -> System.identityHashCode(instance);
                    case "toString" -> type.getSimpleName();
                    default -> handler.invoke(instance, method, args);
                });
    }

    private static Object call(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
