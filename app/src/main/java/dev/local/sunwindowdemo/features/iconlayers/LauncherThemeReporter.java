package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.os.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.function.Consumer;

/** Read-only Launcher theme observation; no icon rendering, cache or replacement hooks. */
final class LauncherThemeReporter {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Consumer<Boolean> listener;
    private Object singleton;
    private Method get;
    private Field state,controller;
    private Context context;
    private Handler worker;
    private HandlerThread thread;
    private Boolean reported;
    private boolean retryPending, lastThemed;
    private long retryDelay=3000,lastDeferredLog;
    private final Runnable retryReport=()->{retryPending=false;if(worker!=null)worker.post(()->{if(context!=null)report(lastThemed);});};
    private final java.util.List<XposedInterface.HookHandle> hooks=new java.util.ArrayList<>();

    LauncherThemeReporter(Consumer<Boolean> listener){this.listener=listener;}
    void install(XposedModule module,ClassLoader loader)throws ReflectiveOperationException{
        Class<?> theme=Class.forName("com.android.launcher3.graphics.ThemeManager",false,loader);
        singleton=theme.getField("INSTANCE").get(null);
        get=singleton.getClass().getMethod("get",Context.class);
        try{state=theme.getDeclaredField("iconState");state.setAccessible(true);
            controller=state.getType().getDeclaredField("themeController");controller.setAccessible(true);
        }catch(NoSuchFieldException kotlinProperties){state=null;controller=null;}
        Method verify=theme.getDeclaredMethod("verifyIconState");verify.setAccessible(true);
        try{
            hooks.add(module.hook(verify).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                // ThemeManager may still be under construction: read this instance, never its singleton.
                refresh(chain.getThisObject());return result;
            }));
            hooks.add(module.hook(android.app.Application.class.getDeclaredMethod("attach",Context.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    try{connect((Context)chain.getThisObject());}
                    catch(RuntimeException failure){close();android.util.Log.w("NcLauncherTheme","theme connection failed",failure);}
                    return result;
                }));
        }catch(RuntimeException|ReflectiveOperationException failure){close();throw failure;}
        android.util.Log.i("NcLauncherTheme","installed");
    }
    synchronized void connect(Context app){
        if(context!=null)return;
        Context application=app.getApplicationContext();context=application!=null?application:app;
        thread=new HandlerThread("NcLauncherTheme");thread.start();worker=new Handler(thread.getLooper());
        android.util.Log.i("NcLauncherTheme","connected");
        main.post(()->refresh(null));
    }
    private boolean read(Object manager){
        try{
            if(manager==null)manager=get.invoke(singleton,context);
            if(manager==null)return false;
            if(state==null||controller==null)return LauncherIconAccess.themeController(manager)!=null;
            Object iconState=state.get(manager);
            return iconState!=null&&controller.get(iconState)!=null;
        }catch(Exception failure){return false;}
    }
    private void refresh(Object manager){
        if(context==null||worker==null)return;
        boolean themed=read(manager);
        main.post(()->listener.accept(themed));
        worker.post(()->report(themed));
    }
    private void report(boolean themed){
        lastThemed=themed;
        if(Boolean.valueOf(themed).equals(reported)){
            main.removeCallbacks(retryReport);retryPending=false;return;
        }
        try{
            Bundle report=new Bundle();report.putBoolean("systemThemed",themed);
            if(context.getContentResolver().call(IconLayerStore.URI,"report",null,report)==null)
                throw new IllegalStateException("theme report not acknowledged");
            reported=themed;retryPending=false;retryDelay=3000;main.removeCallbacks(retryReport);
            android.util.Log.i("NcLauncherTheme","reported systemThemed="+themed);
        }catch(RuntimeException failure){
            // The module provider can be unavailable while the device is locked/direct-boot.
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastDeferredLog>60_000){
                lastDeferredLog=now;
                android.util.Log.w("NcLauncherTheme","theme report deferred",failure);
            }
            if(!retryPending){
                retryPending=true;
                main.postDelayed(retryReport,retryDelay);
                retryDelay=Math.min(30000,retryDelay*2);
            }
        }
    }
    synchronized void close(){
        for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
        hooks.clear();
        main.removeCallbacks(retryReport);
        main.removeCallbacksAndMessages(null);
        if(worker!=null){worker.removeCallbacks(retryReport);worker.removeCallbacksAndMessages(null);}
        if(thread!=null)thread.quitSafely();
        worker=null;thread=null;context=null;
    }
}
