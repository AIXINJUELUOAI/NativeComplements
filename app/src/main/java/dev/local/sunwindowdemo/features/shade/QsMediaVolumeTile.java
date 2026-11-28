package dev.local.sunwindowdemo.features.shade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import dev.local.sunwindowdemo.R;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class QsMediaVolumeTile extends TileService {
    private boolean listening;
    private final BroadcastReceiver volumeUpdates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                update(getSystemService(AudioManager.class));
            }
        }
    };

    @Override public void onStartListening() {
        super.onStartListening();
        if (!listening && PixelOsShade.supports(this)) {
            registerReceiver(volumeUpdates, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), RECEIVER_NOT_EXPORTED);
            listening = true;
        }
        update(getSystemService(AudioManager.class));
    }

    @Override public void onStopListening() {
        stopVolumeUpdates();
        super.onStopListening();
    }

    @Override public void onDestroy() {
        stopVolumeUpdates();
        super.onDestroy();
    }

    private void stopVolumeUpdates() {
        if (!listening) return;
        unregisterReceiver(volumeUpdates);
        listening = false;
    }

    @Override public void onClick() {
        var audio = getSystemService(AudioManager.class);
        if (!PixelOsShade.supports(this) || audio == null || audio.isVolumeFixed()) return;
        int stream = AudioManager.STREAM_MUSIC;
        int minimum = audio.getStreamMinVolume(stream);
        int maximum = audio.getStreamMaxVolume(stream);
        if (maximum <= minimum) return;
        int percent = QsSliderPreferences.read(this, QsSliderPreferences.Option.VOLUME_UNMUTE);
        int unmuted = Math.clamp(minimum + Math.round((maximum - minimum) * percent / 100f), minimum + 1, maximum);
        audio.setStreamVolume(stream, audio.getStreamVolume(stream) > minimum ? minimum : unmuted, 0);
        new Handler(getMainLooper()).postDelayed(() -> update(audio), 150);
    }

    private void update(AudioManager audio) {
        var tile = getQsTile();
        if (tile == null) return;
        int stream = AudioManager.STREAM_MUSIC;
        if (!PixelOsShade.supports(this) || audio == null || audio.isVolumeFixed()) {
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else {
            boolean muted = audio.getStreamVolume(stream) <= audio.getStreamMinVolume(stream);
            tile.setState(muted ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
            tile.setSubtitle(getString(muted ? R.string.qs_media_muted : R.string.qs_media_unmuted));
        }
        tile.updateTile();
    }
}
