package dev.local.sunwindowdemo;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.EditText;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Observes native dispatch without consuming input. Only unoccupied keyguard touches may sleep. */
final class LockscreenDoubleTap {
    private static final WeakHashMap<View, DesktopDoubleTap.Taps> gestures = new WeakHashMap<>();
    private static final List<Rect> fingerprintAreas = new ArrayList<>();
    private static boolean enabled, connected, fingerprintKnown;
    private static PowerManager power;
    private static KeyguardManager keyguard;
    private static Method sleep;
    private static Field interaction, owner, status, primary, alternate, upcoming, launching;
    private static Method state, dozing, dreaming, primaryShowing, alternateShowing;
    private static Field firstTouchTarget, touchChild;
    private static Class<?> notificationStack;
    private static Method notificationAt;
    private static int tracedTaps, tracedFailures;
    private static String touchTarget = "none", blockedBy = "none";

    static void install(XposedModule module, ClassLoader loader, Consumer<String> note) {
        try {
            Class<?> root = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, loader);
            Method dispatch = root.getMethod("dispatchTouchEvent", MotionEvent.class);
            interaction = root.getField("mInteractionEventHandler");
            owner = interaction.getType().getDeclaredField("this$0");
            owner.setAccessible(true);
            Class<?> controller = owner.getType();
            status = controller.getField("mStatusBarStateController");
            primary = controller.getField("mPrimaryBouncerInteractor");
            alternate = controller.getField("mAlternateBouncerInteractor");
            launching = controller.getField("mExpandAnimationRunning");
            state = status.getType().getMethod("getState");
            dozing = status.getType().getMethod("isDozing");
            dreaming = status.getType().getMethod("isDreaming");
            upcoming = status.getType().getField("mUpcomingState");
            primaryShowing = primary.getType().getMethod("isBouncerShowing");
            alternateShowing = alternate.getType().getMethod("isVisibleState");
            firstTouchTarget = ViewGroup.class.getDeclaredField("mFirstTouchTarget");
            firstTouchTarget.setAccessible(true);
            touchChild = firstTouchTarget.getType().getDeclaredField("child");
            touchChild.setAccessible(true);
            notificationStack = Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", false, loader);
            notificationAt = notificationStack.getMethod("getChildAtRawPosition", float.class, float.class);
            sleep = PowerManager.class.getDeclaredMethod("goToSleep", long.class, int.class, int.class);
            sleep.setAccessible(true);
            module.hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                View view = root.isInstance(chain.getThisObject()) ? (View)chain.getThisObject() : null;
                MotionEvent event = (MotionEvent)chain.getArg(0);
                boolean blankBefore = false;
                boolean nativeBlank = false;
                blockedBy = "none";
                try {
                    blankBefore = view != null && ready(view) && blank(view, event.getRawX(), event.getRawY(), true);
                    nativeBlank = view != null && nativeBlankTarget(view);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (tracedFailures++ < 3) note.accept("LOCKSCREEN_PREFLIGHT_FAILED " + failure);
                }
                Object result = chain.proceed();
                if (view == null) return result;
                try {
                    boolean allowed = Boolean.TRUE.equals(result) && blankBefore && ready(view)
                            && blank(view, event.getRawX(), event.getRawY(), true)
                            && (event.getActionMasked() == MotionEvent.ACTION_DOWN ? nativeBlankTarget(view) : nativeBlank)
                            && outsideFingerprint(view.getDisplay(), event.getRawX(), event.getRawY());
                    if (enabled && event.getActionMasked() == MotionEvent.ACTION_UP && tracedTaps++ < 12) {
                        note.accept("LOCKSCREEN_TAP_CHECK allowed=" + allowed + " before=" + blankBefore
                                + " ready=" + ready(view) + " consumed=" + result + " target=" + touchTarget
                                + " blocked=" + blockedBy + " fingerprint=" + fingerprintKnown
                                + " focus=" + view.hasWindowFocus());
                    }
                    DesktopDoubleTap.Taps taps = gestures.computeIfAbsent(view, unused -> new DesktopDoubleTap.Taps());
                    ViewConfiguration config = ViewConfiguration.get(view.getContext());
                    if (taps.event(event.getActionMasked(), event.getEventTime(), event.getRawX(), event.getRawY(),
                            event.getPointerCount(), allowed, config.getScaledTouchSlop(),
                            config.getScaledDoubleTapSlop(), ViewConfiguration.getDoubleTapTimeout(),
                            ViewConfiguration.getLongPressTimeout()) && ready(view)) {
                        sleep.invoke(power, SystemClock.uptimeMillis(), 4, 0);
                        note.accept("LOCKSCREEN_DOUBLE_TAP_SLEEP");
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    gestures.clear();
                    if (tracedFailures++ < 3) note.accept("LOCKSCREEN_TAP_FAILED " + failure);
                }
                return result;
            });
            note.accept("LOCKSCREEN_DOUBLE_TAP_READY");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            note.accept("LOCKSCREEN_DOUBLE_TAP_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    static void connect(Application app, Consumer<String> note) {
        if (connected) return;
        power = app.getSystemService(PowerManager.class);
        keyguard = app.getSystemService(KeyguardManager.class);
        fingerprintKnown = readFingerprintAreas(app);
        if (!fingerprintKnown) note.accept("LOCKSCREEN_FINGERPRINT_UNAVAILABLE");
        Handler main = new Handler(Looper.getMainLooper());
        HandlerThread thread = new HandlerThread("NativeLockscreenSettings");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        HostSnapshot snapshot = HostSnapshot.open(app, "lockscreen_sleep");
        Runnable reload = new Runnable() {
            private int revision;
            @Override public void run() {
                main.removeCallbacks(this);
                enabled = false;
                gestures.clear();
                if (!fingerprintKnown) {
                    fingerprintAreas.clear();
                    fingerprintKnown = readFingerprintAreas(app);
                }
                int request = ++revision;
                worker.post(() -> {
                    HostSnapshot.Result read = snapshot.read(() -> app.getContentResolver().call(
                            android.net.Uri.parse("content://" + ShadeStore.AUTHORITY), "read", null, null));
                    if (read.report()) note.accept("LOCKSCREEN_SLEEP_SETTINGS " + read.summary());
                    main.post(() -> {
                        if (request != revision) return;
                        enabled = configEnabled(read);
                        if (!read.live()) main.postDelayed(this, snapshot.retryDelay(3000));
                    });
                });
            }
        };
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    if (getSentFromUid() == app.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid) reload.run();
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
            }
        }, new IntentFilter(ShadeStore.CHANGED), null, main, Context.RECEIVER_EXPORTED);
        IntentFilter state = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        state.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { gestures.clear(); }
        }, state, null, main, Context.RECEIVER_NOT_EXPORTED);
        connected = true;
        reload.run();
    }

    static boolean configEnabled(HostSnapshot.Result read) {
        return read != null && read.live() && read.config() != null
                && read.config().getBoolean("lockscreenDoubleTapSleep", false);
    }

    private static boolean ready(View view) throws ReflectiveOperationException {
        return enabled && fingerprintKnown && power != null && keyguard != null && power.isInteractive()
                && keyguard.isKeyguardLocked() && view.isAttachedToWindow()
                && view.getDisplay() != null && view.getDisplay().getDisplayId() == 0
                && nativeKeyguard(view);
    }

    private static boolean nativeKeyguard(View view) throws ReflectiveOperationException {
        Object handler = interaction.get(view);
        if (handler == null) return false;
        Object controller = owner.get(handler), stateController = status.get(controller);
        return (Integer)state.invoke(stateController) == 1 && upcoming.getInt(stateController) == 1
                && !(Boolean)dozing.invoke(stateController) && !(Boolean)dreaming.invoke(stateController)
                && !(Boolean)primaryShowing.invoke(primary.get(controller))
                && !(Boolean)alternateShowing.invoke(alternate.get(controller))
                && !launching.getBoolean(controller);
    }

    private static boolean outsideFingerprint(Display display, float x, float y) {
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int rotation = display.getRotation();
        int width = rotation % 2 == 0 ? size.x : size.y;
        int height = rotation % 2 == 0 ? size.y : size.x;
        int physicalWidth = width, physicalHeight = height;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() > physicalWidth) {
                physicalWidth = mode.getPhysicalWidth();
                physicalHeight = mode.getPhysicalHeight();
            }
        }
        float scale = Math.min((float)width/physicalWidth, (float)height/physicalHeight);
        for (Rect area : fingerprintAreas) {
            Rect mapped = fingerprintArea(area, scale, width, height, rotation);
            if (mapped.contains((int)x, (int)y)) return false;
        }
        return true;
    }

    static Rect fingerprintArea(Rect area, float scale, int width, int height, int rotation) {
        int left = (int)Math.floor(area.left*scale), top = (int)Math.floor(area.top*scale);
        int right = (int)Math.ceil(area.right*scale), bottom = (int)Math.ceil(area.bottom*scale);
        return switch (rotation) {
            case Surface.ROTATION_90 -> new Rect(top, width-right, bottom, width-left);
            case Surface.ROTATION_180 -> new Rect(width-right, height-bottom, width-left, height-top);
            case Surface.ROTATION_270 -> new Rect(height-bottom, left, height-top, right);
            default -> new Rect(left, top, right, bottom);
        };
    }

    private static boolean readFingerprintAreas(Context context) {
        try {
            if (!context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) return true;
            Object manager = context.getSystemService(Class.forName("android.hardware.fingerprint.FingerprintManager"));
            if (manager == null) return false;
            var properties = (List<?>) manager.getClass().getMethod("getSensorPropertiesInternal").invoke(manager);
            if (properties == null || properties.isEmpty()) return false;
            for (Object property : properties) {
                int type = property.getClass().getField("sensorType").getInt(property);
                // Under-display optical/ultrasonic sensors need explicit touch exclusion.
                if (type != 2 && type != 3) continue;
                var locations = (List<?>) property.getClass().getMethod("getAllLocations").invoke(property);
                if (locations == null || locations.isEmpty()) return false;
                for (Object location : locations) {
                    Class<?> c = location.getClass();
                    int x = c.getField("sensorLocationX").getInt(location);
                    int y = c.getField("sensorLocationY").getInt(location);
                    int radius = c.getField("sensorRadius").getInt(location);
                    if (radius <= 0) return false;
                    fingerprintAreas.add(new Rect(x-radius, y-radius, x+radius+1, y+radius+1));
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    static boolean protectedView(String name) {
        if (blankReceiver(name)) return false;
        return name.contains("NotificationRow") || name.contains("MediaHost")
                || name.contains("MediaContainer") || name.endsWith(".UdfpsView")
                || name.contains("Bouncer") || name.contains("KeyguardSecurity")
                || name.contains("KeyguardPassword") || name.contains("KeyguardPIN")
                || name.contains("KeyguardPattern") || name.contains("Affordance");
    }

    static boolean blankReceiver(String name) {
        return name.equals("com.android.systemui.shade.NotificationShadeWindowView")
                || name.equals("com.android.systemui.shade.NotificationPanelView")
                || name.equals("com.android.systemui.keyguard.ui.view.KeyguardRootView")
                || name.equals("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
                || name.equals("com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlayOverlappingTouchHandlingView")
                || name.equals("com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlayOverlappingView");
    }

    static boolean decorativeView(String name) {
        // Scrims span the window and may be clickable while a different native layer owns the touch.
        // A scrim that actually receives input is still rejected by nativeBlankTarget.
        return name.equals("com.android.systemui.scrim.ScrimView");
    }

    private static boolean nativeBlankTarget(View root) throws ReflectiveOperationException {
        View target = root;
        while (target instanceof ViewGroup group) {
            Object touch = firstTouchTarget.get(group);
            if (touch == null) break;
            target = (View)touchChild.get(touch);
            if (target == null) return false;
        }
        touchTarget = target.getClass().getName();
        return blankReceiver(touchTarget);
    }

    static boolean blank(View view, float x, float y, boolean root) {
        if (!view.isShown() || view.getAlpha() <= 0f) return true;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains((int)x, (int)y)) return !root;
        if (root && view.getRootWindowInsets() != null) {
            android.graphics.Insets safe = view.getRootWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.inset(safe.left, safe.top, safe.right, safe.bottom);
            if (!bounds.contains((int)x, (int)y)) return false;
        }
        String type = view.getClass().getName();
        if (decorativeView(type)) return true;
        if (notificationStack != null && notificationStack.isInstance(view)) {
            try {
                Object child = notificationAt.invoke(view, x, y);
                if (child != null) blockedBy = child.getClass().getName();
                return child == null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                blockedBy = "notification_hit_test_failed";
                return false;
            }
        }
        if (!root && (protectedView(type) || view instanceof EditText
                || !blankReceiver(type) && (view.isClickable() || view.isLongClickable()))) {
            blockedBy = type;
            return false;
        }
        if (view instanceof ViewGroup group) {
            for (int i=group.getChildCount()-1; i>=0; i--)
                if (!blank(group.getChildAt(i), x, y, false)) return false;
        }
        return true;
    }
}
