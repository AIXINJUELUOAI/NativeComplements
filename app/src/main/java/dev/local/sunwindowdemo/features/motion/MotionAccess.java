package dev.local.sunwindowdemo.features.motion;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.os.*;
import dev.local.sunwindowdemo.BuildConfig;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Optional system-only feature. Provider calls and SensorService work always run outside WM locks. */
public final class MotionAccess {
    private final Handler worker;
    private final Consumer<String> log;
    private final MotionPolicy policy=new MotionPolicy();
    private final Map<Integer,String> targets=new HashMap<>(),applied=new HashMap<>();
    private final Set<Integer> retry=new HashSet<>();
    private Context context;
    private SensorCommand sensors;
    private boolean ready,enabled;
    private long lastFailure;
    private final Runnable expiry=this::reconcile;
    private MotionAccess(Consumer<String> log){
        this.log=log;HandlerThread thread=new HandlerThread("NativeComplements-Motion",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();worker=new Handler(thread.getLooper());
    }
    public static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        try{
            Class<?> activity=Class.forName("com.android.server.wm.ActivityRecord",false,loader);
            Class<?> state=Class.forName(activity.getName()+"$State",false,loader);
            Method changed=activity.getDeclaredMethod("setState",state,String.class);
            Method uid=activity.getDeclaredMethod("getUid"),pid=activity.getDeclaredMethod("getPid"),focused=activity.getDeclaredMethod("isFocusedActivityOnDisplay");
            changed.setAccessible(true);uid.setAccessible(true);pid.setAccessible(true);focused.setAccessible(true);
            if(changed.getReturnType()!=void.class||uid.getReturnType()!=int.class||pid.getReturnType()!=int.class||focused.getReturnType()!=boolean.class)
                throw new NoSuchMethodException("motion foreground signatures");
            MotionAccess access=new MotionAccess(log);
            module.hook(changed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // RESUMED is set before the client resume transaction. Same-UID page switches do not renew the deadline.
                Object result=chain.proceed();
                try{if("RESUMED".equals(String.valueOf(chain.getArg(0)))&&(Boolean)focused.invoke(chain.getThisObject())){
                    int u=(Integer)uid.invoke(chain.getThisObject()),p=(Integer)pid.invoke(chain.getThisObject());
                    long time=SystemClock.elapsedRealtime();
                    access.worker.post(()->{access.policy.foreground(u,p,time);access.reconcile();});
                }}catch(Exception ignored){}
                return result;
            });
            access.worker.postDelayed(access::connect,5000);log.accept("MOTION_FOREGROUND_HOOK_READY");
        }catch(Throwable e){log.accept("MOTION_UNAVAILABLE "+e.getClass().getSimpleName());}
    }
    private void connect(){
        try{
            Method property=Class.forName("android.os.SystemProperties").getDeclaredMethod("getBoolean",String.class,boolean.class);
            if(!(Boolean)property.invoke(null,"sys.boot_completed",false)){worker.postDelayed(this::connect,5000);return;}
            Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getDeclaredMethod("currentActivityThread").invoke(null);
            context=(Context)at.getDeclaredMethod("getSystemContext").invoke(thread);sensors=new SensorCommand();
            // Read-only capability check; no UID state is changed until an application rule needs it.
            sensors.execute("get-uid-state","android");ready=true;
            context.getContentResolver().registerContentObserver(MotionStore.URI,true,new ContentObserver(worker){@Override public void onChange(boolean self){reload();}});
            context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reload();}},
                packageFilter(),null,worker,Context.RECEIVER_NOT_EXPORTED);
            poll();
        }catch(Exception e){failure(e);status("系统传感器接口未就绪："+reason(e));worker.postDelayed(this::connect,10000);}
    }
    private static IntentFilter packageFilter(){IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);f.addDataScheme("package");return f;}
    private void poll(){reload();worker.postDelayed(this::poll,30000);}
    private void reload(){
        long identity=Binder.clearCallingIdentity();
        try{
            Bundle b=context.getContentResolver().call(MotionStore.URI,"read",null,null);if(b==null)throw new IllegalStateException("motion config missing");
            enabled=b.getBoolean("enabled");Map<Integer,Integer> rules=new HashMap<>();Map<Integer,String> next=new HashMap<>();
            String[] packages=b.getStringArray("packages");int[] modes=b.getIntArray("modes");
            if(enabled&&packages!=null&&modes!=null)for(int n=0;n<Math.min(300,Math.min(packages.length,modes.length));n++){
                String pkg=packages[n];if(modes[n]!=1&&modes[n]!=2||pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                    ||pkg.equals("dev.local.sunwindowdemo")||pkg.equals("com.android.systemui")||pkg.equals("com.google.android.apps.nexuslauncher"))continue;
                try{ApplicationInfo info=context.getPackageManager().getApplicationInfo(pkg,0);
                    if(info.uid<10000||(info.flags&ApplicationInfo.FLAG_PERSISTENT)!=0)continue;
                    // Shared UIDs cannot be isolated by package. The strongest selected rule wins.
                    rules.merge(info.uid,modes[n],Math::max);next.put(info.uid,pkg);
                }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            }
            targets.clear();targets.putAll(next);policy.configure(rules,SystemClock.elapsedRealtime());reconcile();
        }catch(Exception e){enabled=false;policy.configure(Map.of(),SystemClock.elapsedRealtime());reconcile();failure(e);status("配置暂不可用，已释放限制");}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    private void reconcile(){
        if(!ready)return;
        worker.removeCallbacks(expiry);long now=SystemClock.elapsedRealtime();String error="";
        Set<Integer> all=new HashSet<>(applied.keySet());all.addAll(targets.keySet());
        for(int uid:all){
            boolean block=enabled&&policy.blocked(uid,now);
            if(block==applied.containsKey(uid)&&!retry.contains(uid))continue;
            try{
                // Resolve again before touching an installed package; stale/reused UIDs do not inherit old rules.
                String pkg=block?targets.get(uid):applied.get(uid);String[] names=context.getPackageManager().getPackagesForUid(uid);
                if(names==null||names.length==0)continue;
                if(!Arrays.asList(names).contains(pkg))pkg=names[0];
                // Track a possibly applied command even if its completion reply times out.
                if(block){applied.put(uid,pkg);sensors.restrict(pkg);}else{sensors.restore(pkg);applied.remove(uid);}
                retry.remove(uid);
                log.accept("MOTION_UID "+(block?"BLOCK":"RESTORE")+" uid="+uid);
            }catch(Exception e){retry.add(uid);error="部分应用设置失败："+reason(e);failure(e);}
        }
        // Expiring the first-screen window restores native policy, including existing registrations.
        long next=policy.nextExpiry(now);
        if(next>0)worker.postDelayed(expiry,Math.max(1,next-now));
        if(!error.isEmpty())worker.postDelayed(expiry,5000);
        status(error);
    }
    private void status(String error){
        if(context==null)return;long identity=Binder.clearCallingIdentity();
        try{Bundle b=new Bundle();b.putString("error",error);context.getContentResolver().call(MotionStore.URI,"backend",BuildConfig.VERSION_NAME,b);}
        catch(Exception ignored){}finally{Binder.restoreCallingIdentity(identity);}
    }
    private void failure(Exception e){long now=SystemClock.elapsedRealtime();if(now-lastFailure>10000){lastFailure=now;log.accept("MOTION_ERROR "+reason(e));}}
    private static String reason(Throwable e){while(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();return e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
}
