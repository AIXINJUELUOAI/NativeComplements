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
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Collection;

/** Adds application volume rails beside the native vertical volume slider. */
public final class MediaVolumeModule {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String TAG = "PixelOSMediaVolume";
    private MediaPlayers players;

    private MediaVolumeModule() {}

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        if (supported()) new MediaVolumeModule().installHooks(module, loader, note);
    }

    private static boolean supported() {
        return Build.VERSION.SDK_INT == 37 && "dada".equals(Build.DEVICE);
    }

    private void installHooks(XposedModule module, ClassLoader loader, Consumer<String> note) {
        try {
            installSideMixer(module, loader);
            note.accept("APP_VOLUME_DIALOG_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            note.accept("APP_VOLUME_DIALOG_UNAVAILABLE " + error);
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
        var bottomToBottom = params.getField("bottomToBottom");
        var endToStart = params.getField("endToStart");
        var vertical = dialogClass.getField("isVolumeDialogVertical");
        Method listenerInfo = View.class.getDeclaredMethod("getListenerInfo");
        listenerInfo.setAccessible(true);
        var clickListener = listenerInfo.getReturnType().getDeclaredField("mOnClickListener");
        clickListener.setAccessible(true);

        module.hook(binder.getMethod("bind", coroutineScope, Dialog.class, boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
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
                        bottomToBottom.setInt(layout, sliderId);
                        layout.height = slider.getHeight() + mixer.footerHeight();
                        layout.bottomMargin = -mixer.footerHeight();
                        slider.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                            int height = b - t + mixer.footerHeight();
                            if (height > 0 && mixer.getLayoutParams().height != height) {
                                var current = mixer.getLayoutParams();
                                current.height = height;
                                mixer.setLayoutParams(current);
                            }
                        });
                        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                        endToStart.setInt(layout, floatingId);
                        layout.setMarginEnd(mixer.gap());
                        // ConstraintLayout resolves end-to-start to the inside edge in either layout direction.
                        if (rtl) mixer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                        root.addView(mixer, layout);
                        @SuppressWarnings("unchecked")
                        Collection<View> bounds = (Collection<View>) touchable.get(viewModel.get(chain.getThisObject()));
                        mixer.setTouchableBounds(bounds);
                        int controlId = res.getIdentifier("volume_dialog_settings", "id", SYSTEM_UI);
                        View control = root.findViewById(controlId);
                        if (control != null) control.post(() -> {
                            try {
                                Object listeners = listenerInfo.invoke(control);
                                View.OnClickListener original = listeners == null ? null
                                        : (View.OnClickListener)clickListener.get(listeners);
                                control.setOnClickListener(v -> {
                                    if (!mixer.toggleExpanded() && original != null) original.onClick(v);
                                });
                            } catch (ReflectiveOperationException | RuntimeException error) {
                                Log.w(TAG, "Volume control binding unavailable", error);
                            }
                        });
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not attach application sliders beside the system slider", error);
            }
            return result;
        });
    }

    private MediaPlayers getPlayers(Context context) throws ReflectiveOperationException {
        if (players == null) {
            var app = context.getApplicationContext();
            var handler = new Handler(Looper.getMainLooper());
            Uri settings = Uri.parse("content://" + ShadeStore.AUTHORITY + "/app_volume");
            var snapshot = readSettings(app, settings);
            players = new MediaPlayers(app, handler, snapshot.getBoolean("appVolume"));
            players.setShowPercentage(snapshot.getBoolean("appVolumePercentage", true));
            app.getContentResolver().registerContentObserver(settings, false, new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    var snapshot = readSettings(app, settings);
                    players.setShowPercentage(snapshot.getBoolean("appVolumePercentage", true));
                    players.setEnabled(snapshot.getBoolean("appVolume"));
                }
            });
        }
        return players;
    }
    private static android.os.Bundle readSettings(Context context, Uri uri) {
        try {
            var result = context.getContentResolver().call(uri, "read", null, null);
            return result == null ? new android.os.Bundle() : result;
        } catch (RuntimeException unavailable) {
            return new android.os.Bundle();
        }
    }
}
