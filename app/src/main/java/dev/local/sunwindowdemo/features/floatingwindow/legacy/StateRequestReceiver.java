package dev.local.sunwindowdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Public request can only resend actual state, never set it or launch anything. */
public final class StateRequestReceiver extends BroadcastReceiver {
    private static long last;
    @Override public void onReceive(Context context,Intent intent){
        if(!LauncherGuard.QUERY.equals(intent.getAction()))return;
        long now=android.os.SystemClock.elapsedRealtime();last=now;
        CornerService service=CornerService.live;
        if(service!=null)service.publish(true);
        else {
            context.sendBroadcast(new Intent(LauncherGuard.STATE).setPackage(LauncherGuard.PACKAGE).putExtra("enabled",false));
            context.sendBroadcast(new Intent(LauncherGuard.STATE).setPackage(BackBridge.PACKAGE).putExtra("dropEnabled",false));
        }
    }
}
