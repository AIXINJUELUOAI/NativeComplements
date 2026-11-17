package dev.local.sunwindowdemo.features.darkaod;

import android.app.AlarmManager;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.Method;
import java.util.ArrayList;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** A scoped ALS reason for the native proximity-pause path, never a display-power owner. */
public final class DarkAodHost {
    private final XposedModule module;
    private final DarkAodAccess access;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    private DarkAodHost(XposedModule module, DarkAodAccess access) {
        this.module = module;
        this.access = access;
    }

    public static void install(XposedModule module, ClassLoader loader) {
        var hooks = new ArrayList<XposedInterface.HookHandle>();
        try {
            DarkAodAccess access = new DarkAodAccess(loader);
            DarkAodHost host = new DarkAodHost(module, access);
            // The transition also calls itself for initialization/pulse completion after R8.
            for (Method method : access.transition.getDeclaringClass().getDeclaredMethods()) {
                if ((method.getName().equals("requestState") || method.equals(access.transition))
                        && !module.deoptimize(method))
                    throw new IllegalStateException("Doze caller deoptimization failed");
            }
            hooks.add(module.hook(access.create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try { host.connect((Context) chain.getThisObject()); }
                catch (ReflectiveOperationException | RuntimeException error) { host.failure(); }
                return result;
            }));
            hooks.add(module.hook(access.destroy).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                try {
                    if (host.session != null && host.session.context == chain.getThisObject()) host.session.close();
                } catch (RuntimeException error) { host.failure(); }
                return chain.proceed();
            }));
            hooks.add(module.hook(access.transition).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Session s = host.session;
                Object requested = chain.getArg(0), target = requested;
                boolean claimed = false;
                if (s != null && s.alive && s.machine == chain.getThisObject()) {
                    try {
                        target = s.target(requested);
                        claimed = requested == access.aod && target != requested
                                && Boolean.FALSE.equals(access.near(s.prox));
                    } catch (ReflectiveOperationException | RuntimeException error) { host.failure(); target = requested; }
                }
                Object result = target == requested ? chain.proceed() : chain.proceed(new Object[]{target, chain.getArg(1)});
                if (s != null && s.alive && s.machine == chain.getThisObject()) {
                    try {
                        Object actual = access.state.get(s.machine);
                        if (claimed && access.isPaused(actual)) s.claim();
                        if ("FINISH".equals(access.name(actual))) s.close();
                        else {
                            if (!access.isPaused(actual) && !access.name(actual).contains("PULS")) s.owned = false;
                            s.mainEvaluate();
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) { host.failure(); }
                }
                return result;
            }));
            host.log("HOOK_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (var hook : hooks) try { hook.unhook(); } catch (RuntimeException ignored) { }
            // No exception messages or sensor values: host/device data is not diagnostic output.
            module.log(android.util.Log.WARN, "NativeDarkAod", "INSTALL_FAILED_NATIVE");
        }
    }

    private void connect(Context service) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        Display display = service.getDisplay();
        if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) return;
        if (session != null) session.close();
        Object machine = access.machine.get(service);
        if (machine == null) return;
        Session next = new Session(service, machine, access.proximity(machine));
        session = next;
        next.start();
    }

    private void failure() {
        Session s = session;
        if (s != null && !s.failed) {
            log("UNAVAILABLE_NATIVE");
            s.failed = true;
            s.enabled = false;
            s.policy.reset();
            s.stopLight();
            // Release only our reason; unknown/near proximity must never become far.
            s.mainEvaluate();
        }
    }

    private void log(String event) {
        try { module.log(android.util.Log.INFO, "NativeDarkAod", event); }
        catch (RuntimeException ignored) { }
    }

    static boolean wantsPause(boolean dark, String current, String requested, Boolean near) {
        return dark && "DOZE_AOD".equals(requested)
                && (("DOZE_AOD".equals(current) || "DOZE_PULSE_DONE".equals(current)) && Boolean.FALSE.equals(near)
                    || "DOZE_AOD_PAUSING".equals(current) || "DOZE_AOD_PAUSED".equals(current));
    }

    static boolean wantsPause(boolean dark, String current, String requested, Boolean near, boolean owned) {
        return wantsPause(dark, current, requested, near)
                || owned && dark && "DOZE_PULSE_DONE".equals(current) && "DOZE_AOD".equals(requested);
    }

    static boolean wantsRestore(boolean owned, boolean dark, String state, Boolean near) {
        return owned && !dark && Boolean.FALSE.equals(near)
                && ("DOZE_AOD_PAUSING".equals(state) || "DOZE_AOD_PAUSED".equals(state));
    }

    private final class Session implements SensorEventListener {
        final Context context;
        final Object machine, prox;
        final int processUser = Process.myUid() / 100000;
        final DarkAodPolicy policy = new DarkAodPolicy();
        final SensorManager sensors;
        final AlarmManager alarms;
        final HandlerThread thread = new HandlerThread("NativeDarkAodConfig");
        final Runnable evaluate = () -> {
            if (!this.alive) return;
            try { evaluate(); }
            catch (ReflectiveOperationException | RuntimeException error) { failure(); }
        };
        final AlarmManager.OnAlarmListener alarm = () -> {
            if (!this.alive) return;
            scheduled = -1;
            try {
                policy.tick(SystemClock.elapsedRealtime());
                mainEvaluate();
            } catch (RuntimeException error) { failure(); }
        };
        Handler reader;
        ContentObserver observer;
        volatile boolean alive = true;
        boolean enabled, owned, listening, failed;
        long scheduled = -1;
        float darkLux, brightLux;

        Session(Context context, Object machine, Object prox) {
            this.context = context;
            this.machine = machine;
            this.prox = prox;
            sensors = context.getSystemService(SensorManager.class);
            alarms = context.getSystemService(AlarmManager.class);
        }

        void start() {
            thread.start();
            reader = new Handler(thread.getLooper());
            observer = new ContentObserver(reader) {
                @Override public void onChange(boolean selfChange) { reader.removeCallbacks(reload); reader.post(reload); }
            };
            reader.post(reload);
        }

        final Runnable reload = this::readSettings;

        void readSettings() {
            if (!alive) return;
            boolean next = false;
            float dark = 0f, bright = 5f;
            try {
                Uri uri = Uri.parse("content://" + DarkAodStore.AUTHORITY);
                if (!this.observing) {
                    context.getContentResolver().registerContentObserver(uri, false, observer);
                    observing = true;
                }
                Bundle data = context.getContentResolver().call(uri, "system", null, null);
                if (data == null) throw new IllegalStateException("Missing config");
                dark = data.getFloat("dark_lux", 0f);
                bright = data.getFloat("bright_lux", 5f);
                next = data.getBoolean("enabled", false) && Float.isFinite(dark) && Float.isFinite(bright)
                        && dark >= 0f && dark <= 5f && bright >= Math.max(5f, dark + 3f);
            } catch (RuntimeException error) {
                log("CONFIG_UNAVAILABLE_NATIVE");
                if (this.alive) reader.postDelayed(this.reload, 5000);
            }
            boolean desired = next;
            float low = dark, high = bright;
            main.post(() -> {
                if (!this.alive) return;
                if (enabled != desired || darkLux != low || brightLux != high) {
                    enabled = desired && !failed;
                    darkLux = low;
                    brightLux = high;
                    policy.configure(enabled, low, high);
                    log("CONFIG enabled=" + enabled);
                }
                mainEvaluate();
            });
        }
        boolean observing;

        boolean validOwner() throws ReflectiveOperationException {
            Display display = context.getDisplay();
            if (display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY
                    && (Integer) access.userId.invoke(access.tracker.get(machine)) == processUser) return true;
            close();
            return false;
        }

        Object target(Object requested) throws ReflectiveOperationException {
            if (!validOwner()) return requested;
            if (!enabled || failed || !policy.isDark() || requested != access.aod
                    || !access.allowed(machine, processUser)) return requested;
            Object current = access.state.get(machine);
            if (!wantsPause(policy.isDark(), access.name(current), access.name(requested), access.near(prox), owned))
                return requested;
            return access.isPaused(current) ? current : access.pausing; // Do not restart the native pause alarm.
        }

        void mainEvaluate() { main.removeCallbacks(evaluate); if (alive) main.post(evaluate); }

        void evaluate() throws ReflectiveOperationException {
            if (!validOwner()) return;
            Object current = access.state.get(machine);
            boolean steady = current == access.aod || access.isPaused(current);
            boolean allowed = access.allowed(machine, processUser);
            if (!allowed) owned = false;
            boolean pulse = access.name(current).contains("PULS");
            // Keep the committed reason and ALS through native pulse/auth, without requesting a state there.
            if (enabled && !failed && (steady || pulse) && allowed) startLight();
            else stopLight();
            schedule();
            if (!steady || !access.idle(machine) || !allowed || !Boolean.FALSE.equals(access.near(prox))) return;
            if (enabled && !failed && policy.isDark() && current == access.aod) {
                access.request.invoke(machine, access.pausing);
                if (access.isPaused(access.state.get(machine))) claim();
            } else if (wantsRestore(owned, enabled && !failed && policy.isDark(), access.name(current), access.near(prox))) {
                access.request.invoke(machine, access.aod);
                if (access.state.get(machine) == access.aod) { owned = false; log("OWNED_RELEASED"); }
            }
        }

        void claim() {
            if (!owned) log("OWNED_PAUSE");
            owned = true;
        }

        void startLight() {
            if (listening) return;
            Sensor light = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT, true);
            if (light == null || !light.isWakeUpSensor() || alarms == null
                    || !sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, main)) {
                failed = true;
                enabled = false;
                policy.reset();
                log("WAKE_SENSOR_UNAVAILABLE_NATIVE");
                return;
            }
            listening = true;
            log("LISTENING");
        }

        void stopLight() {
            if (listening) {
                sensors.unregisterListener(this);
                log("STOP");
            }
            listening = false;
            policy.reset();
            cancelAlarm();
        }

        void schedule() {
            long deadline = policy.deadline();
            if (deadline == scheduled) return;
            cancelAlarm();
            if (deadline < 0 || !alive) return;
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, "NativeDarkAod", alarm, main);
            scheduled = deadline;
        }

        void cancelAlarm() {
            if (scheduled >= 0 && alarms != null) alarms.cancel(alarm);
            scheduled = -1;
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (!alive || !listening) return;
            if (event.values.length == 0 || !Float.isFinite(event.values[0]) || event.values[0] < 0f) policy.reset();
            else policy.sample(event.values[0], SystemClock.elapsedRealtime());
            mainEvaluate();
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        void close() {
            if (!this.alive) return;
            alive = false;
            owned = false;
            stopLight();
            main.removeCallbacks(evaluate);
            if (reader != null) {
                reader.removeCallbacksAndMessages(null);
                // Serialize with an in-flight provider read/observer registration before quitting.
                reader.post(() -> {
                    if (observing) try { context.getContentResolver().unregisterContentObserver(observer); }
                    catch (RuntimeException ignored) { }
                    thread.quitSafely();
                });
            }
            if (session == this) session = null;
        }
    }
}
