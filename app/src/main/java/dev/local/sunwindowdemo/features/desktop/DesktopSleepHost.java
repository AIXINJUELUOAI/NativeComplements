package dev.local.sunwindowdemo;

import android.app.ActivityManager;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.core.LauncherTargets;
import dev.local.sunwindowdemo.features.shade.ShadeStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Consumer;

/** SystemUI owns the sleep operation; an authenticated HOME can only request a fresh gesture. */
final class DesktopSleepHost {
    static final String STATE=Wire.PKG+".DESKTOP_SLEEP_STATE";
    static final String QUERY=Wire.PKG+".QUERY_DESKTOP_SLEEP_STATE";
    static final String CHANNEL=Wire.PKG+".desktopSleep.v1";
    private static DesktopSleepHost live;
    private final Application app;
    private final Consumer<String> log;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final HostSnapshot snapshot;
    private final PowerManager power;
    private final Method sleep;
    private volatile boolean enabled;
    private long lastSleep=-1;
    private int revision;

    static synchronized void connect(Application app,Consumer<String> note){
        if(live!=null||!BackBridge.PACKAGE.equals(app.getPackageName()))return;
        try{
            DesktopSleepHost host=new DesktopSleepHost(app,note);
            try{host.start();live=host;}
            catch(RuntimeException failure){host.worker.getLooper().quitSafely();throw failure;}
        }catch(Exception unavailable){note.accept("DESKTOP_SLEEP_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }

    private DesktopSleepHost(Application app,Consumer<String> log)throws Exception{
        this.app=app;this.log=log;
        power=app.getSystemService(PowerManager.class);
        if(power==null)throw new IllegalStateException("power service");
        sleep=PowerManager.class.getDeclaredMethod("goToSleep",long.class,int.class,int.class);
        if(sleep.getReturnType()!=void.class||Modifier.isStatic(sleep.getModifiers()))throw new NoSuchMethodException("goToSleep");
        sleep.setAccessible(true);
        snapshot=HostSnapshot.open(app,"desktop_sleep");
        HandlerThread thread=new HandlerThread("NativeDesktopSleep");thread.start();
        worker=new Handler(thread.getLooper());
    }

    private final Binder sleepChannel=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return false;
            int uid=Binder.getCallingUid();
            try{
                data.enforceInterface(CHANNEL);
                long eventTime=data.readLong();int display=data.readInt();data.enforceNoDataAvail();
                long identity=Binder.clearCallingIdentity();
                try{
                    if(!authorized(uid,eventTime,display))return true;
                }finally{Binder.restoreCallingIdentity(identity);}
                main.post(()->execute(uid,eventTime,display));
            }catch(RuntimeException rejected){/* Malformed or unauthenticated requests never sleep. */}
            return true;
        }
    };

    private void start(){
        IntentFilter filter=new IntentFilter(QUERY);filter.addAction(ShadeStore.CHANGED);
        app.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                int uid=getSentFromUid();
                if(QUERY.equals(intent.getAction())){
                    if(LauncherTargets.isLauncherUid(app,uid))publish(LauncherTargets.home(app));
                }else if(ShadeStore.CHANGED.equals(intent.getAction())){
                    try{
                        if(uid==app.getPackageManager().getApplicationInfo(Wire.PKG,0).uid)reload();
                    }catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
                }
            }
        },filter,null,main,Context.RECEIVER_EXPORTED);
        reload();
    }

    private final Runnable retry=this::reload;
    private void reload(){
        main.removeCallbacks(retry);
        int version=++revision;
        if(enabled){enabled=false;for(String target:LauncherTargets.targets(app))publish(target);}
        worker.post(()->{
            HostSnapshot.Result read=snapshot.read(()->app.getContentResolver().call(
                Uri.parse("content://"+ShadeStore.AUTHORITY),"read",null,null));
            boolean next=configEnabled(read);
            if(read.report())log.accept("DESKTOP_SLEEP_SETTINGS "+read.summary());
            main.post(()->{
                if(version!=revision)return;
                enabled=next;
                for(String target:LauncherTargets.targets(app))publish(target);
                main.removeCallbacks(retry);
                if(!read.live())main.postDelayed(retry,snapshot.retryDelay(3000));
            });
        });
    }

    // Unlike visual preferences, a stale cached setting must not authorize a power action.
    static boolean configEnabled(HostSnapshot.Result read){
        return read!=null&&read.live()&&read.config()!=null&&read.config().getBoolean("desktopDoubleTapSleep",false);
    }

    private void publish(String target){
        if(target==null||!target.equals(LauncherTargets.home(app)))return;
        Bundle extras=new Bundle();extras.putBoolean("enabled",enabled);
        if(enabled)extras.putBinder("sleepChannel",sleepChannel);
        Intent state=new Intent(STATE).setPackage(target).putExtras(extras);
        app.sendBroadcast(state,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    static boolean requestAllowed(boolean enabled,boolean trusted,int display,long event,long now,
                                  boolean interactive,boolean locked){
        return enabled&&trusted&&display==0&&event>=0&&event<=now&&now-event<=1000&&interactive&&!locked;
    }

    static boolean foregroundHome(Context context,ActivityManager.RunningTaskInfo task){
        if(task==null||task.topActivity==null)return false;
        try{
            if(ActivityManager.RunningTaskInfo.class.getField("displayId").getInt(task)!=0
                ||ActivityManager.RunningTaskInfo.class.getField("userId").getInt(task)
                    !=android.os.Process.myUid()/100000)return false;
            String home=LauncherTargets.home(context);
            if(home==null)return false;
            ResolveInfo resolved=context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
            return resolved!=null&&resolved.activityInfo!=null&&home.equals(resolved.activityInfo.packageName)
                &&task.topActivity.equals(new ComponentName(home,resolved.activityInfo.name));
        }catch(ReflectiveOperationException|RuntimeException unavailable){return false;}
    }

    private boolean authorized(int uid,long event,int display){
        try{
            KeyguardManager keyguard=app.getSystemService(KeyguardManager.class);
            if(!requestAllowed(enabled,LauncherTargets.isLauncherUid(app,uid),display,event,SystemClock.uptimeMillis(),
                power.isInteractive(),keyguard==null||keyguard.isKeyguardLocked()))return false;
            ActivityManager activities=app.getSystemService(ActivityManager.class);
            if(activities==null)return false;
            List<ActivityManager.RunningTaskInfo> tasks=activities.getRunningTasks(1);
            return !tasks.isEmpty()&&foregroundHome(app,tasks.get(0));
        }catch(RuntimeException unavailable){return false;}
    }

    private void execute(int uid,long event,int display){
        // Recheck after queueing: HOME, user, screen, setting or event age may have changed.
        if(event<=lastSleep||!authorized(uid,event,display))return;
        try{
            sleep.invoke(power,SystemClock.uptimeMillis(),4,0); // Native POWER_BUTTON reason; not device-admin lockNow.
            lastSleep=event;
        }catch(ReflectiveOperationException|RuntimeException unavailable){
            enabled=false;
            log.accept("DESKTOP_SLEEP_FAILED "+unavailable.getClass().getSimpleName());
            for(String target:LauncherTargets.targets(app))publish(target);
        }
    }
}
