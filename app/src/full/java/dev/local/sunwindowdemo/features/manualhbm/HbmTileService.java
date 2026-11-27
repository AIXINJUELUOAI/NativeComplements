package dev.local.sunwindowdemo.features.manualhbm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import dev.local.sunwindowdemo.R;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class HbmTileService extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            request(false);
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    public void onStartListening() {
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    public void onStopListening() {
        handler.removeCallbacks(refresh);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    @Override
    public void onClick() {
        if (isLocked()) unlockAndRun(() -> request(true));
        else request(true);
    }

    private void request(boolean toggle) {
        Intent intent = new Intent(HbmControl.ACTION).setPackage("android")
                .putExtra(HbmControl.TOGGLE, toggle);
        sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent result) {
                updateTile(getResultCode());
            }
        }, null, HbmControl.UNAVAILABLE, null, null);
    }

    private void updateTile(int status) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(status == HbmControl.ON ? Tile.STATE_ACTIVE
                : status == HbmControl.UNAVAILABLE ? Tile.STATE_UNAVAILABLE : Tile.STATE_INACTIVE);
        tile.setSubtitle(switch (status) {
            case HbmControl.ON -> getText(R.string.manual_hbm_on);
            case HbmControl.AUTO -> getText(R.string.manual_hbm_auto);
            case HbmControl.PROTECTED -> getText(R.string.manual_hbm_protected);
            case HbmControl.UNAVAILABLE -> getText(R.string.manual_hbm_unavailable);
            default -> null;
        });
        tile.updateTile();
    }
}
