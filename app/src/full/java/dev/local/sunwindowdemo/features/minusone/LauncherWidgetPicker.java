package dev.local.sunwindowdemo.features.minusone;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface;

final class LauncherWidgetPicker {
    private static final String TAG = "PixelOSWidgetPicker";
    private static final String SUITE = "dev.nativecomplements.module";

    private LauncherWidgetPicker() {}

    static void install(XposedInterface framework, ClassLoader loader, Context context, Consumer<String> note) {
        try {
            hookSelection(framework, loader);
            watchRequests(context);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            note.accept("MINUS_ONE_PICKER_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    private static void hookSelection(XposedInterface framework, ClassLoader loader) throws ReflectiveOperationException {
        String picker = "com.android.launcher3.widgetpicker.";
        var listeners = Class.forName(
                picker + "WidgetPickerComposeWrapperImpl$Companion$buildEventListeners$1", false, loader);
        var interaction = Class.forName(picker + "ui.WidgetInteractionInfo", false, loader);
        var add = Class.forName(interaction.getName() + "$WidgetAddInfo", false, loader);
        var widget = Class.forName(picker + "shared.model.WidgetInfo$AppWidgetInfo", false, loader);
        var addWidget = add.getField("widgetInfo");
        var providerInfo = widget.getField("appWidgetProviderInfo");
        var listenerActivity = listeners.getField("$this_buildEventListeners");
        addWidget.setAccessible(true);
        providerInfo.setAccessible(true);
        listenerActivity.setAccessible(true);
        framework.hook(listeners.getDeclaredMethod("onWidgetInteraction", interaction)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            var activity = (Activity) listenerActivity.get(chain.getThisObject());
            if (!activity.getIntent().getBooleanExtra(WidgetPickerHandoff.FOR_FEED, false)
                    || !SUITE.equals(activity.getCallingPackage())) return chain.proceed();
            Object event = chain.getArg(0);
            if (!add.isInstance(event)) {
                Toast.makeText(activity, "点微件预览后选择「添加」", Toast.LENGTH_SHORT).show();
                return null;
            }
            Object chosen = addWidget.get(event);
            if (!widget.isInstance(chosen)) {
                Toast.makeText(activity, "请选择小部件", Toast.LENGTH_SHORT).show();
                return null;
            }
            var info = (AppWidgetProviderInfo) providerInfo.get(chosen);
            if (info == null) return null;
            activity.setResult(Activity.RESULT_OK, new Intent()
                    .putExtra(WidgetPickerHandoff.COMPONENT, info.provider)
                    .putExtra(WidgetPickerHandoff.USER, info.getProfile()));
            activity.finish();
            return null;
        });
    }

    private static void watchRequests(Context context) {
        var observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                var intent = new Intent(Intent.ACTION_PICK)
                        .setClassName(WidgetPickerHandoff.LAUNCHER, WidgetPickerHandoff.PICKER)
                        .putExtra("ui_surface", "widgets_hub")
                        .putExtra("picker_title", "添加到负一屏")
                        .putExtra(WidgetPickerHandoff.FOR_FEED, true);
                var picker = PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                try {
                    MinusOneSettings.publishWidgetPicker(context, picker);
                } catch (RuntimeException unavailable) {
                    picker.cancel();
                    Log.w(TAG, "Could not return the widget picker", unavailable);
                }
            }
        };
        context.getContentResolver().registerContentObserver(
                MinusOneSettings.PICKER_REQUEST_URI, false, observer);
    }
}
