package dev.local.sunwindowdemo;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, single-package UID exception for native audio/focus foreground evaluations only. */
final class WindowAudioContinuation {
    static final long VALID_MS=1000;
    private static final int[] OPS={32,154,155};
    record Grant(String pkg,IBinder lease) {}
    private record Entry(Grant grant,IBinder.DeathRecipient death) {}
    private record Snapshot(Map<Integer,Entry> entries,long until) {}
    private final FrameworkAccess access;
    private final Consumer<String> log;
    private final Object gate=new Object();
    private volatile Snapshot snapshot=new Snapshot(Map.of(),0);
    private volatile boolean disabled;
    private volatile Context context;
    private volatile Object service;
    private volatile Handler handler;
    private Method notify;
    private long epoch;
    private boolean timerQueued;
    private long nextRefreshLog;
    private final Runnable expiry=this::expire;
    WindowAudioContinuation(FrameworkAccess access,Consumer<String> log){this.access=access;this.log=log;}

    static WindowAudioContinuation install(XposedModule module,FrameworkAccess access,List<XposedInterface.HookHandle> handles,Consumer<String> log){
        WindowAudioContinuation bridge=new WindowAudioContinuation(access,log);
        try{
            Class<?> ops=AppOpsManager.class;
            if(access.field(ops,"OP_TAKE_AUDIO_FOCUS").getInt(null)!=32
                ||access.field(ops,"OP_CONTROL_AUDIO").getInt(null)!=154||access.field(ops,"OP_CONTROL_AUDIO_PARTIAL").getInt(null)!=155)
                throw new NoSuchFieldException("audio operation identity");
            Method defaults=access.method(ops,"opToDefaultMode",int.class);
            if(defaults.getReturnType()!=int.class||!java.lang.reflect.Modifier.isStatic(defaults.getModifiers()))
                throw new NoSuchMethodException("audio default mode");
            if((Integer)defaults.invoke(null,32)!=4||(Integer)defaults.invoke(null,154)!=4||(Integer)defaults.invoke(null,155)!=4)
                throw new IllegalStateException("audio foreground defaults");
            ClassLoader loader=access.task.getClassLoader();
            Method eval=access.method(Class.forName("com.android.server.appop.AppOpsUidStateTrackerImpl",false,loader),
                "evalMode",int.class,int.class,int.class);
            Method uidEval=access.method(Class.forName("com.android.server.appop.AppOpsService$UidState",false,loader),
                "evalMode",int.class,int.class);
            Method foreground=access.method(Class.forName("com.android.server.appop.AppOpsService",false,loader),
                "evaluateForegroundMode",int.class,int.class,int.class);
            for(Method method:List.of(eval,uidEval,foreground))if(method.getReturnType()!=int.class
                ||java.lang.reflect.Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException("audio foreground evaluation");
            return bridge.installEvaluation(module,eval,List.of(uidEval,foreground),handles)?bridge:null;
        }catch(Throwable error){
            bridge.disabled=true;
            try{log.accept("EDGE_AUDIO_UNAVAILABLE "+error.getClass().getSimpleName());}catch(Throwable ignored){}
            return null;
        }
    }
    boolean installEvaluation(XposedModule module,Method eval,List<Method> deopts,List<XposedInterface.HookHandle> handles){
        List<XposedInterface.HookHandle> local=new ArrayList<>();
        try{
            for(Method method:deopts)if(!module.deoptimize(method))throw new IllegalStateException("audio evaluation deopt");
            local.add(module.hook(eval).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed(); // Exactly once; native Throwable/result identity is not diagnostic recovery.
                try{
                    int nativeMode=(Integer)result;
                    int evaluated=evaluate((Integer)chain.getArg(0),(Integer)chain.getArg(1),(Integer)chain.getArg(2),nativeMode);
                    return evaluated==nativeMode?result:evaluated;
                }catch(Throwable observation){return result;}
            }));
            handles.addAll(local);return true;
        }catch(Throwable error){
            disabled=true;
            try{handles.removeAll(local);}catch(Throwable ignored){}
            for(XposedInterface.HookHandle handle:local)try{handle.unhook();}catch(Throwable ignored){}
            try{log.accept("EDGE_AUDIO_UNAVAILABLE "+error.getClass().getSimpleName());}catch(Throwable ignored){}
            return false;
        }
    }
    void attach(Context context){
        try{
            Object nativeService=access.field(context.getSystemService(AppOpsManager.class),"mService");
            if(!Class.forName("com.android.server.appop.AppOpsService",false,access.task.getClassLoader()).isInstance(nativeService))
                throw new IllegalStateException("local AppOps service");
            attach(context,nativeService);
        }catch(Exception|LinkageError error){disable(Set.of(),error);}
    }
    // Also exercises the native reflection contract with a local service in host tests.
    void attach(Context context,Object nativeService){
        try{
            Handler nativeHandler=(Handler)access.field(nativeService,"mHandler");
            Method changed=access.method(nativeService.getClass(),"notifyWatchersOnDefaultDevice",int.class,int.class);
            if(nativeHandler==null||changed.getReturnType()!=void.class)throw new NoSuchMethodException("audio mode notification");
            synchronized(gate){if(disabled)return;this.context=context;service=nativeService;notify=changed;handler=nativeHandler;}
            log.accept("EDGE_AUDIO_READY ops=32,154,155");
        }catch(Exception|LinkageError error){disable(Set.of(),error);}
    }
    void close(){synchronized(gate){disabled=true;}clear();}
    long epoch(){synchronized(gate){return epoch;}}
    int evaluate(int uid,int op,int rawMode,int nativeMode){
        if(disabled||rawMode!=4||nativeMode!=1||(op!=32&&op!=154&&op!=155))return nativeMode;
        Snapshot current=snapshot;Entry entry=current.entries.get(uid);
        return entry!=null&&SystemClock.elapsedRealtime()<current.until&&entry.grant.lease.isBinderAlive()?0:nativeMode;
    }
    /** Called on the controller worker, after leaving WM; package membership is deliberately never cached. */
    void update(long expectedEpoch,Map<Integer,Grant> desired){
        Context currentContext=context;Handler currentHandler=handler;
        if(disabled||currentContext==null||currentHandler==null)return;
        Map<Integer,Entry> checked=new HashMap<>();Snapshot before=snapshot;
        try{
            PackageManager pm=currentContext.getPackageManager();
            for(var item:desired.entrySet()){
                int uid=item.getKey();Grant grant=item.getValue();
                if(uid<10000||grant==null||grant.pkg==null||grant.lease==null||!grant.lease.isBinderAlive())continue;
                String[] packages=pm.getPackagesForUid(uid);
                if(packages==null||packages.length!=1||!grant.pkg.equals(packages[0])
                    ||pm.getApplicationInfo(grant.pkg,0).uid!=uid||pm.getPackageInfo(grant.pkg,0).sharedUserId!=null)continue;
                Entry entry=before.entries.get(uid);
                if(entry==null||!entry.grant.equals(grant)){
                    IBinder.DeathRecipient death=()->leaseDied(uid,grant.lease);
                    grant.lease.linkToDeath(death,0);entry=new Entry(grant,death);
                }
                checked.put(uid,entry);
            }
        }catch(Exception|LinkageError error){unlinkNew(checked,before.entries);disable(Set.of(),error);return;}
        boolean schedule=false;
        synchronized(gate){
            if(!disabled&&epoch==expectedEpoch&&!checked.isEmpty()&&!timerQueued){timerQueued=true;schedule=true;}
        }
        // Arm the independent native-handler expiry BEFORE publishing any new authorization.
        if(schedule&&!currentHandler.postDelayed(expiry,VALID_MS)){
            unlinkNew(checked,before.entries);disable(before.entries.keySet(),new IllegalStateException("audio expiry rejected"));return;
        }
        Snapshot after;
        synchronized(gate){
            if(disabled||epoch!=expectedEpoch||!checked.isEmpty()&&!timerQueued){after=null;}
            else{after=new Snapshot(Map.copyOf(checked),SystemClock.elapsedRealtime()+VALID_MS);snapshot=after;}
        }
        if(after==null){unlinkNew(checked,before.entries);refreshLog(desired.size(),expectedEpoch,before);return;}
        unlinkNew(before.entries,after.entries);
        Set<Integer> changed=new HashSet<>(before.entries.keySet());changed.addAll(after.entries.keySet());
        if(before.until>SystemClock.elapsedRealtime())
            changed.removeIf(uid->before.entries.containsKey(uid)&&after.entries.containsKey(uid));
        postNotifications(changed);
        // A lease can die between linkToDeath and publication; do not miss that first death callback.
        if(after.entries.values().stream().anyMatch(entry->!entry.grant.lease.isBinderAlive()))clear();
        refreshLog(desired.size(),expectedEpoch,before);
    }
    private void refreshLog(int desired,long expectedEpoch,Snapshot before){
        if(desired==0&&before.entries.isEmpty())return;
        String line;
        synchronized(gate){
            long now=SystemClock.elapsedRealtime();if(now<nextRefreshLog)return;nextRefreshLog=now+30_000;
            Snapshot current=snapshot;
            line="EDGE_AUDIO_REFRESH desired="+desired+" granted="+current.entries.size()+" epochValid="+(epoch==expectedEpoch)
                +" ttlMillis="+Math.max(0,current.until-now)+" disabled="+disabled;
        }
        try{log.accept(line);}catch(Throwable ignored){/* Numeric observation must not affect publication/revocation. */}
    }
    void clear(){
        Snapshot old;
        synchronized(gate){epoch++;old=snapshot;snapshot=new Snapshot(Map.of(),0);}
        unlinkNew(old.entries,Map.of());postNotifications(old.entries.keySet());
    }
    private void leaseDied(int uid,IBinder lease){
        Snapshot old;
        synchronized(gate){
            Entry entry=snapshot.entries.get(uid);if(entry==null||entry.grant.lease!=lease)return;
            epoch++;old=snapshot;snapshot=new Snapshot(Map.of(),0);
        }
        unlinkNew(old.entries,Map.of());postNotifications(old.entries.keySet());
    }
    private void expire(){
        Snapshot old=null;long delay=0;
        synchronized(gate){
            timerQueued=false;
            if(!snapshot.entries.isEmpty()){
                delay=snapshot.until-SystemClock.elapsedRealtime();
                if(delay<=0){epoch++;old=snapshot;snapshot=new Snapshot(Map.of(),0);}
                else timerQueued=true;
            }
        }
        if(old!=null){unlinkNew(old.entries,Map.of());postNotifications(old.entries.keySet());}
        if(delay>0&&!handler.postDelayed(expiry,delay))disable(Set.of(),new IllegalStateException("audio expiry rejected"));
    }
    private void postNotifications(Set<Integer> uids){
        if(uids.isEmpty())return;
        Handler target=handler;if(target==null)return;
        Set<Integer> copied=Set.copyOf(uids);
        if(!target.post(()->{
            try{notifyNative(copied);}
            catch(Exception|LinkageError error){disable(copied,error);}
        }))disable(copied,new IllegalStateException("audio notification rejected"));
    }
    private void notifyNative(Set<Integer> uids)throws Exception{
        for(int uid:uids)for(int op:OPS)notify.invoke(service,op,uid);
    }
    private void disable(Set<Integer> extra,Throwable error){
        Snapshot old;boolean first;
        synchronized(gate){first=!disabled;disabled=true;epoch++;old=snapshot;snapshot=new Snapshot(Map.of(),0);}
        unlinkNew(old.entries,Map.of());
        Set<Integer> revoke=new HashSet<>(old.entries.keySet());revoke.addAll(extra);
        if(first){
            log.accept("EDGE_AUDIO_UNAVAILABLE "+FrameworkAccess.reason(error));
            Handler target=handler;
            if(target!=null&&!revoke.isEmpty())target.post(()->{
                for(int uid:revoke)for(int op:OPS)try{notify.invoke(service,op,uid);}
                catch(Exception|LinkageError ignored){/* Native notification is best effort after disabling the exception. */}
            });
        }
    }
    private static void unlinkNew(Map<Integer,Entry> entries,Map<Integer,Entry> retained){
        for(var item:entries.entrySet())if(retained.get(item.getKey())!=item.getValue())
            try{item.getValue().grant.lease.unlinkToDeath(item.getValue().death,0);}catch(RuntimeException ignored){}
    }
}
