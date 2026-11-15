package dev.local.sunwindowdemo.features.alive;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.function.Consumer;

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
            log.accept("ALIVE_SYSTEMUI_READY selected ambient wallpaper only");
        }catch(Throwable unavailable){
            if(hook!=null)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_SYSTEMUI_UNAVAILABLE "+unavailable.getClass().getSimpleName());
        }
    }

    private static final class Access {
        final Constructor<?> constructor;
        final Class<?> flow,function;
        final Field ambient,lockInfo,homeInfo,settings,manager,background,selected,userInfo,userId,status;
        final Object complete;
        final Method combine,flowOn,current,getSetting,getInfo,getId,supports;
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
            Object transform=Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},(proxy,method,args)->{
                if(method.getName().equals("invoke")&&args!=null&&args.length==5){
                    Object nativeValue=args[0];
                    if(Boolean.TRUE.equals(nativeValue))return nativeValue;
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
                        return actualId>0&&actual!=null&&OWN.equals(actual.getComponent())&&(Boolean)supports.invoke(actual)
                            &&(Integer)getId.invoke(wallpaper,which,user)==actualId
                            &&(Integer)getId.invoke(wallpaper,WallpaperManager.FLAG_LOCK,user)==lockId
                            &&(Integer)getSetting.invoke(secure,0,user,"doze_always_on")==1
                            &&current.invoke(selection)==model&&status.get(model)==complete?Boolean.TRUE:nativeValue;
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
