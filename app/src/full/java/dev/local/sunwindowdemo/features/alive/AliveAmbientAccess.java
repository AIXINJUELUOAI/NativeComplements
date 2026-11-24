package dev.local.sunwindowdemo.features.alive;

import android.content.ComponentName;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** User-authorized bind-local ambient policy exception, not a PackageManager permission grant. */
public final class AliveAmbientAccess {
    private static final ComponentName AMBIENT=new ComponentName("dev.nativecomplements.module",
        "dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService");
    private AliveAmbientAccess() {}

    private static final class BindScope {
        final ComponentName component;
        final int user;
        boolean consumed;
        BindScope(ComponentName component,int user){this.component=component;this.user=user;}
        boolean suppress(ComponentName info){
            if(consumed||user<0||!AMBIENT.equals(component)||!component.equals(info))return false;
            consumed=true;return true;
        }
    }
    public static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        AtomicBoolean ready=new AtomicBoolean();
        ThreadLocal<BindScope> binding=new ThreadLocal<>();
        try{
            Class<?> service=Class.forName("com.android.server.wallpaper.WallpaperManagerService",false,loader);
            Class<?> description=Class.forName("android.app.wallpaper.WallpaperDescription",false,loader);
            Class<?> data=Class.forName("com.android.server.wallpaper.WallpaperData",false,loader);
            Class<?> callback=Class.forName("android.os.IRemoteCallback",false,loader);
            Class<?> info=Class.forName("android.app.WallpaperInfo",false,loader);
            Method bind=service.getDeclaredMethod("bindWallpaperDescriptionLocked",description,boolean.class,boolean.class,data,callback);
            Method descriptionComponent=description.getDeclaredMethod("getComponent");
            Method infoComponent=info.getDeclaredMethod("getComponent");
            Method ambient=info.getDeclaredMethod("supportsAmbientMode");
            Field user=data.getDeclaredField("userId");
            if(bind.getReturnType()!=boolean.class||ambient.getReturnType()!=boolean.class||user.getType()!=int.class
                ||descriptionComponent.getReturnType()!=ComponentName.class||infoComponent.getReturnType()!=ComponentName.class)
                throw new NoSuchMethodException("ambient bind signatures");
            bind.setAccessible(true);ambient.setAccessible(true);user.setAccessible(true);
            descriptionComponent.setAccessible(true);infoComponent.setAccessible(true);
            hooks.add(module.hook(ambient).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object nativeResult=chain.proceed();
                BindScope scope=binding.get();
                if(ready.get()&&Boolean.TRUE.equals(nativeResult)&&scope!=null){
                    try{if(scope.suppress((ComponentName)infoComponent.invoke(chain.getThisObject())))return false;}
                    catch(Exception ignored){} // Metadata/reflection failure keeps the original denial policy.
                }
                return nativeResult;
            }));
            hooks.add(module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                BindScope previous=binding.get();
                // Every nested bind replaces the frame, including foreign/unknown binds: no inherited opt-in.
                BindScope current=new BindScope(null,-1);
                if(ready.get())try{
                    ComponentName component=(ComponentName)descriptionComponent.invoke(chain.getArg(0));
                    int targetUser=user.getInt(chain.getArg(3));
                    current=new BindScope(component,targetUser);
                }catch(Exception ignored){}
                binding.set(current);
                try{return chain.proceed();}
                finally{if(previous==null)binding.remove();else binding.set(previous);}
            }));
            // The verified caller must not retain an inlined supportsAmbientMode implementation.
            if(!module.deoptimize(bind))throw new IllegalStateException("ambient bind deoptimization unavailable");
            ready.set(true);log.accept("ALIVE_AMBIENT_BIND_READY");
        }catch(Throwable unavailable){
            ready.set(false);binding.remove();
            for(int i=hooks.size()-1;i>=0;i--)try{hooks.get(i).unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_AMBIENT_BIND_UNAVAILABLE "+unavailable.getClass().getSimpleName());
        }
    }
}
