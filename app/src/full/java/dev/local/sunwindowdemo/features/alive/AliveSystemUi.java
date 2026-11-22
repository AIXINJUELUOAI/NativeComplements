package dev.local.sunwindowdemo.features.alive;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Host-loader Flow projection for the explicitly selected, module-only ambient wallpaper. */
public final class AliveSystemUi {
    private static final ComponentName OWN=new ComponentName("dev.nativecomplements.module",
        "dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService");
    private static boolean attempted;
    private AliveSystemUi() {}

    public static synchronized void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        if(attempted)return;attempted=true;
        AliveColorDelivery.install(module,loader,log);
        XposedInterface.HookHandle hook=null;
        try{
            Access access=new Access(loader);
            hook=module.hook(access.constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{access.project(chain.getThisObject(),chain.getArg(3),(android.content.Context)chain.getArg(5));}
                catch(ReflectiveOperationException|RuntimeException unavailable){
                    log.accept("ALIVE_SYSTEMUI_PROJECTION_UNAVAILABLE "+unavailable.getClass().getSimpleName());
                }
                return result;
            });
            if(!module.deoptimize(access.constructor))throw new IllegalStateException("wallpaper repository constructor deoptimize");
            // Experiment switch (root-only debug prop, read once per SystemUI start): leave the native
            // AOD-wallpaper flow alone so it can be observed without the handoff/blur takeover.
            if(nativeExperiment()){log.accept("ALIVE_SYSTEMUI_NATIVE_EXPERIMENT handoff and doze blur skipped");return;}
            installDozeBlur(module,loader,access,log);
            AliveSleepHandoff.install(module,loader,access,log);
            log.accept("ALIVE_SYSTEMUI_READY selected ambient wallpaper only");
        }catch(Throwable unavailable){
            if(hook!=null)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_SYSTEMUI_UNAVAILABLE "+unavailable.getClass().getSimpleName());
        }
    }

    /** Only the two native doze producers may project their own contribution; auth animators are untouched. */
    private static void installDozeBlur(XposedModule module,ClassLoader loader,Access access,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean();
        ThreadLocal<Object> producing=new ThreadLocal<>();
        try{
            Class<?> depth=type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController");
            try{depth.getDeclaredMethod("setWakeAndUnlockBlurRadius",float.class);}
            catch(NoSuchMethodException lineage){
                // LineageOS keeps wakeAndUnlockBlurRadius private and computes it through
                // getNewWakeBlurRadius instead of a public setter.
                installDozeBlurFallback(module,loader,depth,access,log,enabled);
                return;
            }
            Class<?> callback=nestedDoze(loader,depth,"onDozeAmountChanged",float.class,float.class);
            Class<?> collector=nestedEmit(loader,depth);
            Class<?> interactor=type(loader,"com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor");
            Field callbackOwner=field(callback,"this$0",depth),collectorOwner=field(collector,"this$0",depth);
            Field collectorId=optionalField(collector,"$r8$classId",int.class);
            Field wallpapers=field(depth,"wallpaperInteractor",interactor);
            // LineageOS 24.0 declares WallpaperInteractor.wallpaperRepository as the interface;
            // PixelOS narrows it to the implementation.
            Field repository=assignable(interactor,"wallpaperRepository",
                type(loader,"com.android.systemui.wallpapers.data.repository.WallpaperRepository"));
            Field ambient=field(depth,"wallpaperSupportsAmbientMode",boolean.class),amount=field(depth,"prevDozeAmount",float.class);
            Method doze=callback.getDeclaredMethod("onDozeAmountChanged",float.class,float.class);
            Method emit=collector.getDeclaredMethod("emit",Object.class,type(loader,"kotlin.coroutines.Continuation"));
            Method radius=depth.getDeclaredMethod("setWakeAndUnlockBlurRadius",float.class);
            if(doze.getReturnType()!=void.class||emit.getReturnType()!=Object.class||radius.getReturnType()!=void.class)
                throw new NoSuchMethodException("doze blur ABI");
            for(Method method:new Method[]{doze,emit,radius})method.setAccessible(true);
            hooks.add(module.hook(radius).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean project=false;
                try{
                    Object owner=chain.getThisObject();
                    project=enabled.get()&&producing.get()==owner&&ambient.getBoolean(owner)&&amount.getFloat(owner)>0
                        &&access.selectedOwn(repository.get(wallpapers.get(owner)));
                }catch(ReflectiveOperationException|RuntimeException unknown){/* Native contribution on unknown state. */}
                return project?chain.proceed(new Object[]{0f}):chain.proceed();
            }));
            hooks.add(module.hook(doze).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=null;
                try{owner=callbackOwner.get(chain.getThisObject());}catch(IllegalAccessException ignored){}
                if(owner!=null)access.depth=new java.lang.ref.WeakReference<>(owner);
                Object previous=producing.get();
                if(owner!=null)producing.set(owner);
                try{return chain.proceed();}finally{if(previous==null)producing.remove();else producing.set(previous);}
            }));
            hooks.add(module.hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=null;
                try{if(collectorId==null||collectorId.getInt(chain.getThisObject())==1)owner=collectorOwner.get(chain.getThisObject());}
                catch(IllegalAccessException ignored){}
                if(owner!=null)access.depth=new java.lang.ref.WeakReference<>(owner);
                Object previous=producing.get();
                if(owner!=null)producing.set(owner);
                try{return chain.proceed();}finally{if(previous==null)producing.remove();else producing.set(previous);}
            }));
            // Deoptimize both real setter callers; no global BlurUtils/resource changes.
            for(Method method:new Method[]{doze,emit,radius})
                if(!module.deoptimize(method))throw new IllegalStateException("doze blur deoptimize");
            enabled.set(true);
            log.accept("ALIVE_SYSTEMUI_DOZE_BLUR_READY exact selected ambient only");
        }catch(Throwable unknown){
            enabled.set(false);
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_SYSTEMUI_DOZE_BLUR_UNAVAILABLE "+unknown.getClass().getSimpleName());
        }
    }

    /** LOS private-field path: project only the doze contribution of the selected own ambient wallpaper. */
    private static void installDozeBlurFallback(XposedModule module,ClassLoader loader,Class<?> depth,
            Access access,Consumer<String> log,java.util.concurrent.atomic.AtomicBoolean enabled){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> interactor=type(loader,"com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor");
            Field wallpapers=field(depth,"wallpaperInteractor",interactor);
            Field repository=assignable(interactor,"wallpaperRepository",
                type(loader,"com.android.systemui.wallpapers.data.repository.WallpaperRepository"));
            Field ambient=field(depth,"wallpaperSupportsAmbientMode",boolean.class);
            Field amount=field(depth,"prevDozeAmount",float.class);
            Method radius=depth.getDeclaredMethod("getNewWakeBlurRadius",float.class);
            radius.setAccessible(true);
            if(radius.getReturnType()!=float.class)throw new NoSuchMethodException("doze blur fallback ABI");
            hooks.add(module.hook(radius).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(!enabled.get())return chain.proceed();
                try{
                    Object owner=chain.getThisObject();
                    if(ambient.getBoolean(owner)&&amount.getFloat(owner)>0
                            &&access.selectedOwn(repository.get(wallpapers.get(owner))))return 0f;
                }catch(ReflectiveOperationException|RuntimeException unknown){/* Native contribution on unknown state. */}
                return chain.proceed();
            }));
            // Deoptimize the private getter and its known callers; no global BlurUtils/resource change.
            if(!module.deoptimize(radius))throw new IllegalStateException("doze blur fallback deoptimize");
            try{module.deoptimize(depth.getDeclaredMethod("updateWakeBlurRadius",float.class));}catch(NoSuchMethodException ignored){}
            try{module.deoptimize(nestedDoze(loader,depth,"onDozeAmountChanged",float.class,float.class).getDeclaredMethod("onDozeAmountChanged",float.class,float.class));}catch(ReflectiveOperationException ignored){}
            try{module.deoptimize(nestedEmit(loader,depth).getDeclaredMethod("emit",Object.class,type(loader,"kotlin.coroutines.Continuation")));}catch(ReflectiveOperationException ignored){}
            enabled.set(true);
            log.accept("ALIVE_SYSTEMUI_DOZE_BLUR_READY private-field fallback");
        }catch(Throwable unknown){
            enabled.set(false);
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_SYSTEMUI_DOZE_BLUR_UNAVAILABLE "+unknown.getClass().getSimpleName());
        }
    }

    /** R8 numbering of Kotlin anonymous callbacks is not stable; discover by verified methods. */
    private static Class<?> nestedDoze(ClassLoader loader,Class<?> owner,String name,Class<?>...args)throws ClassNotFoundException{
        for(int index=1;index<=64;index++)try{
            Class<?> candidate=type(loader,owner.getName()+"$"+index);
            try{candidate.getDeclaredMethod(name,args);return candidate;}catch(NoSuchMethodException absent){}
        }catch(ClassNotFoundException absent){}
        throw new ClassNotFoundException(owner.getName()+"$*."+name);
    }
    /** The capability collector is a nested R8 lambda with an emit(Object,Continuation) body. */
    private static Class<?> nestedEmit(ClassLoader loader,Class<?> owner)throws ClassNotFoundException{
        Class<?> continuation=type(loader,"kotlin.coroutines.Continuation");
        for(int index=1;index<=64;index++){
            Class<?> candidate;
            try{candidate=type(loader,owner.getName()+"$"+index);}
            catch(ClassNotFoundException absent){continue;}
            try{
                Method emit=candidate.getDeclaredMethod("emit",Object.class,continuation);
                if(emit.getReturnType()==Object.class)return candidate;
            }catch(NoSuchMethodException absent){}
            for(int child=1;child<=16;child++)try{
                Class<?> nested=type(loader,candidate.getName()+"$"+child);
                Method emit=nested.getDeclaredMethod("emit",Object.class,continuation);
                if(emit.getReturnType()==Object.class)return nested;
            }catch(ClassNotFoundException|NoSuchMethodException absent){}
        }
        throw new ClassNotFoundException(owner.getName()+"$*#emit");
    }
    static record MatchToken(Object user,Object lock,Object home,int uid,int wallpaperId,int lockId) {
        boolean same(MatchToken other){return other!=null&&user==other.user&&lock==other.lock&&home==other.home&&uid==other.uid&&wallpaperId==other.wallpaperId&&lockId==other.lockId;}
    }
    private static final class Access implements AliveSleepHandoff.Selection {
        volatile Runnable changed=()->{};
        volatile java.lang.ref.WeakReference<Object> depth=new java.lang.ref.WeakReference<>(null);
        public Object token(Object repository){return selectedToken(repository);}
        public int uid(Object repository){Object value=selectedToken(repository);return value instanceof MatchToken token?token.uid:-1;}
        public void changes(Runnable listener){changed=listener;}
        final Constructor<?> constructor;
        final Class<?> flow,function;
        final Field ambient,lockInfo,homeInfo,settings,manager,background,selected,userInfo,userId,status;
        final Object complete;
        final Method combine,flowOn,current,getSetting,getInfo,getId,supports;
        final boolean lockIsState;
        final int settingMode; // 0: (default,user,name), 1: (default,name,user), 2: (name,default,user)
        int setting(Object secure,int user,String name)throws ReflectiveOperationException {
            return switch (settingMode) {
                case 2 -> (Integer)getSetting.invoke(secure,name,0,user);
                case 1 -> (Integer)getSetting.invoke(secure,0,name,user);
                default -> (Integer)getSetting.invoke(secure,0,user,name);
            };
        }
        private final Map<Object,Selection> selections=new WeakHashMap<>();
        private static final class Selection {
            final Object selected,lock,home;
            volatile MatchToken match;
            Selection(Object selected,Object lock,Object home){this.selected=selected;this.lock=lock;this.home=home;}
        }
        boolean selectedOwn(Object repository){return selectedToken(repository)!=null;}
        Object selectedToken(Object repository){
            try{
                Selection selection;
                synchronized(selections){selection=selections.get(repository);}
                if(selection==null)return null;
                MatchToken match=selection.match;
                return match!=null&&current.invoke(selection.selected)==match.user
                    &&status.get(match.user)==complete&&(!lockIsState||current.invoke(selection.lock)==match.lock)
                    &&current.invoke(selection.home)==match.home?match:null;
            }catch(ReflectiveOperationException|RuntimeException unknown){return null;}
        }
        Access(ClassLoader loader)throws ReflectiveOperationException {
            Class<?> repository=type(loader,"com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl");
            flow=type(loader,"kotlinx.coroutines.flow.Flow");
            function=type(loader,"kotlin.jvm.functions.Function5");
            Class<?> scope=type(loader,"kotlinx.coroutines.CoroutineScope"),dispatcher=type(loader,"kotlinx.coroutines.CoroutineDispatcher");
            Class<?> users=type(loader,"com.android.systemui.user.data.repository.UserRepository");
            Class<?> secure=type(loader,"com.android.systemui.util.settings.SecureSettings");
            Class<?> dispatcherType=type(loader,"com.android.systemui.broadcast.BroadcastDispatcher");
            Class<?> config=type(loader,"com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor");
            Class<?> configImpl=null;
            try{configImpl=type(loader,"com.android.systemui.common.ui.domain.interactor.ConfigurationInteractorImpl");}
            catch(ClassNotFoundException ignored){}
            // LineageOS 24.0 declares the constructor argument as the ConfigurationInteractor
            // interface; PixelOS narrows it to ConfigurationInteractorImpl. Both are source-verified.
            Constructor<?> found=null;
            if(configImpl!=null)try{
                found=repository.getDeclaredConstructor(scope,dispatcher,dispatcherType,users,
                    WallpaperManager.class,android.content.Context.class,secure,configImpl);
            }catch(NoSuchMethodException narrower){}
            if(found==null)try{
                found=repository.getDeclaredConstructor(scope,dispatcher,dispatcherType,users,
                    WallpaperManager.class,android.content.Context.class,secure,config);
            }catch(NoSuchMethodException interfaceShape){
                if(configImpl==null)throw interfaceShape;
                throw new NoSuchMethodException("WallpaperRepositoryImpl constructor");
            }
            constructor=found;
            constructor.setAccessible(true);
            ambient=field(repository,"wallpaperSupportsAmbientMode",flow);
            Class<?> state=type(loader,"kotlinx.coroutines.flow.StateFlow");
            // QPR2 keeps the lock StateFlow local to the constructor; its WALLPAPER_CHANGED stream then
            // re-runs the projection, and the transform re-reads the lock wallpaper id/info itself.
            Field lockState;
            try{lockState=repository.getDeclaredField("lockscreenWallpaperInfo");}catch(NoSuchFieldException absent){lockState=null;}
            lockIsState=lockState!=null;
            lockInfo=lockIsState?assignable(repository,"lockscreenWallpaperInfo",state):assignable(repository,"wallpaperChanged",flow);
            homeInfo=assignable(repository,"wallpaperInfo",state);
            settings=field(repository,"secureSettings",secure);manager=field(repository,"wallpaperManager",WallpaperManager.class);
            background=field(repository,"bgDispatcher",dispatcher);
            selected=assignable(type(loader,"com.android.systemui.user.data.repository.UserRepositoryImpl"),"selectedUser",state);
            Class<?> model=type(loader,"com.android.systemui.user.data.model.SelectedUserModel");
            Class<?> info=type(loader,"android.content.pm.UserInfo"),selection=type(loader,"com.android.systemui.user.data.model.SelectionStatus");
            userInfo=field(model,"userInfo",info);userId=field(info,"id",int.class);status=field(model,"selectionStatus",selection);
            complete=selection.getDeclaredField("SELECTION_COMPLETE").get(null);
            current=state.getMethod("getValue");
            // R8 may keep the kotlinx facade (FlowKt) or only its file parts (FlowKt__ZipKt/__ContextKt).
            Class<?> context=type(loader,"kotlin.coroutines.CoroutineContext");
            Method combined;
            try{combined=type(loader,"kotlinx.coroutines.flow.FlowKt").getDeclaredMethod("combine",flow,flow,flow,flow,function);}
            catch(ClassNotFoundException|NoSuchMethodException facade){combined=type(loader,"kotlinx.coroutines.flow.FlowKt__ZipKt").getDeclaredMethod("combine",flow,flow,flow,flow,function);}
            combine=combined;
            Method context2;
            try{context2=type(loader,"kotlinx.coroutines.flow.FlowKt").getDeclaredMethod("flowOn",flow,context);}
            catch(ClassNotFoundException|NoSuchMethodException facade){context2=type(loader,"kotlinx.coroutines.flow.FlowKt__ContextKt").getDeclaredMethod("flowOn",flow,context);}
            flowOn=context2;
            Class<?> proxy=type(loader,"com.android.systemui.util.settings.UserSettingsProxy");
            Method setting;int mode;
            try{setting=proxy.getMethod("getIntForUser",int.class,int.class,String.class);mode=0;}
            catch(NoSuchMethodException older){
                try{setting=proxy.getMethod("getIntForUser",int.class,String.class,int.class);mode=1;}
                catch(NoSuchMethodException lineage){setting=proxy.getMethod("getIntForUser",String.class,int.class,int.class);mode=2;}
            }
            getSetting=setting;settingMode=mode;
            getInfo=WallpaperManager.class.getDeclaredMethod("getWallpaperInfo",int.class,int.class);
            getId=WallpaperManager.class.getDeclaredMethod("getWallpaperIdForUser",int.class,int.class);
            supports=WallpaperInfo.class.getDeclaredMethod("supportsAmbientMode");
            for(Method method:new Method[]{combine,flowOn,current,getSetting,getInfo,getId,supports})method.setAccessible(true);
            if(getSetting.getReturnType()!=int.class||getId.getReturnType()!=int.class||getInfo.getReturnType()!=WallpaperInfo.class
                    ||supports.getReturnType()!=boolean.class||!flow.isAssignableFrom(combine.getReturnType())||flowOn.getReturnType()!=flow
                    ||!function.isInterface())throw new NoSuchMethodException("ambient projection ABI");
        }
        void project(Object repository,Object userRepository,android.content.Context context)throws ReflectiveOperationException {
            Object original=ambient.get(repository),selection=selected.get(userRepository);
            Object secure=settings.get(repository),wallpaper=manager.get(repository);
            Object lock=lockInfo.get(repository),home=homeInfo.get(repository);
            Selection gate=new Selection(selection,lock,home);
            synchronized(selections){selections.put(repository,gate);}
            Object transform=Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},(proxy,method,args)->{
                if(method.getName().equals("invoke")&&args!=null&&args.length==5){
                    Object nativeValue=args[0];
                    gate.match=null;
                    try{
                        Object model=args[3];
                        if(model==null||current.invoke(selection)!=model||status.get(model)!=complete)return nativeValue;
                        int user=userId.getInt(userInfo.get(model));
                        if(user<0||setting(secure,user,"doze_always_on")!=1)return nativeValue;
                        // A null lock WallpaperInfo may mean a distinct static lock wallpaper, not home inheritance.
                        int lockId=(Integer)getId.invoke(wallpaper,WallpaperManager.FLAG_LOCK,user);
                        WallpaperInfo actual=(WallpaperInfo)getInfo.invoke(wallpaper,WallpaperManager.FLAG_LOCK,user);
                        int which=WallpaperManager.FLAG_LOCK,actualId=lockId;
                        if(actual==null&&lockId<0){
                            which=WallpaperManager.FLAG_SYSTEM;actualId=(Integer)getId.invoke(wallpaper,which,user);
                            actual=(WallpaperInfo)getInfo.invoke(wallpaper,which,user);
                        }
                        boolean own=actualId>0&&actual!=null&&OWN.equals(actual.getComponent())&&(Boolean)supports.invoke(actual)
                            &&(Integer)getId.invoke(wallpaper,which,user)==actualId
                            &&(Integer)getId.invoke(wallpaper,WallpaperManager.FLAG_LOCK,user)==lockId
                            &&setting(secure,user,"doze_always_on")==1
                            &&current.invoke(selection)==model&&status.get(model)==complete;
                        // Gate publication precedes emission; the collector then recomputes only its doze contribution.
                        if(own&&(!lockIsState||current.invoke(lock)==args[1])&&current.invoke(home)==args[2]){
                            android.content.pm.ApplicationInfo app=(android.content.pm.ApplicationInfo)
                                android.content.pm.PackageManager.class.getMethod("getApplicationInfoAsUser",String.class,int.class,int.class)
                                    .invoke(context.getPackageManager(),OWN.getPackageName(),0,user);
                            gate.match=new MatchToken(model,args[1],args[2],app.uid,actualId,lockId);
                        }
                        return own?Boolean.TRUE:nativeValue;
                    }catch(ReflectiveOperationException|RuntimeException unknown){return nativeValue;}
                    finally{changed.run();}
                }
                return switch(method.getName()){
                    case "equals" -> proxy==args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Alive ambient projection";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            Object combined=combine.invoke(null,original,lockInfo.get(repository),homeInfo.get(repository),selection,transform);
            Object projection=flowOn.invoke(null,combined,background.get(repository));
            if(!flow.isInstance(projection))throw new IllegalStateException("host Flow required");
            // Replace only this new repository's cold projection; never write a StateFlow or global resource.
            ambient.set(repository,projection);
        }
    }
    private static boolean nativeExperiment(){
        try{
            Method get=Class.forName("android.os.SystemProperties").getMethod("get",String.class);
            return "1".equals(get.invoke(null,"debug.nc.alive_native"));
        }catch(ReflectiveOperationException|RuntimeException unknown){return false;}
    }
    static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    static Field field(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException {
        Field value=owner.getDeclaredField(name);value.setAccessible(true);
        if(value.getType()!=expected)throw new NoSuchFieldException(owner.getName()+"."+name);
        return value;
    }
    private static Field optionalField(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException{
        try{
            Field value=owner.getDeclaredField(name);value.setAccessible(true);
            if(value.getType()!=expected)throw new NoSuchFieldException(owner.getName()+"."+name);
            return value;
        }catch(NoSuchFieldException absent){return null;}
    }
    /** Declared as a concrete or interface Flow type that must implement the expected host interface. */
    private static Field assignable(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException {
        Field value=owner.getDeclaredField(name);value.setAccessible(true);
        if(!expected.isAssignableFrom(value.getType()))throw new NoSuchFieldException(owner.getName()+"."+name);
        return value;
    }
}
