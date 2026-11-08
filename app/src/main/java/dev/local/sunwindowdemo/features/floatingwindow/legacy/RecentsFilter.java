package dev.local.sunwindowdemo;

import android.content.Intent;
import android.os.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Filters a task before cards are bound and before a running-task stub can be inserted. */
final class RecentsFilter {
    private final Method plan,stub,contains,tasks,reload;
    private final Field applied;
    private final Consumer<String> log;
    private final Map<Object,Snapshot> views=new WeakHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Set<Integer> hidden=Set.of();
    private boolean replay;
    private record Snapshot(List<?> tasks,int changeId){}
    RecentsFilter(ClassLoader cl,Consumer<String> logger) throws Exception {
        log=logger;Class<?> rv=Class.forName("com.android.quickstep.views.RecentsView",false,cl);
        Class<?> group=Class.forName("com.android.quickstep.util.GroupTask",false,cl);
        Class<?> info=Class.forName("com.android.wm.shell.shared.GroupedTaskInfo",false,cl);
        plan=rv.getDeclaredMethod("applyLoadPlan",List.class,int.class);plan.setAccessible(true);
        stub=rv.getDeclaredMethod("shouldAvoidAddingStubTaskView",info);stub.setAccessible(true);
        contains=group.getDeclaredMethod("containsTask",int.class);tasks=group.getDeclaredMethod("getTasks");
        reload=rv.getDeclaredMethod("reloadIfNeeded");reload.setAccessible(true);
        applied=rv.getDeclaredField("mAppliedTaskListChangeId");applied.setAccessible(true);
        info.getDeclaredMethod("getTaskInfo1");info.getDeclaredMethod("isBaseType",int.class);
    }
    void install(XposedModule module){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            hooks.add(module.hook(plan).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                List<?> original=(List<?>)chain.getArg(0);
                if(!replay&&original!=null)views.put(chain.getThisObject(),new Snapshot(new ArrayList<>(original),(Integer)chain.getArg(1)));
                if(hidden.isEmpty()||original==null)return chain.proceed();
                List<Object> filtered=new ArrayList<>();
                for(Object group:original){
                    boolean own=false;
                    if(((List<?>)tasks.invoke(group)).size()==1)for(int id:hidden)if((Boolean)contains.invoke(group,id)){own=true;break;}
                    if(!own)filtered.add(group);
                }
                return chain.proceed(new Object[]{filtered,chain.getArg(1)});
            }));
            hooks.add(module.hook(stub).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object group=chain.getArg(0);
                if(!hidden.isEmpty()&&group!=null&&(Boolean)group.getClass().getMethod("isBaseType",int.class).invoke(group,1)){
                    Object task=group.getClass().getMethod("getTaskInfo1").invoke(group);
                    if(task!=null&&hidden.contains(task.getClass().getField("taskId").getInt(task)))return true;
                }
                return chain.proceed();
            }));
            log.accept("RECENTS_READY hooks=2");
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}throw e;}
    }
    void configure(Intent i){
        int[] ids=i.getIntArrayExtra("hiddenTaskIds");Set<Integer> next=new HashSet<>();
        if(ids!=null)for(int id:ids)if(id>=0)next.add(id);
        if(next.equals(hidden))return;hidden=Set.copyOf(next);
        main.post(()->{
            for(var entry:new ArrayList<>(views.entrySet()))try{
                Object view=entry.getKey();Snapshot s=entry.getValue();
                replay=true;try{plan.invoke(view,s.tasks(),s.changeId());}finally{replay=false;}
                applied.setInt(view,-1);reload.invoke(view);
            }catch(Exception e){log.accept("RECENTS_REFRESH_FAILED "+e.getClass().getSimpleName());}
            log.accept("RECENTS_FILTER task="+hidden);
        });
    }
}
