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
        XposedInterface.HookHandle hook=null;
        try{
            Access access=new Access(loader);
            hook=module.hook(access.constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{access.project(chain.getThisObject(),chain.getArg(3));}
                catch(ReflectiveOperationException|RuntimeException unavailable){
                    log.accept("ALIVE_SYSTEMUI_PROJECTION_UNAVAILABLE "+unavailable.getClass().getSimpleName());
                }
                return result;
            });
            if(!module.deoptimize(access.constructor))throw new IllegalStateException("wallpaper repository constructor deoptimize");
            installDozeBlur(module,loader,access,log);
            installSleepFade(module,loader,access,log);
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
            Class<?> callback=type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController$statusBarStateCallback$1");
            Class<?> collector=type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController$2$1");
            Class<?> interactor=type(loader,"com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor");
            Field callbackOwner=field(callback,"this$0",depth),collectorOwner=field(collector,"this$0",depth);
            Field collectorId=field(collector,"$r8$classId",int.class);
            Field wallpapers=field(depth,"wallpaperInteractor",interactor);
            Field repository=field(interactor,"wallpaperRepository",access.constructor.getDeclaringClass());
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
                try{if(collectorId.getInt(chain.getThisObject())==1)owner=collectorOwner.get(chain.getThisObject());}
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

    /** Preserve the native dim target; remove only its additional 333ms delay + 733ms fade once per sleep. */
    private static void installSleepFade(XposedModule module,ClassLoader loader,Access access,Consumer<String> log){
        XposedInterface.HookHandle hook=null;
        java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            Class<?> depth=type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController");
            Class<?> interactor=type(loader,"com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor");
            Field wallpapers=field(depth,"wallpaperInteractor",interactor);
            Field repository=field(interactor,"wallpaperRepository",access.constructor.getDeclaringClass());
            Class<?> keyguard=type(loader,"com.android.systemui.statusbar.policy.KeyguardStateControllerImpl");
            Field keyguardState=field(depth,"keyguardStateController",type(loader,"com.android.systemui.statusbar.policy.KeyguardStateController"));
            Field showing=field(keyguard,"mShowing",boolean.class),occluded=field(keyguard,"mOccluded",boolean.class);
            Field going=field(keyguard,"mKeyguardGoingAway",boolean.class),bouncer=field(keyguard,"mPrimaryBouncerShowing",boolean.class);
            Field fading=field(keyguard,"mKeyguardFadingAway",boolean.class);
            Class<?> binder=type(loader,"com.android.systemui.keyguard.ui.binder.LightRevealScrimViewBinder$bind$1$1$2$1");
            Field scrim=field(binder,"$revealScrim",type(loader,"com.android.systemui.statusbar.LightRevealScrim"));
            Class<?> pair=type(loader,"kotlin.Pair");
            Constructor<?> create=pair.getDeclaredConstructor(Object.class,Object.class);
            Method first=pair.getDeclaredMethod("component1"),second=pair.getDeclaredMethod("component2");
            Method emit=binder.getDeclaredMethod("emit",Object.class,type(loader,"kotlin.coroutines.Continuation"));
            if(emit.getReturnType()!=Object.class)throw new NoSuchMethodException("sleep fade ABI");
            for(AccessibleObject member:new AccessibleObject[]{create,first,second,emit})member.setAccessible(true);
            // Binder collection is on the view's main lifecycle; one identity-scoped episode, never per-frame hooks.
            Object[] episode={null};long[] deadline={0};boolean[] high={false};
            hook=module.hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object replacement=null;
                try{
                    if(enabled.get()&&android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){
                        android.view.View view=(android.view.View)scrim.get(chain.getThisObject());
                        Object input=chain.getArg(0);
                        if(!pair.isInstance(input))throw new IllegalArgumentException("native Pair required");
                        Object nativeTarget=first.invoke(input);boolean animate=Boolean.TRUE.equals(second.invoke(input));
                        float target=((Number)nativeTarget).floatValue();
                        android.view.Display display=view.getDisplay();
                        boolean displayAllowed=display!=null&&display.getDisplayId()==android.view.Display.DEFAULT_DISPLAY
                            &&(display.getState()==android.view.Display.STATE_ON||display.getState()==android.view.Display.STATE_DOZE);
                        android.os.PowerManager power=view.getContext().getSystemService(android.os.PowerManager.class);
                        Object owner=access.depth.get();
                        Object repo=owner==null?null:repository.get(wallpapers.get(owner));
                        Object identity=repo==null?null:access.selectedToken(repo);
                        boolean own=displayAllowed&&power!=null&&!power.isInteractive()&&identity!=null;
                        if(!own){episode[0]=null;deadline[0]=0;high[0]=false;}
                        else{
                            long now=android.os.SystemClock.uptimeMillis();
                            if(target==1f){
                                if(!high[0]){episode[0]=identity;deadline[0]=now+7800;}
                                high[0]=true;
                            }else if(target>0f&&target<1f){
                                Object state=keyguardState.get(owner);
                                Access.Match before=episode[0] instanceof Access.Match value?value:null;
                                Access.Match current=(Access.Match)identity;
                                boolean same=before!=null&&before.user==current.user&&before.lock==current.lock&&before.home==current.home;
                                boolean allowed=same&&now<deadline[0]&&animate&&keyguard.isInstance(state)
                                    &&showing.getBoolean(state)&&!occluded.getBoolean(state)&&!going.getBoolean(state)
                                    &&!bouncer.getBoolean(state)&&!fading.getBoolean(state);
                                // Consume even when unsafe: no late replay after unlock, occlusion or an expired episode.
                                episode[0]=null;deadline[0]=0;
                                if(allowed)replacement=create.newInstance(nativeTarget,Boolean.FALSE);
                                log.accept("ALIVE_SLEEP_FADE target="+target+" nativeAnimate="+animate+" projected="+allowed);
                            }else{episode[0]=null;deadline[0]=0;high[0]=false;}
                        }
                    }
                }catch(ReflectiveOperationException|RuntimeException unknown){
                    episode[0]=null;deadline[0]=0;high[0]=false;
                }
                return replacement==null?chain.proceed():chain.proceed(new Object[]{replacement,chain.getArg(1)});
            });
            if(!module.deoptimize(emit))throw new IllegalStateException("sleep fade deoptimize");
            enabled.set(true);log.accept("ALIVE_SLEEP_FADE_READY native target preserved");
        }catch(Throwable unknown){
            enabled.set(false);
            if(hook!=null)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_SLEEP_FADE_UNAVAILABLE "+unknown.getClass().getSimpleName());
        }
    }

    private static final class Access {
        volatile java.lang.ref.WeakReference<Object> depth=new java.lang.ref.WeakReference<>(null);
        final Constructor<?> constructor;
        final Class<?> flow,function;
        final Field ambient,lockInfo,homeInfo,settings,manager,background,selected,userInfo,userId,status;
        final Object complete;
        final Method combine,flowOn,current,getSetting,getInfo,getId,supports;
        private final Map<Object,Selection> selections=new WeakHashMap<>();
        private static final class Selection {
            final Object selected,lock,home;
            volatile Match match;
            Selection(Object selected,Object lock,Object home){this.selected=selected;this.lock=lock;this.home=home;}
        }
        private record Match(Object user,Object lock,Object home) {}
        boolean selectedOwn(Object repository){return selectedToken(repository)!=null;}
        Object selectedToken(Object repository){
            try{
                Selection selection;
                synchronized(selections){selection=selections.get(repository);}
                if(selection==null)return null;
                Match match=selection.match;
                return match!=null&&current.invoke(selection.selected)==match.user
                    &&status.get(match.user)==complete&&current.invoke(selection.lock)==match.lock
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
            constructor=repository.getDeclaredConstructor(scope,dispatcher,
                type(loader,"com.android.systemui.broadcast.BroadcastDispatcher"),users,WallpaperManager.class,
                android.content.Context.class,secure,type(loader,"com.android.systemui.common.ui.domain.interactor.ConfigurationInteractorImpl"));
            constructor.setAccessible(true);
            ambient=field(repository,"wallpaperSupportsAmbientMode",flow);
            Class<?> readonly=type(loader,"kotlinx.coroutines.flow.ReadonlyStateFlow");
            lockInfo=field(repository,"lockscreenWallpaperInfo",readonly);homeInfo=field(repository,"wallpaperInfo",readonly);
            settings=field(repository,"secureSettings",secure);manager=field(repository,"wallpaperManager",WallpaperManager.class);
            background=field(repository,"bgDispatcher",dispatcher);
            selected=field(type(loader,"com.android.systemui.user.data.repository.UserRepositoryImpl"),"selectedUser",readonly);
            Class<?> model=type(loader,"com.android.systemui.user.data.model.SelectedUserModel");
            Class<?> info=type(loader,"android.content.pm.UserInfo"),selection=type(loader,"com.android.systemui.user.data.model.SelectionStatus");
            userInfo=field(model,"userInfo",info);userId=field(info,"id",int.class);status=field(model,"selectionStatus",selection);
            complete=selection.getDeclaredField("SELECTION_COMPLETE").get(null);
            current=type(loader,"kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
            Class<?> operators=type(loader,"kotlinx.coroutines.flow.FlowKt");
            combine=operators.getDeclaredMethod("combine",flow,flow,flow,flow,function);
            flowOn=operators.getDeclaredMethod("flowOn",flow,type(loader,"kotlin.coroutines.CoroutineContext"));
            getSetting=type(loader,"com.android.systemui.util.settings.UserSettingsProxy").getMethod("getIntForUser",int.class,int.class,String.class);
            getInfo=WallpaperManager.class.getDeclaredMethod("getWallpaperInfo",int.class,int.class);
            getId=WallpaperManager.class.getDeclaredMethod("getWallpaperIdForUser",int.class,int.class);
            supports=WallpaperInfo.class.getDeclaredMethod("supportsAmbientMode");
            for(Method method:new Method[]{combine,flowOn,current,getSetting,getInfo,getId,supports})method.setAccessible(true);
            if(getSetting.getReturnType()!=int.class||getId.getReturnType()!=int.class||getInfo.getReturnType()!=WallpaperInfo.class
                    ||supports.getReturnType()!=boolean.class||!flow.isAssignableFrom(combine.getReturnType())||flowOn.getReturnType()!=flow
                    ||!function.isInterface())throw new NoSuchMethodException("ambient projection ABI");
        }
        void project(Object repository,Object userRepository)throws ReflectiveOperationException {
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
                        if(user<0||(Integer)getSetting.invoke(secure,0,user,"doze_always_on")!=1)return nativeValue;
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
                            &&(Integer)getSetting.invoke(secure,0,user,"doze_always_on")==1
                            &&current.invoke(selection)==model&&status.get(model)==complete;
                        // Gate publication precedes emission; the collector then recomputes only its doze contribution.
                        if(own&&current.invoke(lock)==args[1]&&current.invoke(home)==args[2])
                            gate.match=new Match(model,args[1],args[2]);
                        return own?Boolean.TRUE:nativeValue;
                    }catch(ReflectiveOperationException|RuntimeException unknown){return nativeValue;}
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
    private static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException {
        Field value=owner.getDeclaredField(name);value.setAccessible(true);
        if(value.getType()!=expected)throw new NoSuchFieldException(owner.getName()+"."+name);
        return value;
    }
}
