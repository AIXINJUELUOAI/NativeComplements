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
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static ActivityOptions launchOptions(ActivityOptions original,Rect bounds)throws Exception{
        ActivityOptions copy=original==null?ActivityOptions.makeBasic():(ActivityOptions)
            ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class).invoke(null,original.toBundle());
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(copy,5);
        copy.setLaunchBounds(new Rect(bounds));return copy;
    }
    // Also runs only in system_server after install() preflights these API37 members.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static ActivityOptions installerOptions(ActivityOptions original)throws Exception{
        ActivityOptions copy=original==null?ActivityOptions.makeBasic():(ActivityOptions)
            ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class).invoke(null,original.toBundle());
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(copy,1);
        copy.setLaunchBounds(null);
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(copy,-1);
        ActivityOptions.class.getDeclaredMethod("setLaunchRootTask",Class.forName("android.window.WindowContainerToken")).invoke(copy,new Object[]{null});
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskFragmentToken",android.os.IBinder.class).invoke(copy,new Object[]{null});
        return copy;
    }
    static boolean installerIntent(Intent intent){
        return intent!=null&&("application/vnd.android.package-archive".equalsIgnoreCase(intent.getType())
            ||"android.intent.action.INSTALL_PACKAGE".equals(intent.getAction())
            ||"android.content.pm.action.CONFIRM_INSTALL".equals(intent.getAction()));
    }
    static boolean nativeFullscreenIntent(Intent intent){
        if(intent==null)return false;
        String action=intent.getAction();
        return installerIntent(intent)||action!=null&&(action.startsWith("android.settings.")
            ||"android.content.pm.action.REQUEST_PERMISSIONS".equals(action)
            ||"android.app.action.CONFIRM_DEVICE_CREDENTIAL".equals(action)
            ||"android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER".equals(action)
            ||Intent.ACTION_OPEN_DOCUMENT.equals(action)||Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)
            ||Intent.ACTION_CREATE_DOCUMENT.equals(action)||Intent.ACTION_GET_CONTENT.equals(action)
            ||Intent.ACTION_PICK.equals(action)||Intent.ACTION_PICK_ACTIVITY.equals(action)
            ||"android.provider.action.PICK_IMAGES".equals(action));
    }
    static boolean eligibleIntent(Intent intent,boolean chooser){
        if(intent==null)return false;
        // Installers also arrive as VIEW(content://..., APK MIME), not only INSTALL_PACKAGE.
        if(nativeFullscreenIntent(intent))return false;
        String action=intent.getAction();
        if(intent.hasCategory(Intent.CATEGORY_HOME)||Intent.ACTION_CHOOSER.equals(action)
            ||Intent.ACTION_DIAL.equals(action)||Intent.ACTION_CALL.equals(action)
            ||"android.intent.action.CALL_EMERGENCY".equals(action)||"android.intent.action.CALL_PRIVILEGED".equals(action)
            ||"android.intent.action.UNINSTALL_PACKAGE".equals(action))return false;
        String scheme=intent.getData()==null?null:intent.getData().getScheme();
        return !"tel".equalsIgnoreCase(scheme)&&!"voicemail".equalsIgnoreCase(scheme);
    }
    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,Consumer<String> log){
        java.util.List<XposedInterface.HookHandle> handles=new java.util.ArrayList<>();
        try{
            ClassLoader loader=a.task.getClassLoader();
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,loader);
            WindowServerTargets.StartUnchecked target=WindowServerTargets.startUnchecked(loader,starter);
            Method method=target.method();
            final int taskIndex=target.task(),flagsIndex=target.flags(),argumentCount=target.count();
            java.lang.reflect.Field optionsField=a.field(starter,"mOptions");
            Method launchParams=a.method(starter,"computeLaunchParams",a.activity,a.activity,a.task);
            handles.add(module.hook(launchParams).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                try{
                    ActivityOptions kept=engine.ownedLaunchOptions(chain.getArg(0),chain.getArg(1),chain.getArg(2),
                        (ActivityOptions)optionsField.get(chain.getThisObject()));
                    if(kept!=null)optionsField.set(chain.getThisObject(),kept);
                }catch(Exception error){log.accept("OWNED_LAUNCH_NATIVE "+FrameworkAccess.reason(error));}
                return chain.proceed();
            }));
            if(method.getReturnType()!=int.class)throw new NoSuchMethodException("share start result");
            a.field(starter,"mLastStartActivityRecord");
            a.method(a.activity,"isResolverOrDelegateActivity");
            a.method(android.content.pm.PackageManager.class,"getPermissionControllerPackageName");
            a.method(ActivityOptions.class,"fromBundle",Bundle.class);
            a.method(ActivityOptions.class,"getLaunchWindowingMode");a.method(ActivityOptions.class,"setLaunchWindowingMode",int.class);
            a.method(ActivityOptions.class,"setLaunchTaskId",int.class);
            a.method(ActivityOptions.class,"setLaunchRootTask",Class.forName("android.window.WindowContainerToken",false,loader));
            a.method(ActivityOptions.class,"setLaunchTaskFragmentToken",android.os.IBinder.class);
            java.lang.reflect.Field starting=a.field(starter,"mStartActivity");
            Method targetTask=a.method(starter,"computeTargetTask");
            if(targetTask.getReturnType()!=a.task)throw new NoSuchMethodException("handoff target task");
            Method reusable=a.method(starter,"resolveReusableTask",boolean.class);
            if(reusable.getReturnType()!=a.task)throw new NoSuchMethodException("handoff reusable task");
            handles.add(module.hook(reusable).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();WindowEngine.ShareLaunch selected=engine.shareLaunch.get();
                // Shared affinities must not move the caller's fullscreen task into freeform.
                return selected!=null&&starting.get(chain.getThisObject())==selected.request()&&result==selected.sourceTask()?null:result;
            }));
            handles.add(module.hook(targetTask).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                WindowEngine.ShareLaunch selected=engine.shareLaunch.get();
                // Use native setNewTask without injecting NEW_TASK into the Intent:
                // that flag would cancel startActivityForResult before task selection.
                if(selected!=null&&starting.get(chain.getThisObject())==selected.request())return null;
                return chain.proceed();
            }));
            handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                WindowEngine.NativeForeground previousForeground=engine.launchForeground.get();
                try{engine.launchForeground.set(engine.nativeForeground());}
                catch(Exception error){engine.launchForeground.remove();log.accept("WINDOW_FOREGROUND_SNAPSHOT_FAILED "+FrameworkAccess.reason(error));}
                try{
                WindowEngine.ShareLaunch selected=null;
                if(chain.getArg(taskIndex)==null&&chain.getArg(taskIndex+1)==null){
                    try{
                        selected=engine.prepareInstaller(chain.getArg(0),chain.getArg(1),(ActivityOptions)chain.getArg(5),(Integer)chain.getArg(flagsIndex));
                        if(selected==null)selected=engine.prepareShare(chain.getArg(0),chain.getArg(1),(ActivityOptions)chain.getArg(5),(Integer)chain.getArg(flagsIndex));
                    }
                    catch(Exception error){log.accept("SHARE_WINDOW_NATIVE "+FrameworkAccess.reason(error));}
                }
                if(selected==null)return chain.proceed();
                WindowEngine.ShareLaunch previous=engine.shareLaunch.get();engine.shareLaunch.set(selected);
                Object[] args=new Object[argumentCount];for(int i=0;i<args.length;i++)args[i]=chain.getArg(i);args[5]=selected.options();
                try{return chain.proceed(args);}
                finally{
                    engine.shareFinished(selected,chain.getThisObject());
                    if(previous==null)engine.shareLaunch.remove();else engine.shareLaunch.set(previous);
                }
                }finally{if(previousForeground==null)engine.launchForeground.remove();else engine.launchForeground.set(previousForeground);}
            }));
            log.accept("SHARE_WINDOW_READY inter-app source/result/URI chain preserved");
        }catch(Throwable error){for(XposedInterface.HookHandle handle:handles)try{handle.unhook();}catch(Throwable ignored){}log.accept("SHARE_WINDOW_UNAVAILABLE "+FrameworkAccess.reason(error));}
    }
}
