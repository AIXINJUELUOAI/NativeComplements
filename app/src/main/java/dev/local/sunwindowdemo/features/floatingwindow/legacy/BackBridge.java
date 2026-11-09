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
    private static final class Stroke {
        float x,threshold,density;boolean left,armed;
        long down;int stage,task,revision;String pkg;Object controller;
    }
    @Override public void onModuleLoaded(ModuleLoadedParam p){eligible=!p.isSystemServer()&&PACKAGE.equals(p.getProcessName());}
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||attempted||!PACKAGE.equals(p.getPackageName()))return;attempted=true;
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate",Application.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();Application app=(Application)chain.getArg(0);
                    if(PACKAGE.equals(app.getPackageName()))main.post(()->{try{connect(app);}catch(Exception e){note("HOST_FAILED "+e);}});
                    return result;
                });
        }catch(Exception e){note("HOST_BOOT_HOOK_FAILED "+e);}
        installEdgeTouchAdmission(p.getDefaultClassLoader());
        try{
            ClassLoader cl=p.getDefaultClassLoader();
            Class<?> panel=Class.forName("com.android.systemui.navigationbar.gestural.BackPanelController",false,cl);
            viewField=field(panel,"mView");callbackField=field(panel,"backCallback");
            cancelBack=Class.forName("com.android.systemui.plugins.NavigationEdgeBackPlugin$BackCallback",false,cl).getDeclaredMethod("cancelBack");
            Method motion=panel.getDeclaredMethod("onMotionEvent",MotionEvent.class);
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
                try{cancelBack.invoke(callbackField.get(owner));}
                catch(Exception ex){note("BACK_CANCEL_FAILED "+ex.getClass().getSimpleName());return chain.proceed();}
                MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
                try{return chain.proceed(new Object[]{cancel});}finally{
                    cancel.recycle();if(committed!=null)main.post(()->waitForNativeBack(committed,0));
                }
            }));
            note("BACK_READY hooks=3 version=0.2.0-preview1");
            installScreenshotHooks(cl);
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}note("BACK_DISABLED "+e.getClass().getSimpleName());}
    }
    // A transparent blocker consumes application touches, but native InputMonitor also sees DOWN.
    // Optional target-verified admission gate: leave the outer receiver's finishInputEvent intact.
    // Runs only inside hooked SystemUI; the hidden getter is optional and API37-preflighted.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installEdgeTouchAdmission(ClassLoader cl){
        try{
            Class<?> adapter=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler$$ExternalSyntheticLambda20",false,cl);
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
            invoke.setAccessible(true);cancel.setAccessible(true);eventDisplay.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object argument=chain.getArg(0);
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
            note("EDGE_BACK_ADMISSION_READY");
        }catch(Throwable failure){note("EDGE_BACK_ADMISSION_UNAVAILABLE "+failure.getClass().getSimpleName());}
    }
    private synchronized void cancelStroke(Object owner){Stroke s=strokes.remove(owner);if(s!=null)preview(s,0);}
    private synchronized void connect(Context c){
        if(context!=null)return;context=c.getApplicationContext();
        context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            if(getSentFromUid()==android.os.Process.myUid())configure(i);
        }},new IntentFilter(LauncherGuard.STATE),null,main,Context.RECEIVER_EXPORTED);
        SystemUiHost.start(context);if(SystemUiHost.live!=null)SystemUiHost.live.publish(true);
        note("BACK_CONNECTED");
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
    private synchronized boolean observe(Object owner,MotionEvent e) throws Exception {
        View view=(View)viewField.get(owner);connect(view.getContext());
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            generation++;
            Stroke old=strokes.remove(owner);if(old!=null)preview(old,0);
            if(!live()||e.getPointerCount()!=1||e.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER||view.getDisplay()==null||view.getDisplay().getDisplayId()!=0)return false;
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return false;
            Rect d=context.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
            ActivityManager.RunningTaskInfo task=context.getSystemService(ActivityManager.class).getRunningTasks(1).stream().findFirst().orElse(null);
            if(task==null||task.topActivity==null)return false;
            String pkg=task.topActivity.getPackageName();
            if(!Wire.allowedPackage(pkg)||pkg.equals(Wire.PKG)||pkg.equals(PACKAGE)||pkg.equals(LauncherGuard.PACKAGE))return false;
            Stroke s=new Stroke();s.x=e.getX();s.left=s.x<d.width()/2f;s.threshold=BackSwipe.threshold(d.width(),d.height());
            s.density=context.getResources().getDisplayMetrics().density;s.down=e.getDownTime();s.task=task.taskId;s.pkg=pkg;
            s.revision=generation;
            strokes.put(owner,s);note("BACK_BEGIN task="+s.task+" threshold="+Math.round(s.threshold));return false;
        }
        Stroke s=strokes.get(owner);if(s==null)return false;
        if(!live()||s.down!=e.getDownTime()||action==MotionEvent.ACTION_CANCEL||e.getPointerCount()!=1||action==MotionEvent.ACTION_POINTER_DOWN){strokes.remove(owner);preview(s,0);return false;}
        float inward=s.left?e.getX()-s.x:s.x-e.getX();
        boolean next=BackSwipe.armed(inward,s.threshold,s.armed,12*s.density);
        if(next!=s.armed){s.armed=next;note(next?"BACK_ARMED":"BACK_WITHDRAWN");}
        if(action==MotionEvent.ACTION_MOVE)preview(s,s.armed?2:inward>s.threshold*.35f?1:0);
        if(action!=MotionEvent.ACTION_UP)return false;
        strokes.remove(owner);preview(s,0);
        if(!s.armed)return false;
        // Synchronous admission prevents swallowing Back when the module rejects the request.
        Object callback=callbackField.get(owner);
        Object handler=field(callback.getClass(),"this$0").get(callback);
        Object animation=field(handler.getClass(),"mBackAnimation").get(handler);
        s.controller=animation==null?null:field(animation.getClass(),"this$0").get(animation);
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(CHANNEL);data.writeInt(s.task);data.writeString(s.pkg);
            if(!sink.transact(IBinder.FIRST_CALL_TRANSACTION+2,data,reply,0))return false;
            reply.readException();boolean accepted=reply.readBoolean();
            if(accepted)pending=s;note(accepted?"BACK_ADMITTED":"BACK_REJECTED");return accepted;
        }finally{data.recycle();reply.recycle();}
    }
    private void waitForNativeBack(Stroke s,int attempt){
        if(!live()||s.revision!=generation)return;
        if(attempt>=40){note("BACK_CANCEL_TIMEOUT");return;}
        try{
            Runnable check=()->{
                try{
                    boolean busy=s.controller!=null&&(field(s.controller.getClass(),"mBackNavigationInfo").get(s.controller)!=null
                        ||field(s.controller.getClass(),"mPostCommitAnimationInProgress").getBoolean(s.controller));
                    main.post(()->{if(busy)main.postDelayed(()->waitForNativeBack(s,attempt+1),40);else commit(s);});
                }catch(Exception e){note("BACK_WAIT_FAILED "+e.getClass().getSimpleName());}
            };
            // Read Shell-owned state on its own executor after it has received physical UP.
            if(s.controller==null)check.run();
            else ((java.util.concurrent.Executor)field(s.controller.getClass(),"mShellExecutor").get(s.controller)).execute(check);
        }catch(Exception e){note("BACK_WAIT_FAILED "+e.getClass().getSimpleName());}
    }
    private void commit(Stroke s){
        if(!live()||s.revision!=generation)return;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeInt(s.task);data.writeString(s.pkg);
            if(!sink.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0)){note("BACK_COMMIT_FAILED");return;}
            reply.readException();note(reply.readBoolean()?"BACK_HANDOFF":"BACK_COMMIT_REJECTED");
        }catch(RemoteException e){note("BACK_COMMIT_FAILED");}finally{data.recycle();reply.recycle();}
    }
    private void preview(Stroke s,int stage){
        if(s.stage==stage)return;s.stage=stage;if(sink==null)return;
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(CHANNEL);data.writeInt(stage);sink.transact(IBinder.FIRST_CALL_TRANSACTION+1,data,null,IBinder.FLAG_ONEWAY);}
        catch(RemoteException e){note("BACK_HINT_FAILED");}finally{data.recycle();}
    }
    private static Field field(Class<?> c,String name) throws Exception {for(;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private void note(String s){try{log(4,"BackLong",s);android.util.Log.i("BackLong",s);}catch(Throwable ignored){}}
}
