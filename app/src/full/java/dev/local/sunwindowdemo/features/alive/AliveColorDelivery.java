package dev.local.sunwindowdemo.features.alive;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Delivers one user-initiated palette through SystemUI's normal wallpaper color callback. */
public final class AliveColorDelivery {
    private static final String MODULE = "dev.nativecomplements.module";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ACTION = MODULE + ".alive.MANUAL_COLOR";
    private static volatile Host installed;

    private AliveColorDelivery() {}

    static void beforeNotify(Context context, Handler main, WallpaperColors colors, Runnable notify) {
        BroadcastReceiver completion = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (main.hasCallbacks(notify)) {
                    main.removeCallbacks(notify);
                    notify.run();
                }
            }
        };
        main.postDelayed(notify, 350);
        try {
            Intent intent = new Intent(ACTION).setPackage(SYSTEM_UI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("colors", colors);
            context.sendOrderedBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    completion, main, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException unavailable) {
            main.removeCallbacks(notify);
            notify.run();
        }
    }

    static void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        XposedInterface.HookHandle handle = null;
        try {
            Host host = new Host(loader);
            handle = module.hook(host.callback).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object owner = null;
                try {
                    owner = host.admit(chain.getThisObject(), (WallpaperColors) chain.getArg(0),
                            (int) chain.getArg(1), (int) chain.getArg(2));
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    log.accept("ALIVE_COLOR_DELIVERY_UNAVAILABLE " + unavailable.getClass().getSimpleName());
                }
                if (owner == null) return chain.proceed();
                android.util.Log.i("NativeComplements", "ALIVE_COLOR_DELIVERY awake manual apply");
                try { return chain.proceed(); }
                finally { host.acceptEvents.setBoolean(owner, false); }
            });
            if (!module.deoptimize(host.callback)) throw new IllegalStateException("wallpaper color callback deoptimize");
            installed = host;
            log.accept("ALIVE_COLOR_DELIVERY_READY");
        } catch (Throwable unavailable) {
            if (handle != null) try { handle.unhook(); } catch (Throwable ignored) {}
            log.accept("ALIVE_COLOR_DELIVERY_UNAVAILABLE " + unavailable.getClass().getSimpleName());
        }
    }

    public static void connect(Context context, Consumer<String> log) {
        Host host = installed;
        if (host == null || host.context != null) return;
        try { host.connect(context.getApplicationContext()); }
        catch (RuntimeException unavailable) { log.accept("ALIVE_COLOR_RECEIVER_UNAVAILABLE " + unavailable.getClass().getSimpleName()); }
    }

    record Pending(int user, int wallpaperId, WallpaperColors colors, long deadline) {
        boolean matches(int userId, int which, WallpaperColors value, long now) {
            return user == userId && now < deadline && (which & WallpaperManager.FLAG_SYSTEM) != 0 && colors.equals(value);
        }
    }

    private static final class Host {
        final Field callbackOwner, acceptEvents, userTracker;
        final Method callback, currentUser;
        Context context;
        Pending pending;

        Host(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> controller = AliveSystemUi.type(loader, "com.android.systemui.theme.ThemeOverlayController");
            Field listener = controller.getDeclaredField("mOnColorsChangedListener");
            Class<?> listenerType = listener.getType();
            if (!WallpaperManager.OnColorsChangedListener.class.isAssignableFrom(listenerType)) {
                throw new NoSuchFieldException("wallpaper color listener owner");
            }
            callbackOwner = AliveSystemUi.field(listenerType, "this$0", controller);
            acceptEvents = AliveSystemUi.field(controller, "mAcceptColorEvents", boolean.class);
            Class<?> tracker = AliveSystemUi.type(loader, "com.android.systemui.settings.UserTracker");
            userTracker = AliveSystemUi.field(controller, "mUserTracker", tracker);
            Class<?> trackerImpl = AliveSystemUi.type(loader, "com.android.systemui.settings.UserTrackerImpl");
            currentUser = trackerImpl.getMethod("getUserId");
            callback = listenerType.getDeclaredMethod("onColorsChanged", WallpaperColors.class, int.class, int.class);
            if (callback.getReturnType() != void.class || currentUser.getReturnType() != int.class) {
                throw new NoSuchMethodException("wallpaper color callback ABI");
            }
        }

        void connect(Context app) {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    int uid = getSentFromUid();
                    if (uid < 0 || !MODULE.equals(getSentFromPackage()) || !ACTION.equals(intent.getAction())
                            || !UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle())) return;
                    try {
                        if (uid != app.getPackageManager().getPackageUid(MODULE, 0) || !awake(app)) return;
                        WallpaperColors colors = intent.getParcelableExtra("colors", WallpaperColors.class);
                        int wallpaper = aliveHomeId(app);
                        if (colors != null && wallpaper > 0) pending = new Pending(uid / 100000, wallpaper, colors,
                                SystemClock.uptimeMillis() + 2500);
                    } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignoredFailure) {
                        // Invalid sender, unavailable wallpaper or an unreadable parcel keeps the native path.
                    }
                }
            }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
            context = app;
        }

        Object admit(Object listener, WallpaperColors colors, int which, int user) throws ReflectiveOperationException {
            if (Looper.myLooper() != Looper.getMainLooper() || pending == null) return null;
            long now = SystemClock.uptimeMillis();
            if (now >= pending.deadline) { pending = null; return null; }
            if (!pending.matches(user, which, colors, now)) return null;
            Object owner = callbackOwner.get(listener);
            Object tracker = userTracker.get(owner);
            if (!currentUser.getDeclaringClass().isInstance(tracker)
                    || (int) currentUser.invoke(tracker) != user || !awake(context)
                    || aliveHomeId(context) != pending.wallpaperId) return null;
            pending = null;
            if (acceptEvents.getBoolean(owner)) return null;
            acceptEvents.setBoolean(owner, true);
            return owner;
        }
    }

    private static boolean awake(Context context) {
        return context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private static int aliveHomeId(Context context) {
        WallpaperManager manager = context.getSystemService(WallpaperManager.class);
        WallpaperInfo info = manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM);
        if (info == null) return -1;
        ComponentName active = info.getComponent();
        if (!MODULE.equals(active.getPackageName())
                || !AliveWallpaperService.class.getName().equals(active.getClassName())
                && !AliveAmbientWallpaperService.class.getName().equals(active.getClassName())) return -1;
        return manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
    }
}
