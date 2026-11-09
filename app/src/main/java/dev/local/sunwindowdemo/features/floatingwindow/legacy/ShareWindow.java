package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional early native routing: keep the real Intent, grants, result chain and start verdict. */
final class ShareWindow {
    // Runs inside system_server; the optional hook preflights this exact API37 member.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static ActivityOptions launchOptions(ActivityOptions original,Rect bounds)throws Exception{
        ActivityOptions copy=original==null?ActivityOptions.makeBasic():(ActivityOptions)
            ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class).invoke(null,original.toBundle());
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(copy,5);
        copy.setLaunchBounds(new Rect(bounds));return copy;
    }
    static boolean eligibleIntent(Intent intent,boolean chooser){
        if(intent==null)return false;
        String action=intent.getAction();
        if(Intent.ACTION_SEND.equals(action)||Intent.ACTION_SEND_MULTIPLE.equals(action))return true;
        if(!chooser||!Intent.ACTION_VIEW.equals(action)||intent.getData()==null)return false;
        String scheme=intent.getData().getScheme();
        return "http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)
            ||"content".equalsIgnoreCase(scheme)||"file".equalsIgnoreCase(scheme);
    }
    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,Consumer<String> log){
        XposedInterface.HookHandle handle=null;
        try{
            ClassLoader loader=a.task.getClassLoader();
            String[] names={"com.android.server.wm.ActivityRecord","com.android.server.wm.ActivityRecord",
                "android.service.voice.IVoiceInteractionSession","com.android.internal.app.IVoiceInteractor",null,
                "android.app.ActivityOptions","com.android.server.wm.Task","com.android.server.wm.TaskFragment",
                "com.android.server.wm.BackgroundActivityStartController$BalVerdict","com.android.server.uri.NeededUriGrants",null,
                "com.android.server.wm.Transition",null,null};
            Class<?>[] parameters=new Class<?>[names.length];
            for(int i=0;i<names.length;i++)parameters[i]=names[i]==null?(i==12?boolean.class:int.class):Class.forName(names[i],false,loader);
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,loader);
            Method method=a.method(starter,"startActivityUnchecked",parameters);
            if(method.getReturnType()!=int.class)throw new NoSuchMethodException("share start result");
            a.field(starter,"mLastStartActivityRecord");
            a.method(a.activity,"isResolverOrDelegateActivity");
            a.method(ActivityOptions.class,"fromBundle",Bundle.class);
            a.method(ActivityOptions.class,"getLaunchWindowingMode");a.method(ActivityOptions.class,"setLaunchWindowingMode",int.class);
            handle=module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                WindowEngine.ShareLaunch selected=null;
                if(chain.getArg(6)==null&&chain.getArg(7)==null){
                    try{selected=engine.prepareShare(chain.getArg(0),chain.getArg(1),(ActivityOptions)chain.getArg(5));}
                    catch(Exception error){log.accept("SHARE_WINDOW_NATIVE "+FrameworkAccess.reason(error));}
                }
                if(selected==null)return chain.proceed();
                WindowEngine.ShareLaunch previous=engine.shareLaunch.get();engine.shareLaunch.set(selected);
                Object[] args=new Object[parameters.length];for(int i=0;i<args.length;i++)args[i]=chain.getArg(i);args[5]=selected.options();
                try{return chain.proceed(args);}
                finally{
                    engine.shareFinished(selected,chain.getThisObject());
                    if(previous==null)engine.shareLaunch.remove();else engine.shareLaunch.set(previous);
                }
            });
            log.accept("SHARE_WINDOW_READY");
        }catch(Throwable error){if(handle!=null)try{handle.unhook();}catch(Throwable ignored){}log.accept("SHARE_WINDOW_UNAVAILABLE "+FrameworkAccess.reason(error));}
    }
}
