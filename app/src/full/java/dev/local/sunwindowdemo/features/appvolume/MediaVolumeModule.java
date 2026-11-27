package dev.local.sunwindowdemo.features.appvolume;

import android.content.Context;
import android.app.Dialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.database.ContentObserver;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import io.github.libxposed.api.XposedInterface;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Collection;
import java.lang.reflect.Proxy;

/** Inserts a separate app mixer into the native volume sheet's component column. */
public final class MediaVolumeModule {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String TAG = "PixelOSMediaVolume";
    private static final String ROOT = "com.android.systemui.volume.panel.ui.composable.";
    private final ThreadLocal<Boolean> insideComponents = new ThreadLocal<>();
    private MediaPlayers players;

    private MediaVolumeModule() {}

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        if (supported()) new MediaVolumeModule().installHooks(module, loader, note);
    }

    private static boolean supported() {
        return Build.VERSION.SDK_INT == 37 && "dada".equals(Build.DEVICE);
    }

    private void installHooks(XposedModule module, ClassLoader loader, Consumer<String> note) {
        HookHandle componentsHook = null;
        try {
            Class<?> root = Class.forName(ROOT + "VolumePanelRootKt", false, loader);
            Class<?> scope = Class.forName(ROOT + "VolumePanelComposeScope", false, loader);
            Class<?> layout = Class.forName("com.android.systemui.volume.panel.ui.layout.ComponentsLayout",
                    false, loader);
            Class<?> modifier = Class.forName("androidx.compose.ui.Modifier", false, loader);
            Class<?> composer = Class.forName("androidx.compose.runtime.Composer", false, loader);
            Class<?> function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            Method components = root.getMethod("Components", scope, layout, modifier, composer, int.class);
            Method bottomBar = root.getMethod("BottomBar", scope, layout, modifier, composer, int.class);
            Class<?> views = Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt", false, loader);
            Method androidView = views.getMethod("AndroidView", function, modifier, function,
                    composer, int.class, int.class);
            Class<?> sizes = Class.forName("androidx.compose.foundation.layout.SizeKt", false, loader);
            Object fullWidth = sizes.getMethod("fillMaxWidth$default", modifier, float.class,
                    int.class, Object.class).invoke(null, modifier.getField("Companion").get(null),
                    1f, 0, null);
            Object viewFactory = Proxy.newProxyInstance(loader, new Class<?>[] {function},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "invoke" -> makeView((Context) args[0]);
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "NativeComplements app mixer";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            componentsHook = module.hook(components).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Boolean previous = insideComponents.get();
                insideComponents.set(true);
                try {
                    return chain.proceed();
                } finally {
                    if (previous == null) insideComponents.remove();
                    else insideComponents.set(previous);
                }
            });
            module.hook(bottomBar).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                // Direct recompositions of BottomBar never recreate this sibling.
                if (Boolean.TRUE.equals(insideComponents.get())) {
                    androidView.invoke(null, viewFactory, fullWidth, null, chain.getArg(3), 0, 4);
                }
                return chain.proceed();
            });
            note.accept("APP_VOLUME_SHEET_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (componentsHook != null) componentsHook.unhook();
            Log.w(TAG, "Volume sheet integration unavailable", error);
        }
        try {
            installSideMixer(module, loader);
            note.accept("APP_VOLUME_DIALOG_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Side volume mixer unavailable", error);
        }
    }

    private void installSideMixer(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> binder = Class.forName("com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder",
                false, loader);
        Class<?> coroutineScope = Class.forName("kotlinx.coroutines.CoroutineScope", false, loader);
        var viewModel = binder.getField("viewModel");
        var touchable = viewModel.getType().getField("touchableBoundsViews");
        Class<?> dialogClass = Class.forName("com.android.systemui.volume.dialog.VolumeDialog", false, loader);
        var visibility = dialogClass.getField("visibilityInteractor");
        Method resetTimeout = visibility.getType().getMethod("resetDismissTimeout");
        Class<?> params = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams",
                false, loader);
        var constructor = params.getConstructor(int.class, int.class);
        var topToTop = params.getField("topToTop");
        var bottomToBottom = params.getField("bottomToBottom");
        var endToStart = params.getField("endToStart");
        var vertical = dialogClass.getField("isVolumeDialogVertical");

        module.hook(binder.getMethod("bind", coroutineScope, Dialog.class, boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Dialog dialog = (Dialog) chain.getArg(1);
            try {
                if (dialogClass.isInstance(dialog) && vertical.getBoolean(dialog)) {
                    Context context = dialog.getContext();
                    var res = context.getResources();
                    int rootId = res.getIdentifier("volume_dialog", "id", SYSTEM_UI);
                    int sliderId = res.getIdentifier("volume_dialog_main_slider_container", "id", SYSTEM_UI);
                    int floatingId = res.getIdentifier("volume_dialog_floating_sliders_container", "id", SYSTEM_UI);
                    View rootView = dialog.findViewById(rootId);
                    View slider = dialog.findViewById(sliderId);
                    View floating = dialog.findViewById(floatingId);
                    if (rootView instanceof ViewGroup root && slider != null && floating != null
                            && root.findViewWithTag(SideAppMixerView.TAG) == null) {
                        Object visibilityInteractor = visibility.get(dialog);
                        Runnable userActivity = () -> {
                            try {
                                resetTimeout.invoke(visibilityInteractor);
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                            }
                        };
                        var mixer = new SideAppMixerView(context, getPlayers(context), userActivity);
                        var layout = (ViewGroup.MarginLayoutParams) constructor.newInstance(
                                ViewGroup.LayoutParams.WRAP_CONTENT, 0);
                        topToTop.setInt(layout, sliderId);
                        bottomToBottom.setInt(layout, sliderId);
                        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                        endToStart.setInt(layout, floatingId);
                        layout.setMarginEnd(mixer.gap());
                        // ConstraintLayout resolves end-to-start to the inside edge in either layout direction.
                        if (rtl) mixer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                        root.addView(mixer, layout);
                        @SuppressWarnings("unchecked")
                        Collection<View> bounds = (Collection<View>) touchable.get(viewModel.get(chain.getThisObject()));
                        mixer.setTouchableBounds(bounds);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not attach application sliders beside the system slider", error);
            }
            return chain.proceed();
        });
    }

    private View makeView(Context context) {
        try {
            return new AppMixerView(context, getPlayers(context));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Player volume integration unavailable", error);
            return new View(context);
        }
    }

    private MediaPlayers getPlayers(Context context) throws ReflectiveOperationException {
        if (players == null) {
            var app = context.getApplicationContext();
            var handler = new Handler(Looper.getMainLooper());
            Uri settings = Uri.parse("content://" + ShadeStore.AUTHORITY + "/app_volume");
            players = new MediaPlayers(app, handler, readEnabled(app, settings));
            app.getContentResolver().registerContentObserver(settings, false, new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    players.setEnabled(readEnabled(app, settings));
                }
            });
        }
        return players;
    }
    private static boolean readEnabled(Context context, Uri uri) {
        try {
            var result = context.getContentResolver().call(uri, "read", null, null);
            return result != null && result.getBoolean("appVolume");
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
