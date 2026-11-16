package dev.local.sunwindowdemo;

import java.lang.reflect.*;
import java.util.Arrays;

/** Exact server hook layouts shared by both ABI variants; never selects by ROM name. */
final class WindowServerTargets {
    static Method taskResize(Class<?> task)throws Exception{
        Method method;
        try{method=task.getDeclaredMethod("isResizeable",boolean.class);}
        catch(NoSuchMethodException absent){method=task.getDeclaredMethod("isResizeable");}
        return instance(method,boolean.class);
    }
    static Method startResult(ClassLoader loader,Class<?> starter,Class<?> activity,Class<?> transition,Class<?> task)throws Exception{
        Class<?>[] parameters={activity,android.app.ActivityOptions.class,int.class,boolean.class,
            Class.forName("android.window.RemoteTransition",false,loader),transition,int.class};
        Method method;
        try{method=starter.getDeclaredMethod("handleStartResult",parameters);}
        catch(NoSuchMethodException absent){
            parameters=Arrays.copyOf(parameters,8);
            parameters[7]=Class.forName("com.android.server.wm.ActivityCallerState$CallerInfo",false,loader);
            method=starter.getDeclaredMethod("handleStartResult",parameters);
        }
        return instance(method,task);
    }
    record StartUnchecked(Method method,int task,int flags,int count) {}
    static StartUnchecked startUnchecked(ClassLoader loader,Class<?> starter)throws Exception{
        String[] names={"com.android.server.wm.ActivityRecord","com.android.server.wm.ActivityRecord",
            "android.service.voice.IVoiceInteractionSession","com.android.internal.app.IVoiceInteractor",null,
            "android.app.ActivityOptions","com.android.server.wm.Task","com.android.server.wm.TaskFragment",
            "com.android.server.wm.BackgroundActivityStartController$BalVerdict","com.android.server.uri.NeededUriGrants",null,
            "com.android.server.wm.Transition",null,null};
        Class<?>[] parameters=new Class<?>[names.length];
        for(int i=0;i<names.length;i++)parameters[i]=names[i]==null?(i==12?boolean.class:int.class):Class.forName(names[i],false,loader);
        Method method;int task=6;
        try{method=starter.getDeclaredMethod("startActivityUnchecked",parameters);}
        catch(NoSuchMethodException absent){
            Class<?>[] next=new Class<?>[16];System.arraycopy(parameters,0,next,0,6);
            next[6]=Class.forName("com.android.server.wm.TaskDisplayArea",false,loader);
            System.arraycopy(parameters,6,next,7,8);
            next[15]=Class.forName("com.android.server.wm.ActivityCallerState$CallerInfo",false,loader);
            parameters=next;task=7;method=starter.getDeclaredMethod("startActivityUnchecked",parameters);
        }
        return new StartUnchecked(instance(method,int.class),task,task+4,parameters.length);
    }
    private static Method instance(Method method,Class<?> result)throws Exception{
        if(method.getReturnType()!=result||Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException(method.getName()+" signature");
        method.setAccessible(true);return method;
    }
}
