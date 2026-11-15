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
    private final java.util.List<XposedInterface.HookHandle> hooks=new java.util.ArrayList<>();

    LauncherThemeReporter(Consumer<Boolean> listener){this.listener=listener;}
    void install(XposedModule module,ClassLoader loader)throws ReflectiveOperationException{
        Class<?> theme=Class.forName("com.android.launcher3.graphics.ThemeManager",false,loader);
        singleton=theme.getField("INSTANCE").get(null);
        get=singleton.getClass().getMethod("get",Context.class);
        state=theme.getField("iconState");controller=state.getType().getField("themeController");
        Method verify=theme.getDeclaredMethod("verifyIconState");
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
    }
    synchronized void connect(Context app){
        if(context!=null)return;
        Context application=app.getApplicationContext();context=application!=null?application:app;
        thread=new HandlerThread("NcLauncherTheme");thread.start();worker=new Handler(thread.getLooper());
        main.post(()->refresh(null));
    }
    private boolean read(Object manager){
        try{
            if(manager==null)manager=get.invoke(singleton,context);
            Object iconState=manager==null?null:state.get(manager);
            return iconState!=null&&controller.get(iconState)!=null;
        }catch(ReflectiveOperationException|RuntimeException failure){return false;}
    }
    private void refresh(Object manager){
        if(context==null||worker==null)return;
        boolean themed=read(manager);
        main.post(()->listener.accept(themed));
        worker.post(()->report(themed));
    }
    private void report(boolean themed){
        if(Boolean.valueOf(themed).equals(reported))return;
        try{
            Bundle report=new Bundle();report.putBoolean("systemThemed",themed);
            context.getContentResolver().call(IconLayerStore.URI,"report",null,report);
            reported=themed;
        }catch(RuntimeException failure){android.util.Log.w("NcLauncherTheme","theme report failed",failure);}
    }
    synchronized void close(){
        for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
        hooks.clear();
        main.removeCallbacksAndMessages(null);
        if(worker!=null)worker.removeCallbacksAndMessages(null);
        if(thread!=null)thread.quitSafely();
        worker=null;thread=null;context=null;
    }
}
