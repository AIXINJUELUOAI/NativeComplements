package dev.local.sunwindowdemo.features.alive;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Resolves the host live-wallpaper entry point and applies only the module's own services. */
public final class AliveWallpaperPicker {
    private static final int BOTH = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
    private AliveWallpaperPicker() {}

    static boolean owns(ComponentName target) {
        return target != null
            && "dev.nativecomplements.module".equals(target.getPackageName())
            && (AliveWallpaperService.class.getName().equals(target.getClassName())
                || AliveAmbientWallpaperService.class.getName().equals(target.getClassName()));
    }

    static Intent change(ComponentName target) {
        return new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, target);
    }

    static Intent chooser() {
        return new Intent(Intent.ACTION_SET_WALLPAPER);
    }

    /**
     * LineageOS' picker does not export the framework preview activity. Ask the hooked
     * WallpaperManagerService to bind the exact own component instead. Hidden-API filtering or a
     * missing system_server hook returns false so the caller can open the native picker.
     */
    static boolean applyDirect(Context context, ComponentName target) {
        if (!owns(target)) return false;
        // The hooked system_server receiver applies only the exact own component through the
        // native WallpaperManagerService policy checks. It does not require hidden APIs.
        if (requestBridge(context, target)) return true;
        WallpaperManager manager = context.getSystemService(WallpaperManager.class);
        if (manager == null) return false;
        try {
            Method method = WallpaperManager.class.getDeclaredMethod(
                "setWallpaperComponentWithFlags", ComponentName.class, int.class);
            method.setAccessible(true);
            Object result = method.invoke(manager, target, BOTH);
            if (Boolean.TRUE.equals(result)) return true;
        } catch (NoSuchMethodException | IllegalAccessException | RuntimeException absent) {
            // Hidden-API policy on this build; try the older unsupported entry point.
        } catch (InvocationTargetException failure) {
            if (!(failure.getCause() instanceof RuntimeException)) return false;
        }
        try {
            Method method = WallpaperManager.class.getDeclaredMethod(
                "setWallpaperComponent", ComponentName.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(manager, target));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean requestBridge(Context context, ComponentName target) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger(Activity.RESULT_CANCELED);
        ResultReceiver reply = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                code.set(resultCode);
                latch.countDown();
            }
        };
        try {
            context.sendBroadcast(new Intent(AliveWallpaperProtocol.ACTION).setPackage("android")
                    .putExtra(AliveWallpaperProtocol.EXTRA_COMPONENT, target)
                    .putExtra(AliveWallpaperProtocol.EXTRA_RESULT, reply), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            return latch.await(2500, TimeUnit.MILLISECONDS)
                && code.get() == Activity.RESULT_OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
