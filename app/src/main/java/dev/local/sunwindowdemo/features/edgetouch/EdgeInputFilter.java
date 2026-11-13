package dev.local.sunwindowdemo.features.edgetouch;

import android.content.*;
import android.os.*;
import android.view.InputEvent;
import android.view.MotionEvent;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** InputDispatcher's existing Java filter boundary, preserving the native accessibility filter. */
public final class EdgeInputFilter {
    private final EdgeInputPolicy policy=new EdgeInputPolicy();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object service,nativeService,filterLock;
    private final Field nativeFilter;
    private final Method enable,inject;
    private final Context context;
    private final Consumer<String> log;
    private long seen;
    private volatile int[] regions=new int[0];
    private volatile boolean active;
    private volatile boolean failed;

    private EdgeInputFilter(Object service,Field contextField,Field nativeField,Field lockField,Field filter,
                            Method enable,Method inject,Consumer<String> log)throws Exception{
        this.service=service;this.nativeService=nativeField.get(service);this.filterLock=lockField.get(service);
        this.nativeFilter=filter;this.enable=enable;this.inject=inject;this.context=(Context)contextField.get(service);this.log=log;
    }

    // Only called inside system_server after exact framework signature checks.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    public static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> manager=Class.forName("com.android.server.input.InputManagerService",false,loader);
            Field context=field(manager,"mContext"),nativeField=field(manager,"mNative"),lock=field(manager,"mInputFilterLock"),filter=field(manager,"mInputFilter");
            Method enable=nativeField.getType().getMethod("setInputFilterEnabled",boolean.class);enable.setAccessible(true);
            Method inject=nativeField.getType().getMethod("injectInputEvent",InputEvent.class,boolean.class,int.class,int.class,int.class,int.class);inject.setAccessible(true);
            Method split=MotionEvent.class.getDeclaredMethod("split",int.class);split.setAccessible(true);
            Method display=InputEvent.class.getDeclaredMethod("getDisplayId");display.setAccessible(true);
            Method incoming=manager.getDeclaredMethod("filterInputEvent",InputEvent.class,int.class);
            Method setFilter=manager.getDeclaredMethod("setInputFilter",Class.forName("android.view.IInputFilter",false,loader));
            java.util.concurrent.atomic.AtomicReference<EdgeInputFilter> host=new java.util.concurrent.atomic.AtomicReference<>();
            hooks.add(module.hook(manager.getDeclaredMethod("start")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                if(host.get()==null)try{
                    EdgeInputFilter next=new EdgeInputFilter(chain.getThisObject(),context,nativeField,lock,filter,enable,inject,log);
                    next.connect();host.set(next);log.accept("EDGE_INPUT_CONNECTED");
                }catch(Exception error){log.accept("EDGE_INPUT_CONNECT_FAILED "+error);}
                return result;
            }));
            hooks.add(module.hook(setFilter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();EdgeInputFilter h=host.get();
                if(h!=null&&h.service==chain.getThisObject())h.sync();
                return result;
            }));
            hooks.add(module.hook(incoming).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                EdgeInputFilter h=host.get();InputEvent input=(InputEvent)chain.getArg(0);
                if(h==null||h.service!=chain.getThisObject()||!h.active||!(input instanceof MotionEvent event))return chain.proceed();
                int accepted=h.policy.accepted(event,(Integer)display.invoke(event)),all=0;
                for(int i=0;i<event.getPointerCount();i++)all|=1<<event.getPointerId(i);
                try{
                    if(accepted==all)return chain.proceed();
                    if(accepted==0){event.recycle();return false;}
                    MotionEvent kept=(MotionEvent)split.invoke(event,accepted);
                    try{
                        synchronized(h.filterLock){
                            if(h.nativeFilter.get(h.service)!=null)return chain.proceed(new Object[]{kept,chain.getArg(1)});
                            // A true filter result dispatches the original native event. Changed
                            // pointer sets must be reinjected with the native FILTERED policy flags.
                            try{
                                int result=(Integer)h.inject.invoke(h.nativeService,kept,false,-1,0,0,chain.getArg(1));
                                if(result!=0)h.disableAfterFailure("inject result="+result);
                            }finally{kept.recycle();}
                            return false;
                        }
                    }catch(Exception error){h.disableAfterFailure(error.toString());return false;}
                    finally{event.recycle();}
                }finally{
                    if(!h.policy.holding()&&h.regions.length==0)h.main.post(h::sync);
                }
            }));
            log.accept("EDGE_INPUT_READY");
        }catch(Throwable error){
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("EDGE_INPUT_UNAVAILABLE "+error);
        }
    }

    private static Field field(Class<?> owner,String name)throws Exception{
        Field value=owner.getDeclaredField(name);value.setAccessible(true);return value;
    }

    private void connect(){
        IntentFilter filter=new IntentFilter(EdgeTouchStore.REGIONS);filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){
                try{
                    if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){regions=new int[0];policy.clear();sync();return;}
                    int uid=context.getPackageManager().getApplicationInfo(EdgeTouchStore.SYSTEM_UI,0).uid;
                    if(getSentFromUid()!=uid)return;
                    int[] next=intent.getIntArrayExtra("rectangles");
                    if(next==null||next.length%4!=0||next.length>256)return;
                    for(int value:next)if(value<0||value>32768)return;
                    regions=next.clone();seen=SystemClock.uptimeMillis();policy.regions(regions);sync();
                    context.sendBroadcast(new Intent(EdgeTouchStore.BACKEND).setPackage(EdgeTouchStore.SYSTEM_UI)
                        .putExtra("active",active),null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                    main.removeCallbacks(expire);main.postDelayed(expire,3000);
                }catch(Exception error){log.accept("EDGE_INPUT_UPDATE_FAILED "+error);}
            }
        },filter,null,main,Context.RECEIVER_EXPORTED);
    }

    private final Runnable expire=()->{
        if(SystemClock.uptimeMillis()-seen>=2900){regions=new int[0];policy.regions(regions);sync();}
    };

    private void sync(){
        synchronized(filterLock){
            boolean next=!failed&&(regions.length>0||policy.holding());
            try{enable.invoke(nativeService,next||nativeFilter.get(service)!=null);active=next;}
            catch(Exception error){active=false;log.accept("EDGE_INPUT_ENABLE_FAILED "+error);}
        }
    }

    private void disableAfterFailure(String reason){
        failed=true;active=false;regions=new int[0];policy.clear();
        log.accept("EDGE_INPUT_FAILED "+reason);main.post(this::sync);
    }
}
