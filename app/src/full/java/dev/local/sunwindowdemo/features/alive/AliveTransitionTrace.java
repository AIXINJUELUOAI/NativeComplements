package dev.local.sunwindowdemo.features.alive;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Exact a4bf SystemUI observation only. Never substitutes native arguments, results or progress. */
public final class AliveTransitionTrace {
    private AliveTransitionTrace() {}

    public static void install(XposedModule module,ClassLoader loader,Supplier<String> episode,Supplier<String> epoch,
            Predicate<View> sameRoot,Consumer<String> log) {
        List<Group> groups=new ArrayList<>();
        Group clock=new Group("clock",module,episode,log);groups.add(clock);
        try {
            Class<?> owner=type(loader,"com.android.keyguard.ClockEventController");
            Field preview=field(owner,"isPreview",boolean.class);
            Field clockFrame=field(owner,"smallClockFrame",android.view.ViewGroup.class);
            Method doze=method(owner,"access$handleDoze",owner,float.class);
            if(!java.lang.reflect.Modifier.isStatic(doze.getModifiers()))throw new NoSuchMethodException("static handleDoze");
            clock.hook(doze,(ignored,args)->{
                Object controller=args[0];
                View frame=(View)clockFrame.get(controller);
                if(!preview.getBoolean(controller)&&frame!=null&&frame.isAttachedToWindow()&&sameRoot.test(frame))
                    clock.value(controller,"doze",((Number)args[1]).floatValue());
            });
            clock.ready();
        } catch(Throwable failure) { clock.fail(failure); }

        Group notifications=new Group("notifications",module,episode,log);groups.add(notifications);
        try {
            Class<?> owner=type(loader,"com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController");
            Field view=owner.getDeclaredField("mView");view.setAccessible(true);
            if(!View.class.isAssignableFrom(view.getType()))throw new NoSuchFieldException("notification mView");
            notifications.hook(method(owner,"setMaxAlphaForKeyguard",String.class,float.class),(controller,args)->{
                View target=(View)view.get(controller);
                if(target!=null&&sameRoot.test(target)) {
                    // Do not read the source string or notification contents.
                    notifications.value(controller,"keyguardAlpha",((Number)args[1]).floatValue());
                }
            });
            notifications.hook(method(owner,"updateAlpha"),(controller,args)->{
                View target=(View)view.get(controller);
                if(target!=null&&sameRoot.test(target))notifications.value(controller,"effectiveAlpha",target.getAlpha());
            });
            notifications.ready();
        } catch(Throwable failure) { notifications.fail(failure); }

        Group scrim=new Group("scrim",module,episode,log);groups.add(scrim);
        try {
            Class<?> owner=type(loader,"com.android.systemui.statusbar.LightRevealScrim");
            Class<?> effect=type(loader,"com.android.systemui.statusbar.LightRevealEffect");
            Field reveal=field(owner,"revealAmount",float.class),effectField=field(owner,"revealEffect",effect);
            Probe snapshot=(target,args)->{
                View view=(View)target;
                if(!sameRoot.test(view))return;
                Object currentEffect=effectField.get(target);
                scrim.effect(target,currentEffect==null?"null":currentEffect.getClass().getSimpleName());
                scrim.value(target,"reveal",reveal.getFloat(target));
                scrim.value(target,"alpha",view.getAlpha());
            };
            scrim.hook(method(owner,"setRevealEffect",effect),snapshot);
            scrim.hook(method(owner,"setRevealAmount",float.class),snapshot);
            scrim.hook(method(owner,"setAlpha",float.class),snapshot);
            scrim.ready();
        } catch(Throwable failure) { scrim.fail(failure); }

        Group transition=new Group("transition",module,episode,log);
        try {
            Class<?> collector=type(loader,"com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor$1$1");
            Class<?> step=type(loader,"com.android.systemui.keyguard.shared.model.TransitionStep");
            Class<?> state=type(loader,"com.android.systemui.keyguard.shared.model.KeyguardState");
            Class<?> status=type(loader,"com.android.systemui.keyguard.shared.model.TransitionState");
            Field id=field(collector,"$r8$classId",int.class);
            Field from=field(step,"from",state),to=field(step,"to",state),value=field(step,"value",float.class);
            Field phase=field(step,"transitionState",status);
            Handler main=new Handler(Looper.getMainLooper());
            Method emit=method(collector,"emit",Object.class,type(loader,"kotlin.coroutines.Continuation"));
            if(!module.deoptimize(emit))throw new IllegalStateException("transition deoptimize");
            transition.hooks.add(module.hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                if(transition.enabled)try {
                    Object target=chain.getThisObject(),current=chain.getArg(0);
                    if(id.getInt(target)!=0||!step.isInstance(current))return result;
                    String nativePhase=((Enum<?>)phase.get(current)).name();
                    if(!nativePhase.equals("STARTED")&&!nativePhase.equals("FINISHED")&&!nativePhase.equals("CANCELED"))return result;
                    String captured=epoch.get();
                    if(captured==null)return result;
                    String route=((Enum<?>)from.get(current)).name()+"->"+((Enum<?>)to.get(current)).name();
                    float progress=value.getFloat(current);
                    long at=SystemClock.uptimeMillis();
                    // Retain only immutable values, never a host continuation, collector or TransitionStep.
                    main.post(()->{
                        if(!transition.enabled)return;
                        try {
                            if(!currentSnapshot(captured,episode.get(),SystemClock.uptimeMillis()-at)||!transition.selected())return;
                            if(transition.transition(route+" "+nativePhase,progress)&&!nativePhase.equals("STARTED"))
                                for(Group group:groups)group.summary();
                        }catch(Throwable failure){transition.fail(failure);}
                    });
                }catch(Throwable failure){main.post(()->transition.fail(failure));}
                return result;
            }));
            transition.ready();
        } catch(Throwable failure) { transition.fail(failure); }
    }

    private interface Probe { void inspect(Object owner,Object[] args)throws Throwable; }
    private static final class Group {
        final String name;
        final XposedModule module;
        final Supplier<String> episode;
        final Consumer<String> log;
        final List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        final WeakHashMap<Object,Map<String,Edge>> values=new WeakHashMap<>();
        String current,lastTransition;
        int transitionCount,logCount;
        volatile boolean enabled;
        Group(String name,XposedModule module,Supplier<String> episode,Consumer<String> log) {
            this.name=name;this.module=module;this.episode=episode;this.log=log;
        }
        void hook(Method method,Probe probe)throws Throwable {
            if(!module.deoptimize(method))throw new IllegalStateException("deoptimize");
            hooks.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // Exactly one original invocation, outside diagnostic recovery: native exceptions propagate unchanged.
                Object result=chain.proceed();
                if(enabled&&Looper.myLooper()==Looper.getMainLooper())try {
                    if(selected())probe.inspect(chain.getThisObject(),chain.getArgs().toArray());
                }catch(Throwable failure){fail(failure);}
                return result;
            }));
        }
        void ready(){enabled=true;emit("ALIVE_TRACE_READY group="+name);}
        void fail(Throwable failure){
            enabled=false;
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            hooks.clear();values.clear();
            emit("ALIVE_TRACE_UNAVAILABLE group="+name+" reason="+failure.getClass().getSimpleName());
        }
        boolean selected(){
            String next=episode.get();
            if(next==null)return false;
            if(!next.equals(current)){current=next;values.clear();lastTransition=null;transitionCount=0;logCount=0;}
            return true;
        }
        Edge edge(Object owner,String key){
            Map<String,Edge> streams=values.get(owner);
            if(streams==null){
                if(values.size()>=8)return null; // Bounded identities; no growing registry of host controllers.
                streams=new HashMap<>();values.put(owner,streams);
            }
            return streams.computeIfAbsent(key,ignored->new Edge());
        }
        void value(Object owner,String key,float value){
            Edge edge=edge(owner,key);
            if(edge!=null&&edge.sample(value))emit("ALIVE_TRACE ep="+current+" group="+name+" id="
                    +Integer.toHexString(System.identityHashCode(owner))+" "+key+"="+value+" direction="+edge.direction);
        }
        void effect(Object owner,String effect){
            Edge edge=edge(owner,"effect");
            if(edge!=null&&edge.effect(effect))emit("ALIVE_TRACE ep="+current+" group="+name+" id="
                    +Integer.toHexString(System.identityHashCode(owner))+" effect="+effect);
        }
        boolean transition(String event,float value){
            if(event.equals(lastTransition)||transitionCount>=12)return false;
            lastTransition=event;transitionCount++;
            emit("ALIVE_TRACE ep="+current+" group=transition "+event+" value="+value);
            return true;
        }
        void summary(){
            if(!enabled)return;
            try {
                if(!selected())return;
                for(Map.Entry<Object,Map<String,Edge>> owner:values.entrySet())
                    for(Map.Entry<String,Edge> stream:owner.getValue().entrySet()) {
                        Edge edge=stream.getValue();
                        if(edge.summary())emit("ALIVE_TRACE ep="+current+" group="+name+" id="
                                +Integer.toHexString(System.identityHashCode(owner.getKey()))+" last-"+stream.getKey()+"="+edge.last
                                +" direction="+edge.direction);
                    }
            }catch(Throwable failure){fail(failure);}
        }
        void emit(String message){
            if(message.startsWith("ALIVE_TRACE ep=")&&logCount++>=40)return;
            try{log.accept(message);}catch(Throwable ignored){}
        }
    }

    static boolean currentSnapshot(String captured,String active,long age){
        return captured!=null&&captured.equals(active)&&age>=0&&age<=100;
    }
    /** Bounded first/direction/endpoint observation; intermediate animation frames only update last. */
    static final class Edge {
        float last=Float.NaN;
        int direction,count;
        String effect;
        boolean sampled,summarized;
        boolean sample(float value){
            if(!Float.isFinite(value))return false;
            int next=sampled?Float.compare(value,last):0;
            boolean changed=!sampled||value!=last;
            boolean report=!sampled||changed&&(value==0f||value==1f||next!=0&&next!=direction);
            if(next!=0)direction=next;
            last=value;sampled=true;
            if(!report||count>=8)return false;
            count++;return true;
        }
        boolean effect(String value){
            if(value.equals(effect)||count>=8)return false;
            effect=value;count++;return true;
        }
        boolean summary(){if(!sampled||summarized)return false;summarized=true;return true;}
    }
    private static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);
        if(field.getType()!=expected)throw new NoSuchFieldException(name);
        field.setAccessible(true);return field;
    }
    private static Method method(Class<?> owner,String name,Class<?>... args)throws ReflectiveOperationException {
        Method method=owner.getDeclaredMethod(name,args);method.setAccessible(true);return method;
    }
}
