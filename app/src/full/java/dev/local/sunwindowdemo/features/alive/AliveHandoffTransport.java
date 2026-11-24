package dev.local.sunwindowdemo.features.alive;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;
import java.util.function.Consumer;

/** Explicit, sender-attributed delivery; unlike wallpaper commands this does not require a wallpaperTarget window. */
public final class AliveHandoffTransport {
    private static final String ACTION="dev.nativecomplements.module.alive.HANDOFF";
    private static final String SYSTEM_UI="com.android.systemui";
    private AliveHandoffTransport() {}
    public record Envelope(String action,Bundle extras) {}
    static boolean acceptsSender(int ownUid,int expected,int sent,String sentPackage){
        return expected>=0&&expected/100000==ownUid/100000&&sent==expected&&SYSTEM_UI.equals(sentPackage);
    }
    public static void send(Context context,String action,Bundle extras){
        BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
        Intent intent=new Intent(ACTION).setPackage("dev.nativecomplements.module").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("command",action).putExtra("payload",extras);
        context.sendBroadcast(intent,null,options.toBundle());
    }
    public static BroadcastReceiver install(Context context,Consumer<Envelope> receive){
        final int expected;
        try{expected=context.getPackageManager().getApplicationInfo(SYSTEM_UI,0).uid;}
        catch(android.content.pm.PackageManager.NameNotFoundException absent){return null;}
        if(expected/100000!=Process.myUid()/100000)return null;
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context ignored,Intent intent){
                if(!acceptsSender(Process.myUid(),expected,getSentFromUid(),getSentFromPackage())||!ACTION.equals(intent.getAction()))return;
                try{
                    String command=intent.getStringExtra("command");
                    if(!AliveSleepHandoff.ACTION_PREPARE.equals(command)&&!AliveSleepHandoff.ACTION_PREPARE_WAKE.equals(command)
                            &&!AliveSleepHandoff.ACTION_CANCEL.equals(command))return;
                    Bundle extras=intent.getBundleExtra("payload");
                    if(extras!=null)receive.accept(new Envelope(command,extras));
                }catch(RuntimeException malformed){/* An invalid message never changes the Engine's current episode. */}
            }
        };
        context.registerReceiver(receiver,new IntentFilter(ACTION),Context.RECEIVER_EXPORTED);
        return receiver;
    }
}
