package dev.local.sunwindowdemo;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Observes the native Quickstep gesture without consuming input or changing its destination. */
final class NativeSwipe {
    static final String ACTION=Wire.PKG+".NATIVE_WINDOW_SWIPE";
    static final int START=1, CANCEL=2, HOME=3, RECENTS=4;

    private record Sequence(Context context,long id,int task){}
    private final Map<Object,Sequence> gestures=new WeakHashMap<>();
    private final Field state,context;
    private final Method runningTasks,endTarget;

    private NativeSwipe(Field state,Field context,Method runningTasks,Method endTarget){
        this.state=state;this.context=context;this.runningTasks=runningTasks;this.endTarget=endTarget;
    }

    static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        var hooks=new ArrayList<XposedInterface.HookHandle>();
        try{
            Class<?> handler=Class.forName("com.android.quickstep.AbsSwipeUpHandler",false,loader);
            Class<?> logic=Class.forName("com.android.quickstep.SwipeUpAnimationLogic",false,loader);
            Class<?> state=Class.forName("com.android.quickstep.GestureState",false,loader);
            Class<?> target=Class.forName("com.android.quickstep.GestureState$GestureEndTarget",false,loader);
            if(!logic.isAssignableFrom(handler)||!target.isEnum())throw new NoSuchMethodException("swipe handler hierarchy");
            NativeSwipe swipe=new NativeSwipe(SwipeMini.field(logic,"mGestureState",state),
                SwipeMini.field(logic,"mContext",Context.class),
                SwipeMini.exact(state.getDeclaredMethod("getRunningTaskIds",boolean.class),int[].class),
                SwipeMini.exact(state.getDeclaredMethod("getEndTarget"),target));
            Method start=SwipeMini.exact(handler.getDeclaredMethod("onGestureStarted",boolean.class),void.class);
            Method settle=SwipeMini.exact(handler.getDeclaredMethod("onSettledOnEndTarget"),void.class);
            Method[] cancellations={
                SwipeMini.exact(handler.getDeclaredMethod("onGestureCancelled"),void.class),
                SwipeMini.exact(handler.getDeclaredMethod("onRecentsAnimationCanceled",java.util.HashMap.class),void.class),
                SwipeMini.exact(handler.getDeclaredMethod("invalidateHandler"),void.class),
                SwipeMini.exact(handler.getDeclaredMethod("invalidateHandlerWithLauncher"),void.class)
            };
            hooks.add(module.hook(start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                swipe.start(chain.getThisObject());
                return chain.proceed();
            }));
            hooks.add(module.hook(settle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                swipe.finish(chain.getThisObject(),true);
                return chain.proceed();
            }));
            for(Method cancel:cancellations)
                hooks.add(module.hook(cancel).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    swipe.finish(chain.getThisObject(),false);
                    return chain.proceed();
                }));
            log.accept("WINDOW_NATIVE_SWIPE_READY");
        }catch(Exception|LinkageError failure){
            for(var hook:hooks)try{hook.unhook();}catch(RuntimeException ignored){}
            log.accept("WINDOW_NATIVE_SWIPE_UNAVAILABLE "+failure.getClass().getSimpleName());
        }
    }

    private synchronized void start(Object handler){
        if(gestures.containsKey(handler))return;
        try{
            int[] tasks=(int[])runningTasks.invoke(state.get(handler),false);
            if(tasks==null||tasks.length!=1||tasks[0]<=0)return;
            Sequence sequence=new Sequence((Context)context.get(handler),SystemClock.elapsedRealtimeNanos(),tasks[0]);
            send(sequence,START);
            gestures.put(handler,sequence);
        }catch(Exception|LinkageError ignored){}
    }

    private synchronized void finish(Object handler,boolean settled){
        Sequence sequence=gestures.remove(handler);
        if(sequence==null)return;
        int phase=CANCEL;
        if(settled)try{
            Object destination=endTarget.invoke(state.get(handler));
            if(destination instanceof Enum<?> target)phase=switch(target.name()){
                case "HOME" -> HOME;
                case "RECENTS" -> RECENTS;
                default -> CANCEL;
            };
        }catch(Exception|LinkageError ignored){}
        try{send(sequence,phase);}catch(RuntimeException|LinkageError ignored){}
    }

    private static void send(Sequence sequence,int phase){
        Intent event=new Intent(ACTION).setPackage("android").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("protocol",Wire.PROTOCOL).putExtra("gesture",sequence.id()).putExtra("taskId",sequence.task())
            .putExtra("phase",phase);
        sequence.context().sendBroadcast(event,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
