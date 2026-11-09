package dev.local.sunwindowdemo.features.callblocking;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import dev.local.sunwindowdemo.BuildConfig;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Android 17 Telecom filtering, independent of window hooks and the default screening app. */
public final class CallBlockingModule extends XposedModule {
    private boolean eligible,attempted;
    private volatile Bundle config=Bundle.EMPTY;
    private Context context;
    private Handler worker;
    private Method managerContext,registrar,account,subscription,incoming,selfManaged,emergency,ecbm,
        hasProperty,target,handle,state,user,builderFrom,allow,reject,addLog,notification,audio,silence,
        blockReason,appName,component,response,build,emergencySms;
    private Field nativeAllows;
    private final Set<Object> recorded=Collections.newSetFromMap(new WeakHashMap<>());
    private long lastFailure;

    @Override public void onModuleLoaded(ModuleLoadedParam p){eligible=p.isSystemServer();}
    @Override public void onSystemServerStarting(SystemServerStartingParam p){
        if(!eligible||attempted)return;
        attempted=true;
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            ClassLoader loader=p.getClassLoader();
            Class<?> manager=Class.forName("com.android.server.telecom.CallsManager",false,loader);
            Class<?> call=Class.forName("com.android.server.telecom.Call",false,loader);
            Class<?> result=Class.forName("com.android.server.telecom.callfiltering.CallFilteringResult",false,loader);
            Class<?> builder=Class.forName(result.getName()+"$Builder",false,loader);
            nativeAllows=result.getDeclaredField("shouldAllowCall");nativeAllows.setAccessible(true);
            if(nativeAllows.getType()!=boolean.class)throw new NoSuchFieldException("CallFilteringResult.shouldAllowCall boolean");
            Class<?> accounts=Class.forName("com.android.server.telecom.PhoneAccountRegistrar",false,loader);
            emergencySms=method(TelephonyManager.class,"isInEmergencySmsMode");
            managerContext=method(manager,"getContext");registrar=method(manager,"getPhoneAccountRegistrar");
            account=method(accounts,"getPhoneAccountUnchecked",PhoneAccountHandle.class);
            subscription=method(accounts,"getSubscriptionIdForPhoneAccount",PhoneAccountHandle.class);
            incoming=method(call,"isIncoming");selfManaged=method(call,"isSelfManaged");
            emergency=method(call,"isEmergencyCall");ecbm=method(call,"isInECBM");
            hasProperty=method(call,"hasProperty",int.class);target=method(call,"getTargetPhoneAccount");
            handle=method(call,"getHandle");state=method(call,"getState");user=method(call,"getAssociatedUser");
            builderFrom=method(builder,"from",result);build=method(builder,"build");
            allow=method(builder,"setShouldAllowCall",boolean.class);reject=method(builder,"setShouldReject",boolean.class);
            addLog=method(builder,"setShouldAddToCallLog",boolean.class);notification=method(builder,"setShouldShowNotification",boolean.class);
            audio=method(builder,"setShouldScreenViaAudio",boolean.class);silence=method(builder,"setShouldSilence",boolean.class);
            blockReason=method(builder,"setCallBlockReason",int.class);appName=method(builder,"setCallScreeningAppName",CharSequence.class);
            component=method(builder,"setCallScreeningComponentName",String.class);
            response=method(builder,"setCallScreeningResponse",Class.forName("android.telecom.ParcelableCallResponse",false,loader),boolean.class);
            Method filtering=method(manager,"onCallFilteringComplete",call,result,boolean.class);
            if(filtering.getReturnType()!=void.class||subscription.getReturnType()!=int.class||build.getReturnType()!=result)
                throw new NoSuchMethodException("Telecom filter signatures");
            // All reflection is resolved before installing anything. A missing API disables only this feature.
            for(Constructor<?> constructor:manager.getDeclaredConstructors()){
                hooks.add(hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object out=chain.proceed();
                    try{connect((Context)managerContext.invoke(chain.getThisObject()));}catch(Throwable e){failure(e);}
                    return out;
                }));
            }
            hooks.add(hook(filtering).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object replacement=null;Bundle entry=null;
                try{
                    Object original=chain.getArg(1);
                    // Preserve native block/ignore decisions and their screening reason/response.
                    Decision d=original!=null&&nativeAllows.getBoolean(original)?decide(chain.getThisObject(),chain.getArg(0)):null;
                    if(d!=null){
                        Object b=builderFrom.invoke(null,chain.getArg(1));
                        allow.invoke(b,false);reject.invoke(b,true);addLog.invoke(b,true);notification.invoke(b,false);
                        audio.invoke(b,false);silence.invoke(b,false);blockReason.invoke(b,1);
                        appName.invoke(b,"类原生补全");component.invoke(b,(Object)null);response.invoke(b,null,false);
                        replacement=build.invoke(b);entry=d.entry;
                    }
                }catch(Throwable e){failure(e);}
                Object out=replacement==null?chain.proceed():chain.proceed(new Object[]{chain.getArg(0),replacement,chain.getArg(2)});
                if(entry!=null){
                    boolean first;
                    synchronized(recorded){first=recorded.add(chain.getArg(0));}
                    if(first){Bundle saved=entry;worker.post(()->record(saved));}
                }
                return out;
            }));
            note("CALL_BLOCKING_HOOK_READY hooks="+hooks.size()+" version="+BuildConfig.VERSION_NAME);
        }catch(Throwable e){
            for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}
            note("CALL_BLOCKING_DISABLED "+e.getClass().getSimpleName());
        }
    }

    private synchronized void connect(Context c){
        if(worker!=null)return;
        context=c;
        HandlerThread thread=new HandlerThread("NativeComplements-Calls",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();worker=new Handler(thread.getLooper());
        worker.post(()->{
            try{context.getContentResolver().registerContentObserver(CallBlockingProvider.CONFIG,true,
                new ContentObserver(worker){@Override public void onChange(boolean self){reload();}});
            }catch(Throwable e){failure(e);}
            poll();
        });
    }

    private void poll(){
        reload();
        worker.postDelayed(this::poll,60_000);
    }
    private void reload(){
        long identity=Binder.clearCallingIdentity();
        try{
            Bundle value=context.getContentResolver().call(CallBlockingProvider.CONFIG,"read",null,null);
            if(value==null)throw new IllegalStateException("empty config");
            Bundle previous=config;
            config=new Bundle(value);
            if(previous==Bundle.EMPTY||previous.getLong("updated_at")!=value.getLong("updated_at")
                ||previous.getBoolean("enabled")!=value.getBoolean("enabled")){
                note("CALL_BLOCKING_CONFIG version="+BuildConfig.VERSION_NAME+" enabled="+value.getBoolean("enabled")
                    +" updatedAt="+value.getLong("updated_at")+" sim1RejectAll="+value.getBoolean("sim1_reject_all")
                    +" sim2RejectAll="+value.getBoolean("sim2_reject_all")+" sim2Follow="+value.getBoolean("sim2_follow_sim1")
                    +" sim1Rules="+ruleCount(value,"sim1_patterns")+" sim2Rules="+ruleCount(value,"sim2_patterns")
                    +" wall="+System.currentTimeMillis()+" elapsed="+SystemClock.elapsedRealtime()
                    +" thread="+Thread.currentThread().getName());
            }
            Bundle status=new Bundle();status.putString("error","");
            context.getContentResolver().call(CallBlockingProvider.CONFIG,"backend",BuildConfig.VERSION_NAME,status);
        }catch(Throwable e){config=Bundle.EMPTY;failure(e);}
        finally{Binder.restoreCallingIdentity(identity);}
    }

    private static int ruleCount(Bundle value,String key){String[] rules=value.getStringArray(key);return rules==null?0:rules.length;}

    private Decision decide(Object manager,Object call)throws Exception{
        Bundle snapshot=config;
        if(!snapshot.getBoolean("enabled")||context==null||worker==null)return null;
        if(!(Boolean)incoming.invoke(call)||(Boolean)selfManaged.invoke(call)||(Boolean)emergency.invoke(call)
            ||(Boolean)ecbm.invoke(call)||(Boolean)hasProperty.invoke(call,1)||(Boolean)hasProperty.invoke(call,1024))return null;
        int current=(Integer)state.invoke(call);
        if(current==7||current==9)return null;
        UserHandle owner=(UserHandle)user.invoke(call);
        if(owner==null||!owner.equals(android.os.Process.myUserHandle()))return null;
        long identity=Binder.clearCallingIdentity();
        try{
            TelephonyManager tm=context.getSystemService(TelephonyManager.class);
            if(tm==null)return null;
            try{if((Boolean)emergencySms.invoke(tm))return null;}
            catch(InvocationTargetException e){if(!(e.getCause() instanceof UnsupportedOperationException))throw e;}
            PhoneAccountHandle pah=(PhoneAccountHandle)target.invoke(call);
            if(pah==null)return null;
            Object registry=registrar.invoke(manager);
            PhoneAccount pa=(PhoneAccount)account.invoke(registry,pah);
            if(pa==null||!pa.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION))return null;
            int sub=(Integer)subscription.invoke(registry,pah);
            SubscriptionManager sm=context.getSystemService(SubscriptionManager.class);
            SubscriptionInfo info=sm==null?null:sm.getActiveSubscriptionInfo(sub);
            if(info==null)return null;
            int slot=info.getSimSlotIndex();
            if(slot<0||slot>1)return null;
            Uri numberUri=(Uri)handle.invoke(call);
            String number=numberUri==null?"":numberUri.getSchemeSpecificPart();
            CallBlockingRules.Decision d=CallBlockingRules.decide(true,slot,
                snapshot.getBoolean("sim1_reject_all"),snapshot.getStringArray("sim1_patterns"),
                snapshot.getBoolean("sim2_follow_sim1"),snapshot.getBoolean("sim2_reject_all"),
                snapshot.getStringArray("sim2_patterns"),number);
            if(!d.blocked)return null;
            Bundle entry=new Bundle();entry.putLong("time",System.currentTimeMillis());
            entry.putString("number",number);entry.putInt("slot_index",slot);entry.putInt("subscription_id",sub);
            entry.putString("sim_label",String.valueOf(info.getDisplayName()));
            entry.putString("reason",d.reason);entry.putString("matched_pattern",d.matchedPattern);
            note("CALL_BLOCKED sim="+(slot+1)+" reason="+d.reason);
            return new Decision(entry);
        }finally{Binder.restoreCallingIdentity(identity);}
    }
    private record Decision(Bundle entry){}

    private void record(Bundle entry){
        long identity=Binder.clearCallingIdentity();
        try{
            Bundle out=context.getContentResolver().call(CallBlockingProvider.CONFIG,"record",null,entry);
            if(out==null||!out.getBoolean("stored"))throw new IllegalStateException("record not stored");
        }catch(Throwable e){failure(e);}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    private static Method method(Class<?> c,String name,Class<?>... args)throws Exception{
        Method m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    private synchronized void failure(Throwable e){
        long now=SystemClock.elapsedRealtime();
        if(lastFailure!=0&&now-lastFailure<60_000)return;
        lastFailure=now;
        Throwable root=e;
        while(root instanceof InvocationTargetException&&root.getCause()!=null)root=root.getCause();
        StackTraceElement[] trace=root.getStackTrace();
        String where=trace.length==0?"unknown":trace[0].getClassName()+"."+trace[0].getMethodName()+":"+trace[0].getLineNumber();
        // Do not log exception messages: framework errors can include a caller's phone number.
        note("CALL_BLOCKING_ERROR type="+e.getClass().getSimpleName()+" cause="+root.getClass().getSimpleName()+" at="+where
            +" elapsed="+now+" thread="+Thread.currentThread().getName());
    }
    private void note(String text){try{log(android.util.Log.INFO,"NativeComplements",text);}catch(Throwable ignored){}}
}
