package dev.local.sunwindowdemo.features.shade;

import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Controls the native device-entry images and their background protection. */
public final class LockscreenFingerprintLayers {
    private static final LockscreenFingerprintLayers INSTANCE = new LockscreenFingerprintLayers();
    private static final String TAG = "PixelOSFingerprintLayers";
    private static final float FINGERPRINT_Z = 100f;
    static final int DEFAULT_SIZE = 100;
    static final int MIN_SIZE = 50;
    static final int MAX_SIZE = 200;

    private record EntryStyle(int foregroundAlpha, int backgroundAlpha, float z, boolean clipChildren) {}

    private final Map<ViewGroup, EntryStyle> entries = new WeakHashMap<>();
    private int entryId, backgroundId, foregroundId;
    private Field renderNodeField;
    private Method setProtectionSuppressed;
    private Object protectionSuppressed;
    private boolean available, hideBackground, hideForeground;
    private int size = DEFAULT_SIZE;

    private LockscreenFingerprintLayers() {}

    public static void install(XposedInterface module, ClassLoader loader, Consumer<String> note) {
        if (Build.VERSION.SDK_INT == 37) INSTANCE.installHooks(module, loader, note);
    }

    private void installHooks(XposedInterface module, ClassLoader loader, Consumer<String> note) {
        if (available) return;
        try {
            var entry = Class.forName(ShadeStore.SYSTEM_UI + ".keyguard.ui.view.DeviceEntryIconView", false, loader);
            if (!ViewGroup.class.isAssignableFrom(entry)) throw new NoSuchMethodException("device entry view");
            installSizeHook(module);
            for (var constructor : entry.getDeclaredConstructors()) {
                module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    var result = chain.proceed();
                    trackEntry((ViewGroup) chain.getThisObject());
                    return result;
                });
            }
            installProtection(module, loader);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            note.accept("UDFPS_APPEARANCE_UNAVAILABLE " + error.getClass().getSimpleName());
        }
    }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installSizeHook(XposedInterface module) {
        try {
            var renderNode = View.class.getDeclaredField("mRenderNode");
            renderNode.setAccessible(true);
            var draw = ImageView.class.getDeclaredMethod("onDraw", Canvas.class);
            module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (size == DEFAULT_SIZE || hideForeground) return chain.proceed();
                var image = (ImageView) chain.getThisObject();
                int id = image.getId();
                if (id != foregroundId && (id != backgroundId || hideBackground)) return chain.proceed();
                if (!(image.getParent() instanceof ViewGroup entry) || entry.getId() != entryId
                        || !entries.containsKey(entry)) return chain.proceed();

                var canvas = (Canvas) chain.getArg(0);
                int saved = canvas.save();
                try {
                    // The native vector cache uses the drawing canvas scale when choosing its resolution.
                    float scale = size / 100f;
                    canvas.scale(scale, scale, image.getWidth() / 2f, image.getHeight() / 2f);
                    return chain.proceed();
                } finally {
                    canvas.restoreToCount(saved);
                }
            });
            renderNodeField = renderNode;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i(TAG, "Fingerprint visual scaling unavailable", error);
        }
    }

    @SuppressWarnings("DiscouragedApi")
    private void trackEntry(ViewGroup entry) {
        if (entryId == 0 || foregroundId == 0 || backgroundId == 0) {
            var resources = entry.getResources();
            entryId = resources.getIdentifier("device_entry_icon_view", "id", ShadeStore.SYSTEM_UI);
            foregroundId = resources.getIdentifier("device_entry_icon_fg", "id", ShadeStore.SYSTEM_UI);
            backgroundId = resources.getIdentifier("device_entry_icon_bg", "id", ShadeStore.SYSTEM_UI);
            if (entryId == 0 || foregroundId == 0 || backgroundId == 0) return;
        }
        ImageView foreground = entry.findViewById(foregroundId);
        ImageView background = entry.findViewById(backgroundId);
        if (foreground == null || background == null || entries.containsKey(entry)) return;

        var stock = new EntryStyle(foreground.getImageAlpha(), background.getImageAlpha(), entry.getZ(), entry.getClipChildren());
        entries.put(entry, stock);
        entry.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                raiseEntry(view);
                if (size != DEFAULT_SIZE) applySize(entry, stock);
            }

            @Override public void onViewDetachedFromWindow(View view) { }
        });
        entry.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> raiseEntry(view));
        if (hideBackground || hideForeground) apply(entry, stock);
        else if (size != DEFAULT_SIZE) applySize(entry, stock);
    }

    private void raiseEntry(View entry) {
        if (hideBackground || hideForeground) entry.setZ(FINGERPRINT_Z);
    }

    private void apply(ViewGroup entry, EntryStyle stock) {
        ImageView foreground = entry.findViewById(foregroundId);
        ImageView background = entry.findViewById(backgroundId);
        if (foreground == null || background == null) return;
        foreground.setImageAlpha(hideForeground ? 0 : stock.foregroundAlpha());
        background.setImageAlpha(hideBackground || hideForeground ? 0 : stock.backgroundAlpha());
        entry.setZ(hideBackground || hideForeground ? FINGERPRINT_Z : stock.z());
        applySize(entry, stock);
    }

    private void applySize(ViewGroup entry, EntryStyle stock) {
        if (!sizeAvailable() || entry.getId() != entryId) return;
        boolean enlarged = !hideForeground && size > DEFAULT_SIZE;
        entry.setClipChildren(!enlarged && stock.clipChildren());
        // The keyguard parent clips this view's render node. Leave its other children alone.
        if (entry.getParent() instanceof ViewGroup parent) {
            try {
                if (renderNodeField.get(entry) instanceof RenderNode node
                        && node.setClipToBounds(!enlarged && parent.getClipChildren())) entry.invalidate();
            } catch (IllegalAccessException error) {
                Log.w(TAG, "Could not update fingerprint clipping", error);
            }
        }
        entry.findViewById(foregroundId).invalidate();
        entry.findViewById(backgroundId).invalidate();
        entry.invalidate();
    }

    private void installProtection(XposedInterface module, ClassLoader loader) {
        try {
            var model = loader.loadClass(ShadeStore.SYSTEM_UI + ".keyguard.ui.viewmodel.DeviceEntryIconViewModel");
            var backgroundProtection = model.getDeclaredField("useBackgroundProtection");
            backgroundProtection.setAccessible(true);
            var flowPackage = "kotlinx.coroutines.flow.";
            var flow = loader.loadClass(flowPackage + "Flow");
            var flowApi = loader.loadClass(flowPackage + "FlowKt");
            var stateFlow = loader.loadClass(flowPackage + "StateFlow");
            var scopeType = loader.loadClass("kotlinx.coroutines.CoroutineScope");
            var transformType = loader.loadClass("kotlin.jvm.functions.Function4");
            // This build keeps the three-flow overload, so use a constant flow for its third input.
            var combine = flowApi.getMethod("combine", flow, flow, flow, transformType);
            var constant = flowApi.getMethod("flowOf", Object.class).invoke(null, Boolean.TRUE);
            var sharing = loader.loadClass(flowPackage + "SharingStarted");
            var eagerField = loader.loadClass(flowPackage + "SharingStarted$Companion").getDeclaredField("Eagerly");
            eagerField.setAccessible(true);
            var eager = eagerField.get(null);
            var stateIn = flowApi.getMethod("stateIn", flow, scopeType, sharing, Object.class);
            var value = stateFlow.getMethod("getValue");
            var mutable = loader.loadClass(flowPackage + "StateFlowKt").getMethod("MutableStateFlow", Object.class);
            var suppressed = mutable.invoke(null, hideBackground && !hideForeground);
            var writeSuppressed = suppressed.getClass().getMethod("setValue", Object.class);
            var transform = Proxy.newProxyInstance(loader, new Class<?>[] {transformType}, (instance, method, args) ->
                    switch (method.getName()) {
                        case "invoke" -> Boolean.TRUE.equals(args[0]) && !Boolean.TRUE.equals(args[1]);
                        case "equals" -> instance == args[0];
                        case "hashCode" -> System.identityHashCode(instance);
                        case "toString" -> "Fingerprint background protection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            var constructor = Arrays.stream(model.getDeclaredConstructors()).filter(candidate ->
                    Arrays.asList(candidate.getParameterTypes()).contains(scopeType)).findFirst()
                    .orElseThrow(NoSuchMethodException::new);
            int scopeIndex = Arrays.asList(constructor.getParameterTypes()).indexOf(scopeType);
            module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var result = chain.proceed();
                var instance = chain.getThisObject();
                var stock = backgroundProtection.get(instance);
                boolean initial = Boolean.TRUE.equals(value.invoke(stock)) && !(hideBackground && !hideForeground);
                var adjusted = combine.invoke(null, stock, suppressed, constant, transform);
                backgroundProtection.set(instance, stateIn.invoke(null, adjusted, chain.getArg(scopeIndex), eager, initial));
                return result;
            });
            protectionSuppressed = suppressed;
            setProtectionSuppressed = writeSuppressed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.i(TAG, "Fingerprint background protection unavailable", error);
        }
    }

    private boolean sizeAvailable() { return available && renderNodeField != null; }

    static void update(Bundle config, boolean pixelOs) {
        INSTANCE.applySettings(config, pixelOs);
    }

    private void applySettings(Bundle config, boolean pixelOs) {
        if (!available) return;
        boolean nextBackground = pixelOs && config != null && config.getBoolean("hideUdfpsBackground");
        boolean nextForeground = pixelOs && config != null && config.getBoolean("hideUdfpsForeground");
        int nextSize = pixelOs && config != null && sizeAvailable()
                ? Math.clamp(config.getInt("udfpsSize", DEFAULT_SIZE), MIN_SIZE, MAX_SIZE) : DEFAULT_SIZE;
        boolean visibilityChanged = nextBackground != hideBackground || nextForeground != hideForeground;
        boolean sizeChanged = nextSize != size;
        if (!visibilityChanged && !sizeChanged) return;
        hideBackground = nextBackground;
        hideForeground = nextForeground;
        size = nextSize;
        if (visibilityChanged) {
            entries.forEach(this::apply);
            if (setProtectionSuppressed != null) {
                try { setProtectionSuppressed.invoke(protectionSuppressed, hideBackground && !hideForeground); }
                catch (ReflectiveOperationException error) { Log.w(TAG, "Could not update background protection", error); }
            }
        } else {
            entries.forEach(this::applySize);
        }
    }
}
