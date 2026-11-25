package dev.local.sunwindowdemo;

import android.service.notification.StatusBarNotification;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Replaces only native heads-up presentation; never removes or mutates a notification. */
final class BarrageHeadsUp {
    private static final String BASE="com.android.systemui.statusbar.notification.";
    record Targets(Method logged,Method unlogged,Field notification,Field interrupts,Constructor<?> decision,List<Method> callers){}
    static Targets resolve(ClassLoader loader)throws Exception{
        Class<?> provider=Class.forName(BASE+"interruption.VisualInterruptionDecisionProviderImpl",false,loader);
        Class<?> decision=Class.forName(provider.getName()+"$DecisionImpl",false,loader);
        Class<?> entry=Class.forName(BASE+"collection.NotificationEntry",false,loader);
        Method logged=method(provider,"makeAndLogHeadsUpDecision",decision,entry);
        Method unlogged=method(provider,"makeUnloggedHeadsUpDecision",decision,entry);
        Field notification=field(entry,"mSbn",StatusBarNotification.class),interrupts=field(decision,"shouldInterrupt",boolean.class);
        Constructor<?> constructor=decision.getDeclaredConstructor(String.class,boolean.class);constructor.setAccessible(true);
        Class<?> listener=Class.forName(BASE+"collection.coordinator.HeadsUpCoordinator$mNotifCollectionListener$1",false,loader);
        Class<?> update=Class.forName(BASE+"collection.notifcollection.UpdateSource",false,loader);
        List<Method> callers=List.of(method(listener,"onEntryAdded",void.class,entry),
            method(listener,"onEntryUpdated",void.class,entry,update),method(listener,"onRankingApplied",void.class));
        return new Targets(logged,unlogged,notification,interrupts,constructor,callers);
    }
    private static Method method(Class<?> owner,String name,Class<?> result,Class<?>... args)throws Exception{
        Method method=owner.getDeclaredMethod(name,args);
        if(method.getReturnType()!=result||Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException(name);
        method.setAccessible(true);return method;
    }
    private static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field field=owner.getDeclaredField(name);
        if(field.getType()!=type||Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException(name);
        field.setAccessible(true);return field;
    }
    static Object replace(Targets t,Object entry,Object nativeDecision,Predicate<StatusBarNotification> accept)throws Exception{
        if(nativeDecision==null||!t.interrupts().getBoolean(nativeDecision))return nativeDecision;
        StatusBarNotification sbn=(StatusBarNotification)t.notification().get(entry);
        // Native FSI evaluation also queries the unlogged HUN decision. Every real FSI stays native;
        // without fullScreenIntent the native FSI provider returns NO_FULL_SCREEN_INTENT first.
        if(sbn==null||sbn.getNotification().fullScreenIntent!=null)return nativeDecision;
        Object replacement=t.decision().newInstance("barrage",false);
        return accept.test(sbn)?replacement:nativeDecision; // Never mutate a possibly shared native decision.
    }
    static void install(XposedInterface module,ClassLoader loader,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Targets targets=resolve(loader);
            for(Method caller:targets.callers())if(!module.deoptimize(caller))throw new IllegalStateException("heads-up caller deoptimize");
            for(Method decision:List.of(targets.logged(),targets.unlogged())){
                hooks.add(module.hook(decision).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    try{return replace(targets,chain.getArg(0),result,sbn->{
                        SystemUiHost host=SystemUiHost.live;
                        return host!=null&&host.suppressBarrageHeadsUp(sbn);
                    });}catch(Exception failure){log.accept("BARRAGE_HEADSUP_FAILED "+failure.getClass().getSimpleName());return result;}
                }));
            }
            log.accept("BARRAGE_HEADSUP_READY");
        }catch(Throwable failure){
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("BARRAGE_HEADSUP_UNAVAILABLE "+failure.getClass().getSimpleName());
        }
    }
}
