package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Extends an already accepted native back-panel stream. Native cancellation owns cleanup. */
public final class BackBridge extends XposedModule {
    static final String PACKAGE="com.android.systemui";
    static final String CHANNEL=Wire.PKG+".backLong.v1";
    private boolean eligible,attempted;
    static volatile boolean cornerReady;
    private volatile boolean enabled;
    private volatile boolean preserveBars;
    private boolean screenshotShown;
    private final Set<View> screenshotViews=Collections.newSetFromMap(new WeakHashMap<>());
    private volatile int generation;
    private Context context;
    private volatile IBinder sink,lease;
    private final Map<Object,Stroke> strokes=new WeakHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Field viewField,callbackField;
    private Method cancelBack;
    private volatile Stroke settling;
    private Stroke cancelling;
    private static final class Stroke {
        final LongBackGesture gesture=new LongBackGesture();
        BackHandoffState handoff; // Created only after prepareBack succeeds.
        int task;String pkg,token;Object controller,handler;
    }
    @Override public void onModuleLoaded(ModuleLoadedParam p){eligible=!p.isSystemServer()&&PACKAGE.equals(p.getProcessName());}
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||attempted||!PACKAGE.equals(p.getPackageName()))return;attempted=true;
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate",Application.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();Application app=(Application)chain.getArg(0);
                    if(PACKAGE.equals(app.getPackageName()))main.post(()->{
                        ProductFeatures.connectSystemUi(app,this::note);
                        try{connect(app);}catch(Exception e){note("HOST_FAILED "+e);}
                    });
                    return result;
                });
        }catch(Exception e){note("HOST_BOOT_HOOK_FAILED "+e);}
        ProductFeatures.installSystemUi(this,p.getDefaultClassLoader(),this::note);
        // The native monitor also owns corner admission, not only the optional edge blocker.
        installEdgeTouchAdmission(p.getDefaultClassLoader());
        ProductFeatures.installNotifications(this,p.getDefaultClassLoader(),this::note);
        WindowTools.install(this,p.getDefaultClassLoader(),this::note);
        NotificationWindow.install(this,p.getDefaultClassLoader(),this::note);
        installBackFinish(p.getDefaultClassLoader());
        try{
            ClassLoader cl=p.getDefaultClassLoader();
            Class<?> panel=Class.forName("com.android.systemui.navigationbar.gestural.BackPanelController",false,cl);
            viewField=field(panel,"mView");callbackField=field(panel,"backCallback");
            cancelBack=Class.forName("com.android.systemui.plugins.NavigationEdgeBackPlugin$BackCallback",false,cl).getDeclaredMethod("cancelBack");
            Method motion=panel.getDeclaredMethod("onMotionEvent",MotionEvent.class);
            Class<?> handlerClass=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",false,cl);
            Method dispatch=handlerClass.getDeclaredMethod("dispatchToBackAnimation",MotionEvent.class);
            Field allow=field(handlerClass,"mAllowGesture"),logging=field(handlerClass,"mLogGesture"),rejected=field(handlerClass,"mInRejectedExclusion");
            if(dispatch.getReturnType()!=void.class||allow.getType()!=boolean.class||logging.getType()!=boolean.class||rejected.getType()!=boolean.class)
                throw new NoSuchMethodException("native Back cancellation signatures");
            Class<?> input=field(Class.forName("com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",false,cl),"onInputEvent").getType();
            if(input.isInterface())input=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler$$ExternalSyntheticLambda20",false,cl);
            // This native caller can inline dispatchToBackAnimation in an AOT SystemUI build.
            if(!deoptimize(input.getDeclaredMethod("invoke",Object.class)))throw new IllegalStateException("native Back dispatcher deoptimize");
            hooks.add(hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                MotionEvent event=(MotionEvent)chain.getArg(0);Stroke s=cancelling;
                if(s==null||!cancelNativeUp(s.handler,s.gesture.down(),chain.getThisObject(),event))return chain.proceed();
                cancelling=null;
                MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
                try{return chain.proceed(new Object[]{cancel});}
                finally{cancel.recycle();allow.setBoolean(s.handler,false);logging.setBoolean(s.handler,false);rejected.setBoolean(s.handler,false);}
            }));
            hooks.add(hook(panel.getDeclaredMethod("onViewAttached")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object r=chain.proceed();try{connect(((View)viewField.get(chain.getThisObject())).getContext());}catch(Exception e){note("BACK_CONNECT_FAILED "+e.getClass().getSimpleName());}return r;
            }));
            hooks.add(hook(panel.getDeclaredMethod("onDestroy")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                cancelStroke(chain.getThisObject());return chain.proceed();
            }));
            hooks.add(hook(motion).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject();MotionEvent e=(MotionEvent)chain.getArg(0);
                boolean take=false;
                try{take=observe(owner,e);}catch(Exception ex){cancelStroke(owner);note("BACK_FAILED "+ex.getClass().getSimpleName());}
                if(!take)return chain.proceed();
                // Cancel predictive back before the parent dispatches this physical UP to Shell.
                Stroke committed=pending;pending=null;
                try{cancelBack.invoke(callbackField.get(owner));cancelling=committed;}
                catch(Exception ex){abort(committed);note("BACK_CANCEL_FAILED "+ex.getClass().getSimpleName());return chain.proceed();}
                MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
                try{return chain.proceed(new Object[]{cancel});}finally{
                    cancel.recycle();if(committed!=null)main.post(()->waitForNativeBack(committed,0));
                }
            }));
            note("BACK_READY hooks=4 version="+BuildConfig.VERSION_NAME);
            installScreenshotHooks(cl);
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}note("BACK_DISABLED "+e.getClass().getSimpleName());}
    }
    // Runs in hooked SystemUI only; optional exact target signatures are checked first.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installBackFinish(ClassLoader loader){
        try{
            Class<?> transitions=Class.forName("com.android.wm.shell.transition.Transitions",false,loader);
            Class<?> active=Class.forName("com.android.wm.shell.transition.Transitions$ActiveTransition",false,loader);
            Class<?> info=Class.forName("android.window.TransitionInfo",false,loader);
            Class<?> change=Class.forName("android.window.TransitionInfo$Change",false,loader);
            Field known=field(transitions,"mKnownTransitions"),mix=field(transitions,"mMixpatchAnimations");
            Field start=field(active,"mStartT"),finish=field(active,"mFinishT"),merged=field(active,"mMerged"),details=field(active,"mInfo");
            Method changes=info.getDeclaredMethod("getChanges"),task=change.getDeclaredMethod("getTaskInfo");
            Method end=transitions.getDeclaredMethod("onFinish",IBinder.class,Class.forName("android.window.WindowContainerTransaction",false,loader));
            if(end.getReturnType()!=void.class||start.getType()!=SurfaceControl.Transaction.class||finish.getType()!=SurfaceControl.Transaction.class
                ||details.getType()!=info||!Map.class.isAssignableFrom(known.getType())||!List.class.isAssignableFrom(mix.getType())
                ||!List.class.isAssignableFrom(merged.getType())||!List.class.isAssignableFrom(changes.getReturnType())
                ||task.getReturnType()!=ActivityManager.RunningTaskInfo.class)throw new NoSuchMethodException("back finish signatures");
            hook(end).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Stroke s=settling;
                try{if(s!=null&&live()&&s.gesture.revision()==generation&&s.controller!=null
                        &&field(s.controller.getClass(),"mTransitions").get(s.controller)==chain.getThisObject()
                        &&((List<?>)mix.get(chain.getThisObject())).isEmpty()){
                    Object running=((Map<?,?>)known.get(chain.getThisObject())).get(chain.getArg(0));
                    if(running!=null){
                        List<Object> group=new ArrayList<>();group.add(running);
                        List<?> joined=(List<?>)merged.get(running);if(joined!=null)group.addAll(joined);
                        SurfaceControl.Transaction tail=(SurfaceControl.Transaction)finish.get(running);boolean matches=false;
                        for(Object item:group){
                            Object description=details.get(item);
                            if(description!=null)for(Object c:(List<?>)changes.invoke(description)){
                                ActivityManager.RunningTaskInfo t=(ActivityManager.RunningTaskInfo)task.invoke(c);
                                if(t!=null&&t.taskId==s.task)matches=true;
                            }
                            if(item!=running){
                                SurfaceControl.Transaction first=(SurfaceControl.Transaction)start.get(item),last=(SurfaceControl.Transaction)finish.get(item);
                                if(first!=null)tail=first;if(last!=null)tail=last;
                            }
                        }
                        // onFinish merges main finish, then each merged start/finish, in that order.
                        // Append to the last native transaction so no earlier alpha=1 can win.
                        if(matches&&tail!=null)try(SurfaceControl.Transaction mask=Wire.finishBack(s.token)){
                            if(mask!=null){tail.merge(mask);note("BACK_FINISH_MASKED task="+s.task+" merged="+(group.size()-1));}
                        }
                    }
                }}catch(Throwable error){note("BACK_FINISH_MASK_FAILED "+error.getClass().getSimpleName());}
                return chain.proceed();
            });
            note("BACK_FINISH_HOOK_READY");
        }catch(Throwable unavailable){note("BACK_FINISH_HOOK_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }
    // A transparent blocker consumes application touches, but native InputMonitor also sees DOWN.
    // Optional target-verified admission gate: leave the outer receiver's finishInputEvent intact.
    // Runs only inside hooked SystemUI; the hidden getter is optional and API37-preflighted.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installEdgeTouchAdmission(ClassLoader cl){
        try{
            Class<?> displayHandler=Class.forName("com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",false,cl);
            Class<?> adapter=field(displayHandler,"onInputEvent").getType();
            if(adapter.isInterface())adapter=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler$$ExternalSyntheticLambda20",false,cl);
            Class<?> handler=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",false,cl);
            Field owner=field(adapter,"f$0"),displays=field(handler,"mDisplayBackGestureHandlers");
            Method invoke=adapter.getDeclaredMethod("invoke",Object.class);
            Method cancel=handler.getDeclaredMethod("cancelGesture",MotionEvent.class);
            Method eventDisplay=MotionEvent.class.getDeclaredMethod("getDisplayId");
            Class<?> unitClass=Class.forName("kotlin.Unit",false,cl);
            Field instance=unitClass.getField("INSTANCE");
            if(owner.getType()!=handler||displays.getType()!=Map.class||invoke.getReturnType()!=Object.class
                ||cancel.getReturnType()!=void.class||eventDisplay.getReturnType()!=int.class||Modifier.isStatic(invoke.getModifiers())
                ||Modifier.isStatic(cancel.getModifiers())||instance.getType()!=unitClass||!Modifier.isStatic(instance.getModifiers()))
                throw new NoSuchMethodException("edge admission signature mismatch");
            Object unit=instance.get(null);if(unit==null)throw new IllegalStateException("missing Unit.INSTANCE");
            Class<?> compatType=Class.forName("com.android.systemui.shared.system.InputMonitorCompat",false,cl);
            Class<?> monitorType=Class.forName("android.view.InputMonitor");
            Field compat=field(displayHandler,"inputMonitorCompat"),monitor=field(compatType,"mInputMonitor");
            Method pilfer=monitorType.getDeclaredMethod("pilferPointers");
            if(compat.getType()!=compatType||monitor.getType()!=monitorType||pilfer.getReturnType()!=void.class)
                throw new NoSuchMethodException("corner input monitor signature mismatch");
            pilfer.setAccessible(true);
            invoke.setAccessible(true);cancel.setAccessible(true);eventDisplay.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object argument=chain.getArg(0);
                if(cornerReady&&active.get()&&argument instanceof MotionEvent event){
                    try{
                        if((Integer)eventDisplay.invoke(event)==Display.DEFAULT_DISPLAY){
                            SystemUiHost host=SystemUiHost.live;
                            Object map=displays.get(owner.get(chain.getThisObject()));
                            Object display=map instanceof Map<?,?> handlers?handlers.get(Display.DEFAULT_DISPLAY):null;
                            if(host!=null&&display!=null&&host.cornerInput(event,()->{
                                try{pilfer.invoke(monitor.get(compat.get(display)));}
                                catch(Exception error){throw new IllegalStateException("corner pilfer",error);}
                            }))return unit;
                        }
                    }catch(Throwable failure){
                        cornerReady=false;note("CORNER_INPUT_DISABLED "+failure.getClass().getSimpleName());
                        // Keep the native receiver alive; a later native DOWN resets its state.
                    }
                }
                if(active.get()&&argument instanceof MotionEvent event&&event.getActionMasked()==MotionEvent.ACTION_DOWN
                    &&event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)){
                    try{
                        if((Integer)eventDisplay.invoke(event)!=Display.DEFAULT_DISPLAY)return chain.proceed();
                        SystemUiHost host=SystemUiHost.live;
                        if(host!=null&&host.blocksEdgeTouch(event.getX(),event.getY())){
                            Object nativeHandler=owner.get(chain.getThisObject()),map=displays.get(nativeHandler);
                            if(map instanceof Map<?,?> handlers&&handlers.get(Display.DEFAULT_DISPLAY)!=null){
                                cancel.invoke(nativeHandler,event);return unit;
                            }
                        }
                    }catch(Throwable failure){active.set(false);note("EDGE_BACK_ADMISSION_DISABLED "+failure.getClass().getSimpleName());}
                }
                return chain.proceed();
            });
            cornerReady=true;
            note("CORNER_INPUT_READY native monitor; pilfer only after diagonal threshold");
            note("EDGE_BACK_ADMISSION_READY");
        }catch(Throwable failure){note("EDGE_BACK_ADMISSION_UNAVAILABLE "+failure.getClass().getSimpleName());}
    }
    private synchronized void cancelStroke(Object owner){Stroke s=strokes.remove(owner);if(s!=null)preview(s,0);}
    private Runnable reconnect;
    private synchronized void connect(Context c){
        if(context!=null&&SystemUiHost.live!=null)return;
        if(reconnect!=null){main.removeCallbacks(reconnect);reconnect=null;}
        Context app=c.getApplicationContext();
        try{
            if(context==null){
                BroadcastReceiver visual=new BroadcastReceiver(){public void onReceive(Context c,Intent i){BackVisual.receive(app,i,getSentFromUid());}};
                app.registerReceiver(visual,new IntentFilter(BackVisual.STATE),null,main,Context.RECEIVER_EXPORTED);
                try{
                    app.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                        if(getSentFromUid()==android.os.Process.myUid())configure(i);
                    }},new IntentFilter(LauncherGuard.STATE),null,main,Context.RECEIVER_EXPORTED);
                }catch(RuntimeException failure){app.unregisterReceiver(visual);throw failure;}
                context=app;
            }
            SystemUiHost.start(app);
            if(SystemUiHost.live==null)throw new IllegalStateException("host initialization failed");
            SystemUiHost.live.publish(true);note("BACK_CONNECTED");
        }catch(RuntimeException failure){
            note("BACK_CONNECT_RETRY "+failure.getClass().getSimpleName());
            reconnect=()->connect(app);main.postDelayed(reconnect,3000);
        }
    }
    private synchronized void configure(Intent i){
        Bundle b=i.getExtras();sink=b==null?null:b.getBinder("dropChannel");lease=b==null?null:b.getBinder("lease");
        enabled=i.getBooleanExtra("dropEnabled",false)&&sink!=null&&lease!=null;
        preserveBars=i.getBooleanExtra("immersive",false);
        if(!enabled){generation++;for(Stroke s:strokes.values())preview(s,0);strokes.clear();}
        note("BACK_STATE enabled="+enabled);
    }
    private void installScreenshotHooks(ClassLoader cl){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> controller=Class.forName("com.android.systemui.screenshot.ScreenshotController",false,cl);
            Class<?> screenshotWindow=Class.forName("com.android.systemui.screenshot.ScreenshotWindow",false,cl);
            Method handle=null;
            for(Method m:controller.getDeclaredMethods())if(m.getName().equals("handleScreenshot")&&m.getParameterCount()==3
                &&m.getParameterTypes()[0].getName().equals("com.android.systemui.screenshot.ScreenshotData")){
                if(handle!=null)throw new NoSuchMethodException("ambiguous screenshot entry");handle=m;
            }
            if(handle==null)throw new NoSuchMethodException("screenshot entry");
            hooks.add(hook(handle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object screenshot=field(controller,"window").get(chain.getThisObject());
                Window w=(Window)field(screenshotWindow,"window").get(screenshot);watchScreenshot(w);
                return chain.proceed();
            }));
            hooks.add(hook(screenshotWindow.getDeclaredMethod("removeWindow")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();Window w=(Window)field(screenshotWindow,"window").get(chain.getThisObject());
                View v=w.peekDecorView();if(v==null||!v.isAttachedToWindow())screenshot(false);return result;
            }));
            note("SCREENSHOT_READY hooks=2");
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}note("SCREENSHOT_DISABLED "+e.getClass().getSimpleName());}
    }
    private synchronized void watchScreenshot(Window w){
        connect(w.getContext());View v=w.getDecorView();screenshot(true);
        if(screenshotViews.add(v))v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){screenshot(true);preserveScreenshotBars(w);view.post(()->preserveScreenshotBars(w));}
            public void onViewDetachedFromWindow(View view){screenshot(false);}
        });
        if(v.isAttachedToWindow())preserveScreenshotBars(w);
        main.postDelayed(()->{if(!v.isAttachedToWindow())screenshot(false);},1500);
    }
    private void preserveScreenshotBars(Window w){
        if(!preserveBars)return;WindowInsetsController c=w.getInsetsController();
        if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}
    }
    private synchronized void screenshot(boolean shown){
        if(screenshotShown==shown)return;screenshotShown=shown;if(sink==null)return;
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(CHANNEL);data.writeBoolean(shown);
            sink.transact(IBinder.FIRST_CALL_TRANSACTION+3,data,null,IBinder.FLAG_ONEWAY);note("SCREENSHOT_PRIORITY shown="+shown);
        }catch(RemoteException e){note("SCREENSHOT_SIGNAL_FAILED");}finally{data.recycle();}
    }
    private boolean live(){return enabled&&lease!=null&&lease.isBinderAlive()&&sink!=null&&sink.isBinderAlive();}
    private Stroke pending;
    static boolean cancelNativeUp(Object expected,long down,Object owner,MotionEvent event){
        return expected==owner&&event.getDownTime()==down&&event.getActionMasked()==MotionEvent.ACTION_UP;
    }
    private void abort(Stroke s){
        if(s==null)return;
        if(settling==s)settling=null;
        if(pending==s)pending=null;
        if(s.handoff.cancel())Wire.cancelBack(s.token);
    }
    /** State already consumed the cancellation transition; release only this native ticket. */
    private void cancelPrepared(Stroke s){
        if(settling==s)settling=null;
        if(pending==s)pending=null;
        Wire.cancelBack(s.token);
    }
    private synchronized boolean observe(Object owner,MotionEvent e) throws Exception {
        View view=(View)viewField.get(owner);connect(view.getContext());
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            abort(settling);cancelling=null;
            generation++;
            Stroke old=strokes.remove(owner);if(old!=null)preview(old,0);
            // Preserve native sampling order: a disabled/non-finger stream never queries the View display.
            if(!live()||e.getPointerCount()!=1||e.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER)return false;
            if(!LongBackGesture.mayStart(true,1,true,view.getDisplay()==null?-1:view.getDisplay().getDisplayId()))return false;
            android.util.DisplayMetrics metrics=view.getResources().getDisplayMetrics();
            Stroke s=new Stroke();s.gesture.begin(e.getX(),e.getDownTime(),metrics.widthPixels,metrics.heightPixels,metrics.density,generation);
            strokes.put(owner,s);return false;
        }
        Stroke s=strokes.get(owner);if(s==null)return false;
        LongBackGesture.Step step;
        // Cancellation facts short-circuit before position sampling, as in the native observer.
        if(!live()||s.gesture.down()!=e.getDownTime()||action==MotionEvent.ACTION_CANCEL
                ||e.getPointerCount()!=1||action==MotionEvent.ACTION_POINTER_DOWN)
            step=s.gesture.event(MotionEvent.ACTION_CANCEL,0,0,0,false);
        else step=s.gesture.event(action,e.getX(),e.getDownTime(),1,true);
        if(!step.retain())strokes.remove(owner);
        if(step.armChange()!=0)note(step.armChange()>0?"BACK_ARMED":"BACK_WITHDRAWN");
        if(step.hint()>=0)preview(s,step.hint());
        if(!step.tryAdmit())return false;
        // Ordinary Back never needs a task/WM/Keyguard Binder query on its input path.
        if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return false;
        ActivityManager.RunningTaskInfo task=context.getSystemService(ActivityManager.class).getRunningTasks(1).stream().findFirst().orElse(null);
        if(task==null||task.topActivity==null)return false;
        String pkg=task.topActivity.getPackageName();
        if(!Wire.allowedPackage(pkg)||pkg.equals(Wire.PKG)||pkg.equals(PACKAGE)||pkg.equals(LauncherGuard.PACKAGE))return false;
        s.task=task.taskId;s.pkg=pkg;
        // Synchronous admission prevents swallowing Back when the module rejects the request.
        Object callback=callbackField.get(owner);
        Object handler=field(callback.getClass(),"this$0").get(callback);
        s.handler=handler;
        Object animation=field(handler.getClass(),"mBackAnimation").get(handler);
        s.controller=animation==null?null:field(animation.getClass(),"this$0").get(animation);
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(CHANNEL);data.writeInt(s.task);data.writeString(s.pkg);
            if(!sink.transact(IBinder.FIRST_CALL_TRANSACTION+2,data,reply,0))return false;
            reply.readException();boolean accepted=reply.readBoolean();
            if(accepted){
                s.token=Wire.token();BackVisual.Pose pose=BackVisual.pose(s.task);android.view.SurfaceControl leash=null;
                try{Object[] apps=s.controller==null?null:(Object[])field(s.controller.getClass(),"mApps").get(s.controller);
                    if(apps!=null)for(Object target:apps)if(target!=null&&field(target.getClass(),"taskId").getInt(target)==s.task){
                        leash=(android.view.SurfaceControl)field(target.getClass(),"leash").get(target);break;
                    }
                }catch(Exception ignored){}
                if(!Wire.prepareBack(s.task,s.pkg,s.token,pose,leash)){note("BACK_PREPARE_REJECTED");return false;}
                BackVisual.claim(s.task);s.handoff=new BackHandoffState(s.gesture.revision());pending=s;settling=s;
            }
            note(accepted?"BACK_ADMITTED":"BACK_REJECTED");return accepted;
        }finally{data.recycle();reply.recycle();}
    }
    private void waitForNativeBack(Stroke s,int attempt){
        BackHandoffState.Action decision=s.handoff.check(live(),generation,attempt);
        if(decision==BackHandoffState.Action.ABORT){cancelPrepared(s);if(attempt>=BackHandoffState.NATIVE_ATTEMPTS)note("BACK_CANCEL_TIMEOUT");return;}
        if(decision!=BackHandoffState.Action.CHECK_NATIVE)return;
        try{
            Runnable check=()->{
                try{
                    boolean busy=s.controller!=null&&(field(s.controller.getClass(),"mBackNavigationInfo").get(s.controller)!=null
                        ||field(s.controller.getClass(),"mPostCommitAnimationInProgress").getBoolean(s.controller));
                    BackHandoffState.Action next=s.handoff.nativeState(busy,s.controller!=null,live(),generation);
                    if(next==BackHandoffState.Action.ABORT)main.post(()->cancelPrepared(s));
                    else if(next==BackHandoffState.Action.WAIT_NATIVE)main.postDelayed(()->waitForNativeBack(s,attempt+1),BackHandoffState.NATIVE_RETRY_MS);
                    else if(next==BackHandoffState.Action.COMMIT)main.post(()->commit(s));
                    else if(next==BackHandoffState.Action.WAIT_IDLE){
                        Object transitions=field(s.controller.getClass(),"mTransitions").get(s.controller);
                        Method idle=transitions.getClass().getDeclaredMethod("runOnIdle",Runnable.class);idle.setAccessible(true);
                        idle.invoke(transitions,(Runnable)()->main.post(()->{
                            BackHandoffState.Action ready=s.handoff.idle(live(),generation);
                            if(ready==BackHandoffState.Action.COMMIT)commit(s);
                            else if(ready==BackHandoffState.Action.ABORT)cancelPrepared(s);
                        }));
                        main.postDelayed(()->{
                            if(s.handoff.idleTimeout()==BackHandoffState.Action.ABORT){cancelPrepared(s);note("BACK_TRANSITION_TIMEOUT");}
                        },BackHandoffState.IDLE_TIMEOUT_MS);
                    }
                }catch(Exception e){main.post(()->abort(s));note("BACK_WAIT_FAILED "+e.getClass().getSimpleName());}
            };
            // Read Shell-owned state after the converted terminal CANCEL reaches its executor.
            if(s.controller==null)check.run();
            else ((java.util.concurrent.Executor)field(s.controller.getClass(),"mShellExecutor").get(s.controller)).execute(check);
        }catch(Exception e){abort(s);note("BACK_WAIT_FAILED "+e.getClass().getSimpleName());}
    }
    private void commit(Stroke s){
        BackHandoffState.Action decision=s.handoff.commit(live(),generation);
        if(decision==BackHandoffState.Action.ABORT){cancelPrepared(s);return;}
        if(decision!=BackHandoffState.Action.COMMIT)return;
        if(settling==s)settling=null;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeInt(s.task);data.writeString(s.pkg);data.writeString(s.token);
            if(!sink.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0)){abort(s);note("BACK_COMMIT_FAILED");return;}
            reply.readException();boolean accepted=reply.readBoolean();
            if(accepted)s.handoff.completed();else abort(s);
            note(accepted?"BACK_HANDOFF":"BACK_COMMIT_REJECTED");
        }catch(RemoteException|RuntimeException e){abort(s);note("BACK_COMMIT_FAILED");}finally{data.recycle();reply.recycle();}
    }
    private void preview(Stroke s,int stage){
        if(!s.gesture.hintChanged(stage)||sink==null)return;
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(CHANNEL);data.writeInt(stage);sink.transact(IBinder.FIRST_CALL_TRANSACTION+1,data,null,IBinder.FLAG_ONEWAY);}
        catch(RemoteException e){note("BACK_HINT_FAILED");}finally{data.recycle();}
    }
    private static Field field(Class<?> c,String name) throws Exception {for(;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private void note(String s){try{log(4,"BackLong",s);android.util.Log.i("BackLong",s);}catch(Throwable ignored){}}
}
