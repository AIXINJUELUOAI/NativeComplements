package dev.local.sunwindowdemo.features.shade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import dev.local.sunwindowdemo.features.shade.OutdoorRefreshSettings.Options;
import dev.local.sunwindowdemo.features.shade.OutdoorRefreshSettings.Trigger;
import io.github.libxposed.api.XposedInterface;
import dev.local.sunwindowdemo.core.ModuleIdentity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

public final class OutdoorRefreshModule {
    private final XposedInterface module;
    private static final String TAG = "PixelOSOutdoorRefresh";
    private static final String MODE_PACKAGE = "com.android.server.display.mode.";
    private static final String APP_PACKAGE = ModuleIdentity.CURRENT.packageName();
    private static final int DISPLAY = Display.DEFAULT_DISPLAY;
    private static final float TARGET_HZ = 120f;

    private volatile Session session;

    public static void install(XposedInterface module, ClassLoader loader) {
        if (OutdoorRefreshSettings.supportedDevice()) new OutdoorRefreshModule(module).installHooks(loader);
    }

    private OutdoorRefreshModule(XposedInterface module) { this.module = module; }

    private void installHooks(ClassLoader loader) {
        try {
            var director = Class.forName(MODE_PACKAGE + "DisplayModeDirector", false, loader);
            var vote = Class.forName(MODE_PACKAGE + "Vote", false, loader);
            var combinedVote = Class.forName(MODE_PACKAGE + "CombinedVote", false, loader)
                    .getDeclaredConstructor(List.class);
            combinedVote.setAccessible(true);
            var contextField = director.getField("mContext");
            var lockField = director.getField("mLock");
            var storageField = director.getField("mVotesStorage");
            var physical = vote.getMethod("forPhysicalRefreshRates", float.class, float.class);
            var supported = vote.getMethod("forSupportedModes", List.class);
            var priorityName = vote.getMethod("priorityToString", int.class);
            int priority = findFlickerSwitchPriority(priorityName);
            var notifyChanged = director.getMethod("notifyDesiredDisplayModeSpecsChangedLocked");
            var getVotes = storageField.getType().getMethod("getVotes", int.class);
            if (getVotes.getReturnType() != SparseArray.class) throw new NoSuchMethodException("display votes");
            module.hook(getVotes).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                var current = session;
                if (current == null || chain.getThisObject() != current.storage
                        || (int) chain.getArg(0) != DISPLAY) return result;
                Object activeVote = current.activeVote;
                if (activeVote == null || result == null) return result;
                @SuppressWarnings("unchecked")
                var votes = (SparseArray<Object>) result;
                if (votes.indexOfKey(priority) >= 0) return result;
                var scopedVotes = votes.clone();
                scopedVotes.put(priority, activeVote);
                return scopedVotes;
            });
            module.deoptimize(director.getMethod("getDesiredDisplayModeSpecs", int.class));
            module.hook(director.getMethod("start", SensorManager.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (session != null) return result;
                try {
                    var instance = chain.getThisObject();
                    var context = (Context) contextField.get(instance);
                    if (!PixelOsShade.supports(context)) return result;
                    var worker = new HandlerThread(TAG);
                    worker.start();
                    var handler = new Handler(worker.getLooper());
                    var current = new Session(context, handler,
                            (SensorManager) chain.getArg(0), instance, lockField.get(instance),
                            storageField.get(instance), notifyChanged, physical, supported, combinedVote);
                    session = current;
                    handler.post(current::start);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w(TAG, "Could not attach display policy", error);
                }
                return result;
            });
            Log.i(TAG, "Outdoor refresh hook installed, priority " + priority);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Outdoor refresh unavailable", error);
        }
    }

    private static int findFlickerSwitchPriority(Method priorityName) throws ReflectiveOperationException {
        for (int priority = 0; priority < 64; priority++) {
            if ("PRIORITY_FLICKER_REFRESH_RATE_SWITCH".equals(priorityName.invoke(null, priority))) {
                return priority;
            }
        }
        throw new NoSuchFieldException("PRIORITY_FLICKER_REFRESH_RATE_SWITCH");
    }

    private static final class Session implements DisplayManager.DisplayListener, SensorEventListener {
        private static final Options DEFAULTS = Options.from(new android.os.Bundle());

        private final Context context;
        private final Handler handler;
        private final SensorManager sensors;
        private final DisplayManager displays;
        private final Object director, lock, storage;
        private final Method notifyChanged, physical, supported;
        private final Constructor<?> combined;
        private final Runnable readSettings = this::readSettings;
        private Object fixedVote;
        private volatile Object activeVote;
        private Options options = DEFAULTS;
        private boolean started, listeningToLight, screenOn;
        private float lastLux = Float.NaN;

        Session(Context context, Handler handler, SensorManager sensors, Object director, Object lock,
                Object storage, Method notifyChanged, Method physical, Method supported, Constructor<?> combined) {
            this.context = context;
            this.handler = handler;
            this.sensors = sensors;
            this.director = director;
            this.lock = lock;
            this.storage = storage;
            this.notifyChanged = notifyChanged;
            this.physical = physical;
            this.supported = supported;
            this.combined = combined;
            displays = context.getSystemService(DisplayManager.class);
        }

        void start() {
            try {
                context.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) { startMonitoring(); }
                }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, handler, Context.RECEIVER_NOT_EXPORTED);
                if (context.getSystemService(android.os.UserManager.class).isUserUnlocked()) startMonitoring();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not initialize outdoor refresh", error);
            }
        }

        private void startMonitoring() {
            if (started) return;
            try {
                var display = displays.getDisplay(DISPLAY);
                if (display == null) return;
                var currentMode = display.getMode();
                for (var mode : display.getSupportedModes()) {
                    if (mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                            && mode.getPhysicalHeight() == currentMode.getPhysicalHeight()
                            && Math.abs(mode.getRefreshRate() - TARGET_HZ) < 0.01f
                            && mode.getAlternativeRefreshRates().length == 0) {
                        float rate = mode.getRefreshRate();
                        fixedVote = combined.newInstance(List.of(physical.invoke(null, rate, rate),
                                supported.invoke(null, List.of(mode.getModeId()))));
                        Log.i(TAG, "Fixed 120 Hz group available, display mode " + mode.getModeId());
                        break;
                    }
                }
                if (fixedVote == null) {
                    Log.w(TAG, "No fixed 120 Hz group found on the default display");
                    return;
                }
                displays.registerDisplayListener(handler::post, DisplayManager.EVENT_TYPE_DISPLAY_STATE
                        | DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS, this);
                var packages = new IntentFilter();
                packages.addAction(Intent.ACTION_PACKAGE_ADDED);
                packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
                packages.addDataScheme("package");
                packages.addDataSchemeSpecificPart(APP_PACKAGE, android.os.PatternMatcher.PATTERN_LITERAL);
                context.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                                && Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) return;
                        readSettings();
                    }
                }, packages, null, handler, Context.RECEIVER_NOT_EXPORTED);
                context.getContentResolver().registerContentObserver(OutdoorRefreshSettings.URI, false,
                        new ContentObserver(handler) {
                            @Override public void onChange(boolean selfChange) {
                                handler.removeCallbacks(readSettings);
                                handler.post(readSettings);
                            }
                        });
                started = true;
                readSettings();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not monitor outdoor refresh triggers", error);
            }
        }

        private void readSettings() {
            Options next = DEFAULTS;
            try {
                var values = context.getContentResolver().call(OutdoorRefreshSettings.URI, OutdoorRefreshSettings.READ, null, null);
                if (values != null) next = Options.from(values);
            } catch (RuntimeException error) {
                Log.w(TAG, "Outdoor refresh settings unavailable", error);
            }
            boolean reset = !options.equals(next);
            options = next;
            update(reset);
        }

        private void update(boolean reset) {
            var display = displays.getDisplay(DISPLAY);
            screenOn = display != null && display.getState() == Display.STATE_ON;
            boolean useLight = screenOn && options.enabled() && options.trigger() == Trigger.LIGHT;
            if (useLight != listeningToLight) {
                lastLux = Float.NaN;
                if (useLight) {
                    var sensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
                    listeningToLight = sensor != null && sensors.registerListener(this, sensor,
                            SensorManager.SENSOR_DELAY_NORMAL, handler);
                } else {
                    sensors.unregisterListener(this);
                    listeningToLight = false;
                }
            }
            evaluate(reset);
        }

        private void evaluate(boolean reset) {
            boolean alreadyActive = !reset && activeVote != null;
            boolean active = options.enabled() && screenOn && switch (options.trigger()) {
                case MANUAL -> true;
                case BRIGHTNESS -> readBrightness() >= (alreadyActive
                        ? Math.max(1, options.brightness() - 3) : options.brightness());
                case LIGHT -> lastLux >= (alreadyActive ? options.light() * 0.85f : options.light());
            };
            if (active == (activeVote != null)) return;
            synchronized (lock) {
                activeVote = active ? fixedVote : null;
                try {
                    notifyChanged.invoke(director);
                    Log.i(TAG, (active ? "Requested fixed 120 Hz" : "Restored default display policy")
                            + " (" + options.trigger() + ")");
                } catch (ReflectiveOperationException | RuntimeException error) {
                    activeVote = null;
                    Log.w(TAG, "Could not update display policy", error);
                }
            }
        }

        private float readBrightness() {
            try {
                return displays.getBrightness(DISPLAY, DisplayManager.BRIGHTNESS_UNIT_PERCENTAGE);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not read display brightness percentage", error);
                return Float.NaN;
            }
        }

        @Override public void onDisplayAdded(int id) { if (id == DISPLAY) update(false); }
        @Override public void onDisplayRemoved(int id) { if (id == DISPLAY) update(false); }
        @Override public void onDisplayChanged(int id) { if (id == DISPLAY) update(false); }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        @Override public void onSensorChanged(SensorEvent event) {
            if (!listeningToLight) return;
            lastLux = event.values[0];
            evaluate(false);
        }
    }
}
