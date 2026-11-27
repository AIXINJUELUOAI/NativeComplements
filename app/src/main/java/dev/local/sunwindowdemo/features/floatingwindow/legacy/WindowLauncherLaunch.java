package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.content.Intent;
import dev.local.sunwindowdemo.core.LauncherTargets;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Local native-success adapter. It never starts an Intent or substitutes a native verdict/options. */
final class WindowLauncherLaunch {
    private final FrameworkAccess a;
    private final WindowEngine engine;
    private final Consumer<String> log;
    private final Field doResume,balVerdict;
    private final Method avoidFront,balAllows;
    final ThreadLocal<Recent> recent=new ThreadLocal<>();
    private volatile boolean ready;

    WindowLauncherLaunch(FrameworkAccess a,WindowEngine engine,Class<?> starter,Consumer<String> log)throws Exception {
        this(a,engine,starter,ActivityOptions.class,Class.forName("android.window.WindowContainerToken"),log);
    }
    // Exact same ABI checks with a fixture owner; production always selects ActivityOptions.class above.
    WindowLauncherLaunch(FrameworkAccess a,WindowEngine engine,Class<?> starter,Class<?> optionsType,Class<?> token,Consumer<String> log)throws Exception {
        this.a=a;this.engine=engine;this.log=log;
        doResume=a.field(starter,"mDoResume");balVerdict=a.field(starter,"mBalVerdict");
        if(doResume.getType()!=boolean.class)throw new NoSuchFieldException("mDoResume");
        avoidFront=instance(a.method(starter,"avoidMoveToFront"),boolean.class);
        balAllows=instance(a.method(balVerdict.getType(),"allows"),boolean.class);
        for(String name:new String[]{"getLaunchWindowingMode","getLaunchActivityType","getLaunchTaskId","getLaunchDisplayId",
                "getCallerDisplayId","getLaunchTaskDisplayAreaFeatureId"})instance(a.method(optionsType,name),int.class);
        for(String name:new String[]{"getAvoidMoveToFront","getTaskOverlay","getLaunchTaskBehind","getTransientLaunch",
                "getLaunchNextToBubble","getTaskAlwaysOnTop"})instance(a.method(optionsType,name),boolean.class);
        instance(a.method(optionsType,"getLaunchRootTask"),token);
        instance(a.method(optionsType,"getLaunchTaskDisplayArea"),token);
        instance(a.method(optionsType,"getLaunchTaskFragmentToken"),android.os.IBinder.class);
        instance(a.method(optionsType,"getLaunchBounds"),android.graphics.Rect.class);
    }
    static WindowLauncherLaunch install(XposedModule module,FrameworkAccess a,WindowEngine engine,
            List<XposedInterface.HookHandle> handles,Consumer<String> log) {
        try {
            ClassLoader loader=a.task.getClassLoader();
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,loader);
            Class<?> supervisor=Class.forName("com.android.server.wm.ActivityTaskSupervisor",false,loader);
            Class<?> safe=Class.forName("com.android.server.wm.SafeActivityOptions",false,loader);
            WindowLauncherLaunch access=new WindowLauncherLaunch(a,engine,starter,log);
            Method fromRecents=instance(a.method(supervisor,"startActivityFromRecents",int.class,int.class,int.class,safe),int.class);
            Method options=instance(a.method(safe,"getOptions",supervisor),ActivityOptions.class);
            Class<?> controller=Class.forName("com.android.server.wm.ActivityStartController",false,loader);
            Method post=instance(a.method(controller,"postStartActivityProcessingForLastStarter",a.activity,int.class,a.task),void.class);
            return access.installRecents(module,fromRecents,options,post,handles)?access:null;
        }catch(Throwable failure) {
            try{log.accept("MINI_LAUNCH_NATIVE_UNAVAILABLE "+failure.getClass().getSimpleName());}catch(Throwable ignored){}
            return null;
        }
    }
    boolean installRecents(XposedModule module,Method fromRecents,Method options,List<XposedInterface.HookHandle> handles) {
        return installRecents(module,fromRecents,options,null,handles);
    }
    boolean installRecents(XposedModule module,Method fromRecents,Method options,Method post,List<XposedInterface.HookHandle> handles) {
        List<XposedInterface.HookHandle> local=new ArrayList<>();
        try {
            // The verified supervisor is the direct native caller of getOptions(supervisor).
            if(!module.deoptimize(fromRecents)||!module.deoptimize(options))throw new IllegalStateException("recents deopt");
            hook(module,fromRecents,options,local);
            if(post!=null){
                if(!module.deoptimize(post))throw new IllegalStateException("recents completion deopt");
                local.add(module.hook(post).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    // Native has passed credential checks and moved the task, but still defers layout.
                    // Prepare the cover here, not after continueWindowLayout exposes the new geometry.
                    Recent scope=recent.get();
                    try{
                        Object activity=chain.getArg(0);
                        if(ready&&scope!=null&&!scope.completed&&scope.observed&&scope.allowed&&scope.task!=null
                            &&Integer.valueOf(2).equals(chain.getArg(1))&&activity!=null
                            &&a.call(activity,"getTask")==scope.task&&chain.getArg(2)==scope.task)
                            scope.completed=engine.launcherTaskStarted(scope.task,scope.uid);
                    }catch(Throwable failure){note("MINI_RECENTS_PREPLAYBACK_UNAVAILABLE");}
                    return result;
                }));
            }
            handles.addAll(local);ready=true;note("MINI_LAUNCH_NATIVE_READY");return true;
        }catch(Throwable failure) {
            ready=false;
            for(XposedInterface.HookHandle handle:local)try{handle.unhook();}catch(Throwable ignored){}
            note("MINI_LAUNCH_NATIVE_UNAVAILABLE "+failure.getClass().getSimpleName());return false;
        }
    }
    void beforeStartResult(Object starter,Object request,Object started,ActivityOptions options,int code,int realUid)throws Exception {
        try {
            if(!ready||request==null||started==null||(code!=0&&code!=2&&code!=3)
                    ||!doResume.getBoolean(starter)||(Boolean)avoidFront.invoke(starter))return;
            Object verdict=balVerdict.get(starter);
            if(verdict==null||!(Boolean)balAllows.invoke(verdict)||!optionsAllowed(a,options,-1))return;
            Object task=launcherTask(a,request,started,realUid);
            if(task!=null)engine.launcherTaskStarted(task,realUid);
        }catch(Throwable failure){note("MINI_LAUNCH_OBSERVE_UNAVAILABLE");}
    }
    static Object launcherTask(FrameworkAccess a,Object request,Object started,int realUid)throws Exception {
        Intent intent=(Intent)a.field(request,"intent");
        if(intent==null||!Intent.ACTION_MAIN.equals(intent.getAction())||!intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                ||intent.hasCategory(Intent.CATEGORY_HOME)||intent.getData()!=null||intent.getType()!=null
                ||(intent.getFlags()&(Intent.FLAG_ACTIVITY_CLEAR_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    |Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))!=0
                ||(Integer)a.field(request,"launchedFromUid")!=realUid
                ||!LauncherTargets.isLauncher((String)a.field(request,"launchedFromPackage")))return null;
        String pkg=(String)a.field(request,"packageName");
        if(pkg==null||a.integer(request,"getUid")!=a.integer(started,"getUid")
                ||!pkg.equals(a.field(started,"packageName")))return null;
        Object task=a.call(started,"getTask");
        if(task==null)return null;
        Object requestTask=a.call(request,"getTask"),display=a.call(task,"getDisplayContent");
        // A reused native request can be unattached. A different attached request task is never a match.
        if(requestTask!=null&&requestTask!=task)return null;
        Object requestDisplay=a.call(request,"getDisplayContent"),top=a.call(task,"topRunningActivity");
        if(display==null||a.call(started,"getDisplayContent")!=display||requestDisplay!=null&&requestDisplay!=display
                ||!a.field(task,"mUserId").equals(a.field(request,"mUserId"))
                ||!a.field(task,"mUserId").equals(a.field(started,"mUserId"))||top==null
                ||a.integer(request,"getUid")!=a.integer(top,"getUid")
                ||!a.field(request,"packageName").equals(a.field(top,"packageName")))return null;
        return task;
    }
    static boolean optionsAllowed(FrameworkAccess a,Object options,int recentTaskId)throws Exception {
        if(options==null)return true;
        int mode=a.integer(options,"getLaunchWindowingMode"),type=a.integer(options,"getLaunchActivityType");
        int id=a.integer(options,"getLaunchTaskId");
        if((mode!=0&&mode!=1)||(type!=0&&type!=1)||a.call(options,"getLaunchBounds")!=null
                ||(id!=-1&&(recentTaskId<0||id!=recentTaskId)))return false;
        for(String name:new String[]{"getLaunchDisplayId","getCallerDisplayId"}) {
            int display=a.integer(options,name);
            if(display!=-1&&display!=0)return false;
        }
        if(a.integer(options,"getLaunchTaskDisplayAreaFeatureId")!=-1)return false;
        for(String name:new String[]{"getAvoidMoveToFront","getTaskOverlay","getLaunchTaskBehind","getTransientLaunch",
                "getLaunchNextToBubble","getTaskAlwaysOnTop"})if(a.bool(options,name))return false;
        for(String name:new String[]{"getLaunchRootTask","getLaunchTaskFragmentToken","getLaunchTaskDisplayArea"})
            if(a.call(options,name)!=null)return false;
        return true;
    }
    static final class Recent {
        final Object supervisor,safe,task;
        final int uid,taskId;
        boolean observed,allowed=true,completed;
        Recent(Object supervisor,Object safe,Object task,int uid,int taskId) {
            this.supervisor=supervisor;this.safe=safe;this.task=task;this.uid=uid;this.taskId=taskId;
            observed=safe==null;
        }
    }
    void hook(XposedModule module,Method fromRecents,Method options,List<XposedInterface.HookHandle> handles)throws Throwable {
        handles.add(module.hook(options).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object result=chain.proceed(); // Preserve even a native null, result identity and original Throwable.
            Recent scope=recent.get();
            try {
                if(scope!=null&&scope.safe==chain.getThisObject()&&scope.supervisor==chain.getArg(0)) {
                    scope.observed=true;
                    scope.allowed&=optionsAllowed(a,(ActivityOptions)result,scope.taskId);
                }
            }catch(Throwable failure){if(scope!=null)scope.allowed=false;note("MINI_RECENTS_OPTIONS_UNAVAILABLE");}
            return result;
        }));
        handles.add(module.hook(fromRecents).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Recent previous=recent.get(),scope=null;
            try {
                recent.remove();
                try {
                    int id=(Integer)chain.getArg(2),uid=(Integer)chain.getArg(1);
                    Object task=engine.launcherTaskCandidate(id);
                    scope=new Recent(chain.getThisObject(),chain.getArg(3),task,uid,id);
                    recent.set(scope);
                }catch(Throwable failure){note("MINI_RECENTS_CANDIDATE_UNAVAILABLE");}
                Object result=chain.proceed(); // Native exceptions bypass observation and propagate unchanged.
                if(ready&&scope!=null&&!scope.completed&&scope.task!=null&&scope.observed&&scope.allowed&&Integer.valueOf(2).equals(result))try {
                    engine.launcherTaskStarted(scope.task,scope.uid);
                }catch(Throwable failure){note("MINI_RECENTS_RESTORE_UNAVAILABLE");}
                return result;
            }finally{if(previous==null)recent.remove();else recent.set(previous);}
        }));
    }
    private void note(String message){try{log.accept(message);}catch(Throwable ignored){}}
    private static Method instance(Method method,Class<?> result)throws NoSuchMethodException {
        if(method.getReturnType()!=result||Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException(method.getName());
        return method;
    }
}
