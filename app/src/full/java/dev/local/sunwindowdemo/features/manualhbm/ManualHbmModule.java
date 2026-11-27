package dev.local.sunwindowdemo.features.manualhbm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import java.util.function.Consumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ManualHbmModule {
    private static final String TAG = "PixelOSManualHBM";
    private static final long SESSION_MS = 10 * 60 * 1000L;
    private static final long MONITOR_MS = 5_000;
    private static final int MIN_BATTERY_PERCENT = 10;

    private volatile DisplaySession display;
    private volatile long expiresAt;
    private boolean receiversRegistered;
    private Method updateHbmMode;
    private Method updatePowerState;
    private Method supportsHbm;
    private Field hbmEnabled;
    private Field automatic;
    private Field timeAvailable;
    private Field lowPower;

    private ManualHbmModule() {}

    public static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        if (Build.VERSION.SDK_INT == 37 && "dada".equals(Build.DEVICE)) {
            new ManualHbmModule().installHooks(module, loader, note);
        }
    }

    private void installHooks(XposedModule module, ClassLoader loader, Consumer<String> note) {
        try {
            Class<?> dpc = Class.forName("com.android.server.display.DisplayPowerController", false, loader);
            Field id = dpc.getField("mDisplayId");
            Field context = dpc.getField("mContext");
            Field handler = dpc.getField("mHandler");
            Field range = dpc.getField("mBrightnessRangeController");
            Field controller = range.getType().getField("mHbmController");
            Class<?> hbm = controller.getType();

            Method allowed = hbm.getMethod("isHbmCurrentlyAllowed");
            updateHbmMode = hbm.getMethod("updateHbmMode");
            updatePowerState = dpc.getMethod("sendUpdatePowerState");
            supportsHbm = hbm.getMethod("deviceSupportsHbm");
            hbmEnabled = hbm.getField("mHbmControllerIsEnabled");
            automatic = hbm.getField("mIsAutoBrightnessEnabled");
            timeAvailable = hbm.getField("mIsTimeAvailable");
            lowPower = hbm.getField("mIsBlockedByLowPowerMode");

            // Preserve the native mode calculation and change only the manual eligibility gate.
            module.hook(allowed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                boolean original = (Boolean) chain.proceed();
                if (original) return true;
                DisplaySession session = display;
                return session != null && chain.getThisObject() == session.hbm()
                        && SystemClock.elapsedRealtime() < expiresAt
                        && nativeEligibility(session);
            });
            module.deoptimize(hbm.getMethod("getCurrentBrightnessMax"));
            module.deoptimize(hbm.getMethod("calculateHighBrightnessMode"));
            module.deoptimize(range.getType().getMethod("getCurrentBrightnessMax"));

            for (var constructor : dpc.getConstructors()) {
                module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object instance = chain.getThisObject();
                        if (id.getInt(instance) == Display.DEFAULT_DISPLAY) {
                            Object hbmController = controller.get(range.get(instance));
                            var displayContext = (Context) context.get(instance);
                            var displayHandler = (Handler) handler.get(instance);
                            displayHandler.post(() -> connect(
                                    instance, hbmController, displayContext, displayHandler));
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        Log.w(TAG, "Could not attach default display", error);
                    }
                    return result;
                });
            }
            note.accept("MANUAL_HBM_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            note.accept("MANUAL_HBM_UNAVAILABLE " + error.getClass().getSimpleName());
        }
    }

    private void connect(Object dpc, Object hbm, Context context, Handler handler) {
        if (hbm == null) return;
        var power = context.getSystemService(PowerManager.class);
        var battery = context.getSystemService(BatteryManager.class);
        if (power == null || battery == null) return;
        expiresAt = 0;
        display = new DisplaySession(dpc, hbm, context, handler, power, battery);
        if (receiversRegistered) return;
        boolean controlRegistered = false;
        try {
            context.registerReceiver(controlReceiver, new IntentFilter(HbmControl.ACTION),
                    HbmControl.PERMISSION, handler, Context.RECEIVER_EXPORTED);
            controlRegistered = true;
            context.registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    null, handler, Context.RECEIVER_NOT_EXPORTED);
            receiversRegistered = true;
            Log.i(TAG, "Quick Settings control registered for default display");
        } catch (RuntimeException error) {
            if (controlRegistered) context.unregisterReceiver(controlReceiver);
            Log.w(TAG, "Unable to register manual HBM Quick Settings control", error);
        }
    }

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DisplaySession session = display;
            if (session == null) return;
            if (!isOrderedBroadcast()) return;
            int availability = availability(session);
            if (intent.getBooleanExtra(HbmControl.TOGGLE, false)) {
                if (expiresAt != 0) stop(session);
                else if (availability == HbmControl.OFF) {
                    expiresAt = SystemClock.elapsedRealtime() + SESSION_MS;
                    recompute(session);
                    session.handler().postDelayed(monitor, MONITOR_MS);
                }
            }
            setResultCode(expiresAt > SystemClock.elapsedRealtime() && availability == HbmControl.OFF
                    ? HbmControl.ON : availability);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DisplaySession session = display;
            if (session != null && expiresAt != 0) stop(session);
        }
    };

    private final Runnable monitor = new Runnable() {
        @Override
        public void run() {
            DisplaySession session = display;
            if (session == null || expiresAt == 0) return;
            long remaining = expiresAt - SystemClock.elapsedRealtime();
            if (remaining <= 0 || availability(session) != HbmControl.OFF) stop(session);
            else session.handler().postDelayed(this, Math.min(MONITOR_MS, remaining));
        }
    };

    private int availability(DisplaySession session) {
        try {
            if (!Boolean.TRUE.equals(supportsHbm.invoke(session.hbm()))
                    || !hbmEnabled.getBoolean(session.hbm())) return HbmControl.UNAVAILABLE;
            int mode = Settings.System.getInt(session.context().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return HbmControl.AUTO;
            if (!nativeEligibility(session) || !session.power().isInteractive()
                    || session.power().isPowerSaveMode()
                    || session.power().getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE
                    || session.battery().getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            < MIN_BATTERY_PERCENT) return HbmControl.PROTECTED;
            return HbmControl.OFF;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return HbmControl.UNAVAILABLE;
        }
    }

    private boolean nativeEligibility(DisplaySession session) {
        try {
            return hbmEnabled.getBoolean(session.hbm())
                    && !automatic.getBoolean(session.hbm())
                    && timeAvailable.getBoolean(session.hbm())
                    && !lowPower.getBoolean(session.hbm());
        } catch (IllegalAccessException | RuntimeException ignored) {
            return false;
        }
    }

    private void stop(DisplaySession session) {
        expiresAt = 0;
        session.handler().removeCallbacks(monitor);
        recompute(session);
    }

    private void recompute(DisplaySession session) {
        try {
            updateHbmMode.invoke(session.hbm());
            updatePowerState.invoke(session.dpc());
        } catch (ReflectiveOperationException | RuntimeException error) {
            expiresAt = 0;
            Log.w(TAG, "Default display refresh failed", error);
        }
    }

    private record DisplaySession(Object dpc, Object hbm, Context context, Handler handler,
            PowerManager power, BatteryManager battery) {}
}
