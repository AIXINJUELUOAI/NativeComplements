package dev.local.sunwindowdemo.features.alive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Unlocked upgrade/boot migration only; never starts, rebinds or selects a wallpaper. */
public final class AliveBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)&&!Intent.ACTION_BOOT_COMPLETED.equals(action))return;
        PendingResult pending=goAsync();
        new Thread(()->{
            try{
                boolean ready=AliveSettings.prepareRuntime(context);
                android.util.Log.i("NativeComplements","ALIVE_DIRECT_BOOT prepared="+ready);
            }catch(Exception failure){android.util.Log.w("NativeComplements","ALIVE_DIRECT_BOOT unavailable",failure);}
            finally{pending.finish();}
        },"AliveBootPrepare").start();
    }
}
