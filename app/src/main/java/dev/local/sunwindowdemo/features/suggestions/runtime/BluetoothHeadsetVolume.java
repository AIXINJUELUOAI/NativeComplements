package dev.local.sunwindowdemo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Caps the initial media volume of the routed Bluetooth headset once per connection. */
@SuppressLint({"MissingPermission", "BlockedPrivateApi"})
final class BluetoothHeadsetVolume implements AutoCloseable {
    record Hint(String description, boolean reduced) {}
    private static final int STREAM = AudioManager.STREAM_MUSIC;
    private static final AudioAttributes MEDIA =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    private final AudioManager audio;
    private final BluetoothAdapter bluetooth;
    private final HandlerThread thread = new HandlerThread("NativeComplements:HeadsetVolume");
    private final Handler worker;
    private final Handler main;
    private final Consumer<Hint> hint;
    private final Set<Integer> handled = new HashSet<>();
    private final Runnable apply = this::applyToHeadset;
    private int limit;
    private int routedId = -1;
    private boolean callbackRegistered;
    private Object routeListener;
    private Method unregisterRouteListener;
    private volatile boolean closed;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { schedule(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            for (var device : devices) handled.remove(device.getId());
            schedule();
        }
    };

    BluetoothHeadsetVolume(Context context, AudioManager audio, Handler main, Consumer<Hint> hint) {
        this.audio = audio;
        var manager = context.getSystemService(BluetoothManager.class);
        bluetooth = manager == null ? null : manager.getAdapter();
        this.main = main;
        this.hint = hint;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::observe);
    }

    void configure(int newLimit) {
        worker.post(() -> {
            if (limit == newLimit) return;
            limit = newLimit;
            schedule();
        });
    }

    private void observe() {
        try {
            audio.registerAudioDeviceCallback(deviceCallback, worker);
            callbackRegistered = true;
        } catch (RuntimeException unavailable) {
            Log.i("NativeComplements", "SUGGESTIONS_HEADSET_DEVICE_CALLBACK_UNAVAILABLE");
        }
        try {
            var type = Class.forName("android.media.AudioManager$OnDevicesForAttributesChangedListener");
            var add = AudioManager.class.getMethod("addOnDevicesForAttributesChangedListener",
                    AudioAttributes.class, Executor.class, type);
            var remove = AudioManager.class.getMethod("removeOnDevicesForAttributesChangedListener", type);
            Object listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "onDevicesForAttributesChanged" -> { schedule(); yield null; }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "NativeComplements:HeadsetVolume";
                        default -> null;
                    });
            add.invoke(audio, MEDIA, (Executor) worker::post, listener);
            routeListener = listener;
            unregisterRouteListener = remove;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            Log.i("NativeComplements", "SUGGESTIONS_HEADSET_ROUTE_CALLBACK_UNAVAILABLE");
        }
    }

    private void schedule() {
        worker.removeCallbacks(apply);
        if (!closed && limit > 0) worker.postDelayed(apply, 550);
    }

    private AudioDeviceInfo routedOutput() {
        var routes = audio.getAudioDevicesForAttributes(MEDIA);
        return routes.size() == 1 && routes.get(0).isSink() ? routes.get(0) : null;
    }

    private void applyToHeadset() {
        try {
            if (closed || limit == 0 || audio.isVolumeFixed()) return;
            var route = routedOutput();
            int id = route == null ? -1 : route.getId();
            if (routedId != id) {
                routedId = id;
                publish(null);
            }
            if (route == null || handled.contains(id) || !isHeadset(route)) return;
            int target = Math.min(limit, audio.getStreamMaxVolume(STREAM));
            if (target < 1 || target < audio.getStreamMinVolume(STREAM)) return;
            int previous = audio.getStreamVolume(STREAM);
            boolean muted = audio.isStreamMute(STREAM) || previous == 0;
            var confirmed = routedOutput();
            if (confirmed == null || confirmed.getId() != id) return;
            if (!muted && previous > target) {
                audio.setStreamVolume(STREAM, target, 0);
                worker.postDelayed(() -> confirmVolume(id, previous), 200);
            } else {
                publish(new Hint(muted ? "蓝牙耳机媒体已静音" : "蓝牙耳机媒体音量 " + previous + " 档", false));
            }
            handled.add(id);
        } catch (RuntimeException | LinkageError unavailable) {
            Log.i("NativeComplements", "SUGGESTIONS_HEADSET_VOLUME_UNAVAILABLE");
        }
    }

    private void confirmVolume(int id, int previous) {
        try {
            if (closed || limit == 0 || !handled.contains(id)) return;
            var route = routedOutput();
            if (route == null || route.getId() != id) return;
            int current = audio.getStreamVolume(STREAM);
            if (audio.isStreamMute(STREAM) || current == 0) {
                publish(new Hint("蓝牙耳机媒体已静音", false));
            } else {
                boolean reduced = current < previous;
                publish(new Hint((reduced ? "蓝牙耳机媒体音量已降至 " : "蓝牙耳机媒体音量 ") + current + " 档", reduced));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            Log.i("NativeComplements", "SUGGESTIONS_HEADSET_VOLUME_CHECK_UNAVAILABLE");
        }
    }

    private boolean isHeadset(AudioDeviceInfo device) {
        if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
        if (device.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || bluetooth == null
                || !BluetoothAdapter.checkBluetoothAddress(device.getAddress())) return false;
        var type = bluetooth.getRemoteDevice(device.getAddress()).getBluetoothClass();
        if (type == null) return false;
        int kind = type.getDeviceClass();
        return kind == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                || kind == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET;
    }

    private void publish(Hint result) {
        main.post(() -> { if (!closed) hint.accept(result); });
    }

    @Override public void close() {
        closed = true;
        worker.post(() -> {
            worker.removeCallbacksAndMessages(null);
            try {
                if (callbackRegistered) audio.unregisterAudioDeviceCallback(deviceCallback);
            } catch (RuntimeException ignored) {}
            try {
                if (routeListener != null) unregisterRouteListener.invoke(audio, routeListener);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
            thread.quitSafely();
        });
    }
}
