package dev.local.sunwindowdemo;

import android.graphics.Rect;
import io.github.libxposed.api.*;
import java.util.*;

public final class SunWindowModule extends XposedModule {
    private boolean eligible, attempted;
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        eligible = p.isSystemServer();
        note("MODULE_LOADED process=" + p.getProcessName() + " systemServer=" + eligible + " version=0.4.0-preview1");
    }
    private void installUndimSuggestion(ClassLoader loader){
        try{
            Class<?> power=Class.forName("com.android.server.power.PowerManagerService",false,loader);
            Class<?> group=Class.forName("com.android.server.power.PowerGroup",false,loader);
            java.lang.reflect.Method event=power.getDeclaredMethod("userActivityNoUpdateLocked",group,long.class,int.class,int.class,int.class);
            java.lang.reflect.Method dim=group.getDeclaredMethod("isPolicyDimLocked"),id=group.getDeclaredMethod("getGroupId"),wake=group.getDeclaredMethod("getWakefulnessLocked");
            java.lang.reflect.Field context=power.getDeclaredField("mContext");
            int touch=android.os.PowerManager.class.getDeclaredField("USER_ACTIVITY_EVENT_TOUCH").getInt(null);
            int noLights=android.os.PowerManager.class.getDeclaredField("USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS").getInt(null);
            if(event.getReturnType()!=boolean.class||dim.getReturnType()!=boolean.class||id.getReturnType()!=int.class
                ||wake.getReturnType()!=int.class||context.getType()!=android.content.Context.class)throw new NoSuchMethodException("undim signature");
            event.setAccessible(true);dim.setAccessible(true);id.setAccessible(true);wake.setAccessible(true);context.setAccessible(true);
            android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
            hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean offer=false;long time=(Long)chain.getArg(1);
                try{Object g=chain.getArg(0);offer=(Integer)chain.getArg(2)==touch&&((Integer)chain.getArg(3)&noLights)==0
                    &&(Integer)id.invoke(g)==0&&(Integer)wake.invoke(g)==1&&(Boolean)dim.invoke(g);}
                catch(Exception ignored){}
                Object result=chain.proceed();
                if(offer&&Boolean.TRUE.equals(result))try{
                    android.content.Context c=(android.content.Context)context.get(chain.getThisObject());
                    main.post(()->{try{
                        long age=android.os.SystemClock.uptimeMillis()-time;
                        if(age<0||age>1500||!c.getSystemService(android.os.PowerManager.class).isInteractive()
                            ||c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())return;
                        c.sendBroadcast(new android.content.Intent(dev.local.sunwindowdemo.features.suggestions.SuggestionStore.AWAKE)
                            .setPackage(BackBridge.PACKAGE).putExtra("time",time),null,
                            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                    }catch(RuntimeException ignored){}});
                }catch(Exception ignored){}
                return result;
            });
            note("SUGGESTIONS_UNDIM_READY");
        }catch(Throwable unavailable){note("SUGGESTIONS_UNDIM_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam p) {
        note("SYSTEM_SERVER_START eligible=" + eligible + " attempted=" + attempted);
        if (!eligible || attempted) return;
        attempted = true;
        installUndimSuggestion(p.getClassLoader());
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            FrameworkAccess a = new FrameworkAccess(p.getClassLoader());
            WindowEngine engine = new WindowEngine(a, this::note);
            handles.add(hook(a.systemReady).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); note("WMS_READY_SEEN"); engine.attach(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.prepare).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.prepared(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.changed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.configurationChanged(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.freeform).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.owns(chain.getThisObject())) { engine.bounds(chain.getThisObject(), (Rect) chain.getArg(0)); return null; }
                return chain.proceed();
            }));
            handles.add(hook(a.recentVisible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if(engine.hideRecent(chain.getArg(0)))return false;
                return chain.proceed();
            }));
            handles.add(hook(a.systemBars).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if((Boolean)chain.getArg(3)&&engine.preserveImmersive(chain.getThisObject(),chain.getArg(0))){
                    return chain.proceed(new Object[]{chain.getArg(0),chain.getArg(1),chain.getArg(2),false});
                }
                return chain.proceed();
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBack",a.task)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.backToClose(chain.getThisObject()))return true;
                return chain.proceed();
            }));
            Class<?> window=Class.forName("com.android.server.wm.WindowState",false,p.getClassLoader());
            try{
                java.lang.reflect.Method drawn=a.method(window,"finishDrawing",android.view.SurfaceControl.Transaction.class,int.class);
                if(drawn.getReturnType()!=boolean.class)throw new NoSuchMethodException("finishDrawing(Transaction,int)");
                handles.add(hook(drawn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.drawingFinished(chain.getThisObject(),(Integer)chain.getArg(1));return result;
                }));
                note("TASK_COVER_DRAW_HOOK_READY");
            }catch(Exception e){note("TASK_COVER_DRAW_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            handles.add(hook(a.method(window,"canReceiveTouchInput")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.blockContentTouch(chain.getThisObject()))return false;
                return chain.proceed();
            }));
            // Mini tasks must stay nonfocusable for native Home/recents task
            // selection. Preflight the native fragment resume path separately;
            // window key eligibility is not task focus eligibility.
            try{
                java.lang.reflect.Method keys=a.method(window,"canReceiveKeys",boolean.class);
                if(keys.getReturnType()!=boolean.class)throw new NoSuchMethodException("canReceiveKeys(boolean)");
                handles.add(hook(keys).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.blockContentTouch(chain.getThisObject()))return false;
                    return chain.proceed();
                }));
                Class<?> visibleFragment=Class.forName("com.android.server.wm.TaskFragment",false,p.getClassLoader());
                java.lang.reflect.Method resume=a.method(visibleFragment,"resumeTopActivity",a.activity,android.app.ActivityOptions.class,boolean.class);
                if(resume.getReturnType()!=boolean.class)throw new NoSuchMethodException("resumeTopActivity(ActivityRecord,ActivityOptions,boolean)");
                engine.visibleResumeReady();note("VISIBLE_MULTI_RESUME_READY nonfocusableMini=true");
            }catch(Exception e){note("VISIBLE_MULTI_RESUME_UNAVAILABLE "+FrameworkAccess.reason(e));}
            // Input sinks are separate surfaces, not WindowStates. Alpha zero and
            // setFocusable(false) do not stop a parked task's sink intercepting touches.
            // Preserve native calculation first so reactivation restores native input.
            try{
                Class<?> sink=Class.forName("com.android.server.wm.ActivityRecordInputSink",false,p.getClassLoader());
                java.lang.reflect.Field record=a.field(sink,"mActivityRecord");
                java.lang.reflect.Method wrapper=a.method(sink,"getInputWindowHandleWrapper",boolean.class);
                java.lang.reflect.Method inputConfig=a.method(wrapper.getReturnType(),"setInputConfigMasked",int.class,int.class);
                handles.add(hook(wrapper).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    if(engine.blockContentTouch(record.get(chain.getThisObject())))
                        // NOT_TOUCHABLE=8, verified against the target framework's native sink.
                        inputConfig.invoke(result,8,8);
                    return result;
                }));
                note("PARKED_INPUT_SINK_HOOK_READY");
            }catch(Exception e){note("PARKED_INPUT_SINK_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            // SunOS WindowState.canAffectSystemUiFlags excludes popup windows. The
            // native frame here is logical (0,0), so it must not color the real bars.
            handles.add(hook(a.method(window,"canAffectSystemUiFlags")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.owns(a.call(chain.getThisObject(),"getTask")))return false;
                return chain.proceed();
            }));
            Class<?> transition=Class.forName("com.android.server.wm.Transition",false,p.getClassLoader());
            Class<?> fragment=Class.forName("com.android.server.wm.TaskFragment",false,p.getClassLoader());
            handles.add(hook(a.method(fragment,"canBeResumed",a.activity)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean nativeResult=(Boolean)chain.proceed();
                return nativeResult||engine.keepVisibleResumed(chain.getThisObject(),chain.getArg(0));
            }));
            // SunOS ActivityRecord uses isFocusableOrPopUpView in BOTH lifecycle
            // decisions. Keep task/input focus native, but admit only a live,
            // visible owned mini at these two lifecycle predicates.
            List<XposedInterface.HookHandle> lifecycleHooks=new ArrayList<>();
            try{
                java.lang.reflect.Method resume=a.method(a.activity,"shouldBeResumed",a.activity);
                java.lang.reflect.Method pause=a.method(a.activity,"shouldPauseActivity",a.activity);
                for(java.lang.reflect.Method check:new java.lang.reflect.Method[]{resume,pause,
                    a.method(a.activity,"shouldMakeActive",a.activity),a.method(a.activity,"canResumeByCompat")})
                    if(check.getReturnType()!=boolean.class)throw new NoSuchMethodException("mini lifecycle boolean predicate");
                a.method(a.activity,"getTaskFragment");
                lifecycleHooks.add(hook(resume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    boolean nativeResult=(Boolean)chain.proceed();
                    return nativeResult||engine.shouldResumeMiniActivity(chain.getThisObject(),chain.getArg(0));
                }));
                lifecycleHooks.add(hook(pause).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    boolean nativeResult=(Boolean)chain.proceed();
                    return nativeResult&&!engine.retainMiniActivity(chain.getThisObject(),chain.getArg(0));
                }));
                // The target ART may inline these small predicates into native
                // visibility/relaunch callers. Interpret those callers so the
                // paired hooks also apply to previously compiled framework code.
                for(java.lang.reflect.Method caller:new java.lang.reflect.Method[]{
                    a.method(a.activity,"makeActiveIfNeeded",a.activity),
                    a.method(a.activity,"shouldResumeActivity",a.activity),
                    a.method(a.activity,"relaunchActivityLocked",boolean.class,int.class)})
                    note("MINI_LIFECYCLE_DEOPT method="+caller.getName()+" result="+deoptimize(caller));
                handles.addAll(lifecycleHooks);note("MINI_LIFECYCLE_READY resumeAndPause=true taskFocus=false");
            }catch(Throwable e){
                for(XposedInterface.HookHandle handle:lifecycleHooks)try{handle.unhook();}catch(Throwable ignored){}
                note("MINI_LIFECYCLE_UNAVAILABLE "+FrameworkAccess.reason(e));
            }
            handles.add(hook(a.method(transition,"onTransactionReady",int.class,android.view.SurfaceControl.Transaction.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    engine.transitionReady(chain.getThisObject());return chain.proceed();
                }));
            handles.add(hook(a.method(transition,"buildFinishTransaction",android.view.SurfaceControl.Transaction.class,
                Class.forName("android.window.TransitionInfo",false,p.getClassLoader()),java.lang.reflect.Array.newInstance(a.dc,0).getClass()))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    engine.transitionBuilt(chain.getThisObject(),chain.getArg(1),(android.view.SurfaceControl.Transaction)chain.getArg(0));
                    return result;
                }));
            handles.add(hook(a.method(transition,"collect",a.wc)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.displayTransitionCollecting(chain.getThisObject(),chain.getArg(0));
                Object result=chain.proceed();engine.collected(chain.getThisObject(),chain.getArg(0));return result;
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBackInner",a.task,transition)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.beforeBackInner(chain.getThisObject(),chain.getArg(1));
                Object result=chain.proceed();engine.afterBackInner(chain.getThisObject());return result;
            }));
            handles.add(hook(a.requestTransition).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.requesting(chain.getArg(0),chain.getArg(1));return chain.proceed();
            }));
            // The target framework asks this before creating predictive-back leashes.
            // Suppress only an owned root's remote preview; keep internal app callbacks.
            // This is optional so a missing preview API cannot disable window management.
            try{
                Class<?> adapter=Class.forName("android.window.BackAnimationAdapter",false,p.getClassLoader());
                handles.add(hook(a.method(adapter,"isAnimatable",int.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        if(engine.suppressLinkedBackPreview())return false;
                        return chain.proceed();
                    }));
            }catch(Exception e){note("LINKED_BACK_PREVIEW_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            LinkedBack.install(this,a,engine,handles,this::note);
            // SunOS forces resizability at the system level. Scope that behavior
            // to acquired tasks and the current preflight candidate; keep app
            // manifests and the system-wide developer setting untouched.
            List<XposedInterface.HookHandle> resizeHooks=new ArrayList<>();
            try{
                java.lang.reflect.Method taskResize=a.method(a.task,"isResizeable",boolean.class);
                java.lang.reflect.Method activityResize=a.method(a.activity,"isResizeable");
                if(taskResize.getReturnType()!=boolean.class||activityResize.getReturnType()!=boolean.class)
                    throw new NoSuchMethodException("scoped resize signatures");
                resizeHooks.add(hook(taskResize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.allowResize(chain.getThisObject()))return true;
                    return chain.proceed();
                }));
                resizeHooks.add(hook(activityResize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.allowResize(a.call(chain.getThisObject(),"getTask")))return true;
                    return chain.proceed();
                }));
                handles.addAll(resizeHooks);note("SCOPED_RESIZE_HOOK_READY hooks="+resizeHooks.size());
            }catch(Throwable e){
                for(XposedInterface.HookHandle handle:resizeHooks)try{handle.unhook();}catch(Throwable ignored){}
                note("SCOPED_RESIZE_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));
            }
            // Early display geometry changes must be hidden before native rotation snapshots.
            // Optional: a different framework disables only this extra observation point.
            try{
                handles.add(hook(a.method(a.dc,"onRequestedOverrideConfigurationChanged",android.content.res.Configuration.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        engine.displayConfigurationChanging(chain.getThisObject(),(android.content.res.Configuration)chain.getArg(0));
                        return chain.proceed();
                    }));
                note("ROTATION_VISIBILITY_HOOK_READY");
            }catch(Exception e){note("ROTATION_VISIBILITY_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            ServerViewport.install(this,a,engine,handles);
            // Diagnostics are optional: missing diagnostic callbacks must not disable windows.
            try{
                handles.add(hook(a.method(a.activity,"finishRelaunching")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.trace.state("SERVER_FINISH_RELAUNCH",chain.getThisObject());return result;
                }));
                handles.add(hook(a.method(a.activity,"activityResumedLocked",android.os.IBinder.class,boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.trace.resumed((android.os.IBinder)chain.getArg(0));return result;
                }));
            }catch(Exception e){note("ENTRY_LAYOUT_CALLBACK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            // Target Android 17 signature, checked against the connected device's
            // services.jar. Configure related tasks before handleStartResult requests Shell playback; preserve flags.
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,p.getClassLoader());
            java.lang.reflect.Field sourceRecord=a.field(starter,"mSourceRecord");
            java.lang.reflect.Field lastStarted=a.field(starter,"mLastStartActivityRecord");
            java.lang.reflect.Field realCallingUid=a.field(starter,"mRealCallingUid");
            java.lang.reflect.Field activityIntent=a.field(a.activity,"intent");
            handles.add(hook(a.method(starter,"handleStartResult",a.activity,android.app.ActivityOptions.class,
                int.class,boolean.class,Class.forName("android.window.RemoteTransition"),transition,int.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object ticket=null;
                    int code=(Integer)chain.getArg(2);
                    if(code>=0&&code<=3){
                        try{
                            android.content.Intent intent=(android.content.Intent)activityIntent.get(chain.getArg(0));
                            ticket=engine.activityStarting(sourceRecord.get(chain.getThisObject()),chain.getArg(0),lastStarted.get(chain.getThisObject()),intent==null?0:intent.getFlags(),realCallingUid.getInt(chain.getThisObject()));
                        }catch(Exception e){note("HANDOFF_OBSERVE_FAILED "+FrameworkAccess.reason(e));}
                    }
                    Object shortcutTicket=code>=0&&code<=3?engine.shortcutLaunchTicket():null;
                    boolean success=false;
                    try{
                        Object result=chain.proceed();success=result!=null;
                        if(success&&shortcutTicket!=null)try{
                            Object started=lastStarted.get(chain.getThisObject());
                            // Reused singleTask starts can return an existing task while
                            // the incoming request record itself was never attached.
                            if((started==null||a.call(started,"getTask")==null)&&a.task.isInstance(result))
                                started=a.call(result,"topRunningActivity");
                            engine.shortcutActivityStarted(shortcutTicket,started);
                        }catch(Exception e){note("SHORTCUT_OBSERVE_FAILED "+FrameworkAccess.reason(e));}
                        return result;
                    }finally{engine.activityStartResult(ticket,success);}
                }));
            note("HOOKS_READY count="+handles.size()+" nativecomplements-0.3.0-preview6; system scopes only");
        } catch (Throwable e) {
            for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (Throwable ignored) { }
            note("DISABLED signature-preflight " + FrameworkAccess.reason(e));
        }
    }
    private void note(String message) {
        try { android.util.Log.i("SunWindowDemo5", message); } catch (Throwable ignored) { }
        try { log(4, "SunWindowDemo5", message); } catch (Throwable ignored) { }
    }
}
