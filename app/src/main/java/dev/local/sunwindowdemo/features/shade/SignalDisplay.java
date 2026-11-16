package dev.local.sunwindowdemo.features.shade;

import android.app.Application;
import android.content.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.telephony.*;
import android.view.View;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Visual-only standard mobile drawable adapter. Never mutates telephony objects or flows. */
public final class SignalDisplay {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<Integer,Radio> RADIOS=new HashMap<>();
    private static final List<Paint> PAINTS=new ArrayList<>();
    private static final ThreadLocal<Binding> BINDING=new ThreadLocal<>();
    private static final List<XposedInterface.HookHandle> HOOKS=new ArrayList<>();
    private static final Map<View,Integer> MOBILE_VIEWS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Drawable,Integer> MOBILE_DRAWABLES=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int MOBILE_SIGNAL_ID;
    private static volatile boolean MOBILE_LEVEL_ACTIVE;
    private static final Set<Class<?>> TELEPHONY_CALLBACKS=Collections.synchronizedSet(new HashSet<>());
    private static final Map<Object,Integer> CALLBACK_SUBS=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean enabled;
    private static boolean installed,connected;
    private static Method primary,emergencyOnly;
    private static Class<?> signalDrawable,cellular;
    private static Consumer<String> log=ignored->{};
    // Module-owned observable state: invalidates Compose readers without writing native flows.
    private static Object stackedRevision;
    private static Method stackedRevisionRead,stackedRevisionWrite;
    private static boolean stackedSeen,stackedApplied,stackedFailure;
    private record Binding(Drawable drawable,int subId){}
    private static final class Radio {
        WeakReference<Object> owner;
        SignalStrength strength;
        ServiceState service;
        boolean ntn;
        Radio(Object owner){this.owner=new WeakReference<>(owner);}
    }
    private static final class Paint {
        WeakReference<Drawable> drawable;
        int subId,nativeState;
        Paint(Drawable d,int sub,int state){drawable=new WeakReference<>(d);subId=sub;nativeState=state;}
    }
    private SignalDisplay(){}
    private static Field field(Class<?> owner,String name)throws Exception{
        return StackedSignalTargets.field(owner,name);
    }
    // Runs only inside the scoped Xposed SystemUI host, never as an ordinary app API.
    // Missing/inaccessible framework members disable this optional adapter before hooks.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    public static synchronized void install(XposedModule module,ClassLoader loader,Consumer<String> logger){
        if(installed)return;installed=true;log=logger;
        try{
            primary=SignalStrength.class.getDeclaredMethod("getPrimary");primary.setAccessible(true);
            if(primary.getReturnType()!=CellSignalStrength.class)throw new NoSuchMethodException("primary");
            emergencyOnly=ServiceState.class.getDeclaredMethod("isEmergencyOnly");emergencyOnly.setAccessible(true);
            if(emergencyOnly.getReturnType()!=boolean.class)throw new NoSuchMethodException("emergency state");
            signalDrawable=Class.forName("com.android.settingslib.graph.SignalDrawable",false,loader);
            cellular=Class.forName("com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel$CellularTypeIconModel$Cellular",false,loader);
            Class<?> binder=Class.forName("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder$bind$1$2$1$2$2",false,loader);
            Class<?> continuation=Class.forName("kotlin.coroutines.Continuation",false,loader);
            Method emit=binder.getDeclaredMethod("emit",Object.class,continuation);
            Field id=field(binder,"$r8$classId"),drawable=field(binder,"$mobileDrawable"),viewModel=field(binder,"$viewModel");
            Method sub=viewModel.getType().getMethod("getSubscriptionId");
            Class<?> pair=Class.forName("kotlin.Pair",false,loader);
            Field second=field(pair,"second");
            Field changing=field(cellular,"carrierNetworkChange");
            // Resolve every required target before hooks; unsupported ROMs remain native.
            List<Callback> callbacks=new ArrayList<>();
            for(String[] target:new String[][]{
                {"MobileConnectionRepositoryImpl$callbackEvents$1$1$callback$1","$this_run"},
                {"MobileConnectionRepositoryKairosImpl$callbackEvents$1$2$callback$1","this$0"}}){
                Class<?> c=Class.forName("com.android.systemui.statusbar.pipeline.mobile.data.repository.prod."+target[0],false,loader);
                Field repo=field(c,target[1]),subscription=field(repo.getType(),"subId");
                callbacks.add(new Callback(repo,subscription,
                    c.getDeclaredMethod("onSignalStrengthsChanged",SignalStrength.class),
                    c.getDeclaredMethod("onServiceStateChanged",ServiceState.class),
                    c.getDeclaredMethod("onCarrierRoamingNtnModeChanged",boolean.class)));
            }
            HOOKS.add(module.hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Binding previous=BINDING.get();
                try{
                    BINDING.remove();
                    try{
                    Object value=chain.getArg(0),d=drawable.get(chain.getThisObject());
                    if(id.getInt(chain.getThisObject())==0&&pair.isInstance(value)&&signalDrawable.isInstance(d)){
                        Object model=second.get(value);
                        if(cellular.isInstance(model)&&!changing.getBoolean(model)){
                            int subscription=(Integer)sub.invoke(viewModel.get(chain.getThisObject()));
                            if(SubscriptionManager.isValidSubscriptionId(subscription))BINDING.set(new Binding((Drawable)d,subscription));
                            else forget((Drawable)d);
                        }else forget((Drawable)d);
                    }
                    }catch(Exception unavailable){
                        BINDING.remove();
                        try{Object d=drawable.get(chain.getThisObject());if(d instanceof Drawable value)forget(value);}catch(Exception ignored){}
                    }
                    return chain.proceed();
                }finally{if(previous==null)BINDING.remove();else BINDING.set(previous);}
            }));
            Method setLevel=Drawable.class.getDeclaredMethod("setLevel",int.class);
            HOOKS.add(module.hook(setLevel).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Binding b=BINDING.get();
                if(b==null||chain.getThisObject()!=b.drawable)return chain.proceed();
                int nativeState=(Integer)chain.getArg(0);
                if(!remember(b,nativeState))return chain.proceed();
                int adjusted=adjusted(nativeState,b.subId);
                return adjusted==nativeState?chain.proceed():chain.proceed(new Object[]{adjusted});
            }));
            if(!module.deoptimize(emit))throw new IllegalStateException("signal binder deoptimize");
            for(Callback c:callbacks){
                Method[] methods={c.strength,c.service,c.ntn};
                for(int index=0;index<methods.length;index++){
                    final int kind=index;
                    HOOKS.add(module.hook(methods[index]).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        Object result=chain.proceed();
                        try{
                            Object owner=c.repo.get(chain.getThisObject());int subscription=c.sub.getInt(owner);
                            synchronized(RADIOS){
                                RADIOS.entrySet().removeIf(e->e.getValue().owner.get()==null);
                                Radio radio=RADIOS.get(subscription);
                                if(radio==null||radio.owner.get()!=owner){radio=new Radio(owner);RADIOS.put(subscription,radio);}
                                if(kind==0)radio.strength=(SignalStrength)chain.getArg(0);
                                else if(kind==1){
                                    radio.service=(ServiceState)chain.getArg(0);
                                    if(!registeredNr(radio.service))radio.strength=null;
                                }else{
                                    radio.ntn=(Boolean)chain.getArg(0);
                                    if(radio.ntn)radio.strength=null;
                                }
                            }
                            requestRedraw();
                        }catch(Exception failure){log.accept("SIGNAL_CALLBACK_UNAVAILABLE "+failure.getClass().getSimpleName());}
                        return result;
                    }));
                }
            }
            installStacked(module,loader);
            log.accept("SIGNAL_DISPLAY_READY standard mobile drawable");
        }catch(Throwable failure){
            enabled=false;for(var hook:HOOKS)try{hook.unhook();}catch(Throwable ignored){}HOOKS.clear();
            log.accept("SIGNAL_DISPLAY_PIXELOS_UNAVAILABLE "+StackedSignalTargets.describe(failure));
            installLineage(module,loader);
        }
    }
    /**
     * LineageOS 24.0 uses MobileIconBinder.bind and a single anonymous TelephonyCallback in each
     * MobileConnectionRepositoryImpl. There is no published synthetic binder callback. Hook the
     * verified bind entry to associate the actual mobile_signal drawable with its subscription,
     * then observe the public TelephonyManager registration to retain the raw SignalStrength /
     * ServiceState used by SignalDisplayPolicy.
     */
    private static void installLineage(XposedModule module,ClassLoader loader){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> binder=Class.forName("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder",false,loader);
            Method bind=null;
            for(Method candidate:binder.getDeclaredMethods()){
                if(!candidate.getName().equals("bind")||candidate.getParameterCount()!=4)continue;
                Class<?>[] types=candidate.getParameterTypes();
                if(types[0]==android.view.ViewGroup.class&&types[2]==int.class
                    &&"com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel".equals(types[1].getName())
                    &&types[3].getName().endsWith("MobileViewLogger")){bind=candidate;break;}
            }
            if(bind==null)throw new NoSuchMethodException("MobileIconBinder.bind");
            bind.setAccessible(true);
            hooks.add(module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // Associate the view before bind starts its initial state flow, otherwise the
                // first setImageDrawable/setLevel pair can escape the subscription mapping.
                try{
                    android.view.ViewGroup group=(android.view.ViewGroup)chain.getArg(0);
                    Object viewModel=chain.getArg(1);
                    int subscription=(Integer)viewModel.getClass().getMethod("getSubscriptionId").invoke(viewModel);
                    int id=group.getResources().getIdentifier("mobile_signal","id","com.android.systemui");
                    if(id!=0){
                        MOBILE_SIGNAL_ID=id;
                        if(SubscriptionManager.isValidSubscriptionId(subscription)){
                            View signal=group.findViewById(id);if(signal!=null)MOBILE_VIEWS.put(signal,subscription);
                        }
                    }
                }catch(Exception failure){log.accept("SIGNAL_LINEAGE_BIND_PARTIAL "+StackedSignalTargets.describe(failure));}
                return chain.proceed();
            }));
            Method setImage=ImageView.class.getDeclaredMethod("setImageDrawable",Drawable.class);
            setImage.setAccessible(true);
            hooks.add(module.hook(setImage).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject();
                int mobileId=MOBILE_SIGNAL_ID;
                // This hook is process-wide in SystemUI; reject unrelated ImageViews with one integer check.
                if(!(owner instanceof ImageView view)||mobileId==0||view.getId()!=mobileId)return chain.proceed();
                Object result=chain.proceed();
                try{
                    Integer subscription=MOBILE_VIEWS.get(view);
                    Drawable drawable=(Drawable)chain.getArg(0);
                    if(subscription!=null&&drawable!=null){
                        MOBILE_DRAWABLES.put(drawable,subscription);
                        MOBILE_LEVEL_ACTIVE=true;
                    }
                }catch(RuntimeException failure){log.accept("SIGNAL_LINEAGE_VIEW_PARTIAL "+failure.getClass().getSimpleName());}
                return result;
            }));
            Method setLevel=Drawable.class.getDeclaredMethod("setLevel",int.class);
            setLevel.setAccessible(true);
            hooks.add(module.hook(setLevel).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(!MOBILE_LEVEL_ACTIVE||MOBILE_DRAWABLES.isEmpty())return chain.proceed();
                Object owner=chain.getThisObject();
                if(!(owner instanceof Drawable drawable)||!signalDrawable.isInstance(drawable))
                    return chain.proceed();
                Integer subscription=MOBILE_DRAWABLES.get(drawable);
                if(subscription==null)return chain.proceed();
                int nativeState=(Integer)chain.getArg(0);
                if(!remember(new Binding(drawable,subscription),nativeState))return chain.proceed();
                int adjusted=adjusted(nativeState,subscription);
                return adjusted==nativeState?chain.proceed():chain.proceed(new Object[]{adjusted});
            }));
            Method register=TelephonyManager.class.getMethod("registerTelephonyCallback",
                java.util.concurrent.Executor.class,TelephonyCallback.class);
            hooks.add(module.hook(register).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // Install before native registration so the first callback burst is observed.
                try{
                    Object callback=chain.getArg(1);
                    int subscription=((TelephonyManager)chain.getThisObject()).getSubscriptionId();
                    if(callback!=null&&SubscriptionManager.isValidSubscriptionId(subscription))
                        installLineageCallback(module,callback,subscription);
                }catch(Exception failure){log.accept("SIGNAL_LINEAGE_REGISTER_PARTIAL "+failure.getClass().getSimpleName());}
                return chain.proceed();
            }));
            HOOKS.addAll(hooks);
            log.accept("SIGNAL_DISPLAY_READY lineage mobile_icon_binder");
        }catch(Throwable failure){
            for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            enabled=false;
            log.accept("SIGNAL_DISPLAY_UNAVAILABLE "+StackedSignalTargets.describe(failure));
        }
    }
    private static void installLineageCallback(XposedModule module,Object callback,int subscription)throws Exception{
        CALLBACK_SUBS.put(callback,subscription);
        Class<?> type=callback.getClass();
        if(!TELEPHONY_CALLBACKS.add(type))return;
        Method strength=type.getDeclaredMethod("onSignalStrengthsChanged",SignalStrength.class);
        Method service=type.getDeclaredMethod("onServiceStateChanged",ServiceState.class);
        Method ntn=null;
        try{ntn=type.getDeclaredMethod("onCarrierRoamingNtnModeChanged",boolean.class);}
        catch(NoSuchMethodException optional){}
        strength.setAccessible(true);service.setAccessible(true);
        if(ntn!=null)ntn.setAccessible(true);
        hookLineageCallback(module,strength,0);
        hookLineageCallback(module,service,1);
        if(ntn!=null)hookLineageCallback(module,ntn,2);
    }
    private static void hookLineageCallback(XposedModule module,Method method,int kind)throws Exception{
        HOOKS.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object result=chain.proceed();
            try{
                Object owner=chain.getThisObject();
                Integer subscription=CALLBACK_SUBS.get(owner);
                if(subscription==null)return result;
                synchronized(RADIOS){
                    RADIOS.entrySet().removeIf(e->e.getValue().owner.get()==null);
                    Radio radio=RADIOS.get(subscription);
                    if(radio==null||radio.owner.get()!=owner){radio=new Radio(owner);RADIOS.put(subscription,radio);}
                    if(kind==0)radio.strength=(SignalStrength)chain.getArg(0);
                    else if(kind==1){
                        radio.service=(ServiceState)chain.getArg(0);
                        if(!registeredNr(radio.service))radio.strength=null;
                    }else{
                        radio.ntn=(Boolean)chain.getArg(0);
                        if(radio.ntn)radio.strength=null;
                    }
                }
                requestRedraw();
            }catch(Exception failure){log.accept("SIGNAL_LINEAGE_CALLBACK_PARTIAL "+failure.getClass().getSimpleName());}
            return result;
        }));
    }
    private static void installStacked(XposedModule module,ClassLoader loader){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            String prefix="com.android.systemui.statusbar.pipeline.";
            Class<?> dual=Class.forName(prefix+"mobile.ui.model.DualSim",false,loader);
            Class<?> parser=Class.forName(prefix+"mobile.ui.model.DualSimKt",false,loader);
            Class<?> pair=Class.forName("kotlin.Pair",false,loader);
            // Legacy builds copies with tryParseDualSim; QPR2 removed it and allocates DualSim directly.
            Method parse=null;
            for(Method m:parser.getDeclaredMethods())if(m.getName().equals("tryParseDualSim")){parse=StackedSignalTargets.method(parser,"tryParseDualSim",List.class);break;}
            if(parse!=null&&parse.getReturnType()!=dual)throw new NoSuchMethodException(parser.getName()+"#tryParseDualSim returns "+parse.getReturnType().getName());
            StackedSignalDisplay adapter=new StackedSignalDisplay(dual,cellular,pair,parse);
            Class<?> state=Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableIntState",false,loader);
            Object revision=StackedSignalTargets.constructor(state,int.class).newInstance(0);
            Method read=StackedSignalTargets.method(state,"getIntValue"),write=StackedSignalTargets.method(state,"setIntValue",int.class);
            if(read.getReturnType()!=int.class)throw new NoSuchMethodException(state.getName()+"#getIntValue returns "+read.getReturnType().getName());
            Class<?> composer=Class.forName("androidx.compose.runtime.Composer",false,loader);
            Class<?> modifier=Class.forName("androidx.compose.ui.Modifier",false,loader);
            Class<?> draw=Class.forName(prefix+"shared.ui.composable.StackedMobileIconKt",false,loader);
            // Legacy has an extra String parameter; QPR2 removed it. Chosen by exact parameter types.
            Method render=StackedSignalTargets.render(draw,dual,modifier,composer);
            final int changedArg=StackedSignalTargets.changedIndex(render);
            hooks.add(module.hook(render).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object input=chain.getArg(0),nativeModel=adapter.original(input),adjusted=nativeModel;
                try{
                    read.invoke(revision); // Register this composition's dependency on our own state.
                    adjusted=adapter.adjust(nativeModel,SignalDisplay::adjusted);
                    if(!stackedSeen){stackedSeen=true;log.accept("SIGNAL_STACKED_RENDER_SEEN");}
                    if(adjusted!=nativeModel&&!stackedApplied){stackedApplied=true;log.accept("SIGNAL_STACKED_APPLIED");}
                }catch(Exception failure){
                    if(!stackedFailure){stackedFailure=true;log.accept("SIGNAL_STACKED_RENDER_FAILED "+StackedSignalTargets.describe(failure));}
                }
                Object[] args=chain.getArgs().toArray();args[0]=adjusted;
                // A radio/config revision can change our copy while the native model is equal.
                // Mark only DualSim as changed so Compose does not skip this render.
                args[changedArg]=((Integer)args[changedArg]&~14)|4;
                return chain.proceed(args);
            }));
            // The restart callback and parent composable may inline the render entry.
            // Parent view model is StackedMobileIconViewModel (legacy) or StackedMobileIconViewModelImpl (QPR2).
            Method parent=StackedSignalTargets.parent(draw,modifier,composer);
            Class<?> restart=Class.forName(prefix+"shared.ui.composable.StackedMobileIconKt$$ExternalSyntheticLambda6",false,loader);
            Method restartInvoke=StackedSignalTargets.restart(restart,dual);
            if(!module.deoptimize(parent))throw new IllegalStateException("deoptimize "+parent);
            if(!module.deoptimize(restartInvoke))throw new IllegalStateException("deoptimize "+restartInvoke);
            stackedRevision=revision;stackedRevisionRead=read;stackedRevisionWrite=write;
            HOOKS.addAll(hooks);log.accept("SIGNAL_STACKED_READY explicit subscription presentation copies "+render.getName());
        }catch(Throwable failure){
            for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("SIGNAL_STACKED_UNAVAILABLE "+StackedSignalTargets.describe(failure));
        }
    }
    private record Callback(Field repo,Field sub,Method strength,Method service,Method ntn){}
    private static void forget(Drawable drawable){synchronized(PAINTS){PAINTS.removeIf(p->p.drawable.get()==null||p.drawable.get()==drawable);}}
    private static boolean remember(Binding b,int state){
        synchronized(PAINTS){
            PAINTS.removeIf(p->p.drawable.get()==null);
            for(Paint p:PAINTS)if(p.drawable.get()==b.drawable){p.subId=b.subId;p.nativeState=state;return true;}
            // ponytail: at most 32 live standard mobile drawables; excess views keep native rendering.
            if(PAINTS.size()>=32)return false;
            PAINTS.add(new Paint(b.drawable,b.subId,state));return true;
        }
    }
    private static boolean registeredNr(ServiceState service){
        if(service==null||service.getState()==ServiceState.STATE_POWER_OFF
            ||service.getState()==ServiceState.STATE_EMERGENCY_ONLY||service.isUsingNonTerrestrialNetwork())return false;
        try{if((Boolean)emergencyOnly.invoke(service))return false;}catch(Exception unavailable){return false;}
        for(NetworkRegistrationInfo info:service.getNetworkRegistrationInfoList()){
            if(info.getTransportType()==1&&(info.getDomain()&NetworkRegistrationInfo.DOMAIN_PS)!=0
                &&info.isRegistered()&&!info.isNonTerrestrialNetwork()
                &&info.getAccessNetworkTechnology()==TelephonyManager.NETWORK_TYPE_NR)return true;
        }
        return false;
    }
    private static int adjusted(int nativeState,int subscription){
        int nativeLevel=(nativeState&255)-((((nativeState>>>8)&255)==6)?1:0);
        if(!enabled||nativeLevel<=0)return nativeState;
        try{synchronized(RADIOS){
            Radio radio=RADIOS.get(subscription);
            if(radio==null||radio.owner.get()==null||radio.strength==null||radio.service==null||radio.ntn)return nativeState;
            if(nativeLevel!=radio.strength.getLevel())return nativeState;
            if(!registeredNr(radio.service))return nativeState;
            Object current=primary.invoke(radio.strength);
            if(!(current instanceof CellSignalStrengthNr nr))return nativeState;
            int dbm=nr.getSsRsrp();
            if(dbm < -156||dbm > -31)return nativeState;
            return SignalDisplayPolicy.encode(nativeState,SignalDisplayPolicy.level(0,true,true,true,dbm));
        }}catch(Exception unavailable){return nativeState;}
    }
    private static final Runnable REDRAW=()->{
        Binding previous=BINDING.get();BINDING.remove();
        try{
            if(stackedRevision!=null)try{
                int revision=(Integer)stackedRevisionRead.invoke(stackedRevision);
                stackedRevisionWrite.invoke(stackedRevision,revision+1);
            }catch(Exception failure){log.accept("SIGNAL_STACKED_REFRESH_FAILED "+StackedSignalTargets.describe(failure));}
            List<Paint> snapshot;
            synchronized(PAINTS){PAINTS.removeIf(p->p.drawable.get()==null);snapshot=new ArrayList<>(PAINTS);}
            for(Paint p:snapshot){
                Drawable d=p.drawable.get();if(d==null)continue;
                try{d.setLevel(adjusted(p.nativeState,p.subId));}
                catch(RuntimeException failure){
                    try{d.setLevel(p.nativeState);}catch(RuntimeException ignored){}
                    forget(d);
                }
            }
        }finally{if(previous!=null)BINDING.set(previous);else BINDING.remove();}
    };
    private static void requestRedraw(){MAIN.removeCallbacks(REDRAW);MAIN.post(REDRAW);}
    public static void connect(Application app){
        if(connected||HOOKS.isEmpty()||!ShadeStore.SYSTEM_UI.equals(app.getPackageName()))return;
        connected=true;
        var worker=Executors.newSingleThreadExecutor();
        Runnable load=new Runnable(){
            @Override public void run(){
                worker.execute(()->{
                    // Shares the shade snapshot; a stopped manager retains the stored choice.
                    var snapshot=dev.local.sunwindowdemo.core.HostSnapshot.open(app,"shade");
                    var read=snapshot.read(()->app.getContentResolver().call(
                        android.net.Uri.parse("content://"+ShadeStore.AUTHORITY),"read",null,null));
                    if(read.report())log.accept("SIGNAL_SETTINGS "+read.summary());
                    Bundle config=read.config();
                    enabled=config!=null&&config.getBoolean("signalDisplay",false);
                    requestRedraw();
                    MAIN.removeCallbacks(this);
                    if(!read.live())MAIN.postDelayed(this,snapshot.retryDelay(3000));
                });
            }
        };
        try{
            app.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context context,Intent intent){
                    try{
                        int uid=context.getPackageManager().getApplicationInfo("dev.nativecomplements.module",0).uid;
                        if(ShadeStore.CHANGED.equals(intent.getAction())&&getSentFromUid()==uid)load.run();
                    }catch(Exception ignored){}
                }
            },new IntentFilter(ShadeStore.CHANGED),null,MAIN,Context.RECEIVER_EXPORTED);
            load.run();
        }catch(RuntimeException failure){enabled=false;requestRedraw();}
    }
}
