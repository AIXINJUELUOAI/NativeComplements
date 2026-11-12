package dev.local.sunwindowdemo;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;

/** Coalesced, bounded diagnostic snapshot; no user content and no IPC on hook hot paths. */
final class RotationDiagnostics {
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final Bundle state=new Bundle();
    private static Context context;
    private static boolean queued;
    static synchronized void connect(Context c){context=c.getApplicationContext();update("version",BuildConfig.VERSION_NAME);}
    static synchronized void update(String key,String value){
        if(value==null||value.equals(state.getString(key)))return;
        state.putString(key,value);
        if(context==null||queued)return;
        queued=true;
        worker.execute(()->{
            Bundle snapshot;Context c;
            synchronized(RotationDiagnostics.class){snapshot=new Bundle(state);c=context;queued=false;}
            try{c.getContentResolver().call(Uri.parse("content://"+SuggestionStore.AUTHORITY),"rotation_status",null,snapshot);}
            catch(RuntimeException ignored){} // Diagnostics must never affect native rotation.
        });
    }
}
