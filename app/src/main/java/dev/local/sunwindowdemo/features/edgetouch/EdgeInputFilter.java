package dev.local.sunwindowdemo.features.edgetouch;

import android.content.*;
import android.os.*;
import android.view.InputDevice;
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
    private final ForwardedTimes forwarded=new ForwardedTimes();
    /** A valid second finger starts its own visible stream, not the earlier rejected finger's clock. */
    static final class ForwardedTimes {
        private final java.util.Map<Long,Long> adjusted=new java.util.HashMap<>();
        synchronized boolean holding(){return !adjusted.isEmpty();}
        synchronized void clear(){adjusted.clear();}
        synchronized long time(MotionEvent event,int accepted,int display){
            if(display!=0||!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return event.getDownTime();
            long key=((long)event.getDeviceId()<<32)|(display&0xffffffffL);
            int action=event.getActionMasked(),changed=1<<event.getPointerId(event.getActionIndex());
            if(action==MotionEvent.ACTION_DOWN)adjusted.remove(key);
            if(action==MotionEvent.ACTION_POINTER_DOWN&&accepted==changed&&!adjusted.containsKey(key))
                adjusted.put(key,event.getEventTime());
            long time=adjusted.getOrDefault(key,event.getDownTime());
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL
                ||action==MotionEvent.ACTION_POINTER_UP&&accepted==changed)adjusted.remove(key);
            return time;
        }
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object service,nativeService,filterLock;
    private final Field nativeFilter;
    private final Method enable,inject;
    private final Context context;
    private final Consumer<String> log;
    private long seen,cornerSeen;
    private volatile boolean cornersActive;
    private volatile String cornerPackage;
    private volatile int cornerWidth,cornerHeight;
    private GameCornerAccess cornerAccess;
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
            Method setDownTime=MotionEvent.class.getDeclaredMethod("setDownTime",long.class);setDownTime.setAccessible(true);
            Method display=InputEvent.class.getDeclaredMethod("getDisplayId");display.setAccessible(true);
            Method incoming=manager.getDeclaredMethod("filterInputEvent",InputEvent.class,int.class);incoming.setAccessible(true);
            Method setFilter=manager.getDeclaredMethod("setInputFilter",Class.forName("android.view.IInputFilter",false,loader));setFilter.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<EdgeInputFilter> host=new java.util.concurrent.atomic.AtomicReference<>();
            hooks.add(module.hook(manager.getDeclaredMethod("start")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                if(host.get()==null)try{
                    EdgeInputFilter next=new EdgeInputFilter(chain.getThisObject(),context,nativeField,lock,filter,enable,inject,log);
                    try{next.cornerAccess=GameCornerAccess.resolve(loader);log.accept("GAME_CORNER_INPUT_READY");}
                    catch(Exception unavailable){log.accept("GAME_CORNER_INPUT_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
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
                int action=event.getActionMasked(),displayId=(Integer)display.invoke(event);
                boolean cornerEligible=h.cornersActive&&displayId==0&&event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    &&(action!=MotionEvent.ACTION_DOWN&&action!=MotionEvent.ACTION_POINTER_DOWN
                    ||h.cornerAccess!=null&&h.cornerAccess.allows(h.context,h.cornerPackage,h.cornerWidth,h.cornerHeight));
                int accepted=accepted(h.policy,event,displayId,cornerEligible),all=0;
                for(int i=0;i<event.getPointerCount();i++)all|=1<<event.getPointerId(i);
                long downTime=h.forwarded.time(event,accepted,displayId);
                try{
                    if(accepted==all&&downTime==event.getDownTime())return chain.proceed();
                    if(accepted==0){event.recycle();return false;}
                    MotionEvent kept=(MotionEvent)split.invoke(event,accepted);
                    if(kept.getActionMasked()==MotionEvent.ACTION_DOWN&&downTime!=event.getDownTime()){
                        MotionEvent clean=MotionEvent.obtainNoHistory(kept);kept.recycle();kept=clean;
                    }
                    setDownTime.invoke(kept,downTime);
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
                    if(!h.policy.holding()&&!h.forwarded.holding()&&h.regions.length==0&&!h.cornersActive)h.main.post(h::sync);
                }
            }));
            log.accept("EDGE_INPUT_READY");
        }catch(Throwable error){
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("EDGE_INPUT_UNAVAILABLE "+error);
        }
    }

    /** Snapshot only the action's required native facts; non-touch/default-display input never enters policy. */
    static int accepted(EdgeInputPolicy policy,MotionEvent event,int display){return accepted(policy,event,display,true);}
    static int accepted(EdgeInputPolicy policy,MotionEvent event,int display,boolean cornerEligible){
        int all=0;
        for(int i=0;i<event.getPointerCount();i++)all|=1<<event.getPointerId(i);
        if(display!=0||!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return all;
        long stream=((long)event.getDeviceId()<<32)|(display&0xffffffffL);
        int action=event.getActionMasked(),changed=0;
        EdgeInputPolicy.Action kind=switch(action){
            case MotionEvent.ACTION_DOWN -> EdgeInputPolicy.Action.DOWN;
            case MotionEvent.ACTION_POINTER_DOWN -> EdgeInputPolicy.Action.POINTER_DOWN;
            case MotionEvent.ACTION_POINTER_UP -> EdgeInputPolicy.Action.POINTER_UP;
            case MotionEvent.ACTION_UP -> EdgeInputPolicy.Action.UP;
            case MotionEvent.ACTION_CANCEL -> EdgeInputPolicy.Action.CANCEL;
            default -> EdgeInputPolicy.Action.OTHER;
        };
        boolean finger=false;float x=0,y=0;
        if(kind==EdgeInputPolicy.Action.DOWN||kind==EdgeInputPolicy.Action.POINTER_DOWN){
            int i=event.getActionIndex();
            finger=event.getToolType(i)==MotionEvent.TOOL_TYPE_FINGER;
            if(finger){x=event.getX(i);y=event.getY(i);changed=1<<event.getPointerId(i);}
        }else if(kind==EdgeInputPolicy.Action.POINTER_UP)changed=1<<event.getPointerId(event.getActionIndex());
        return policy.accepted(stream,kind,all,changed,finger,x,y,cornerEligible);
    }

    private static Field field(Class<?> owner,String name)throws Exception{
        Field value=owner.getDeclaredField(name);value.setAccessible(true);return value;
    }

    private void connect(){
        IntentFilter filter=new IntentFilter(EdgeTouchStore.REGIONS);filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(dev.local.sunwindowdemo.features.gamemode.GameModeStore.CORNER_REGIONS);
        context.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){
                try{
                    if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){regions=new int[0];cornersActive=false;policy.clear();forwarded.clear();sync();return;}
                    int uid=context.getPackageManager().getApplicationInfo(EdgeTouchStore.SYSTEM_UI,0).uid;
                    if(getSentFromUid()!=uid)return;
                    if(dev.local.sunwindowdemo.features.gamemode.GameModeStore.CORNER_REGIONS.equals(intent.getAction())){
                        int width=intent.getIntExtra("width",0),height=intent.getIntExtra("height",0);
                        float percent=intent.getFloatExtra("percent",0);
                        int[] protectedRects=intent.getIntArrayExtra("protected");
                        if(width<0||height<0||width>32768||height>32768||protectedRects==null
                            ||protectedRects.length%4!=0||protectedRects.length>256)return;
                        for(int value:protectedRects)if(value<0||value>32768)return;
                        if(!Float.isFinite(percent)||width>0&&(height==0||percent<=0||percent>100))return;
                        cornerWidth=width;cornerHeight=height;cornerPackage=intent.getStringExtra("package");
                        cornersActive=width>0&&height>0&&dev.local.sunwindowdemo.Rules.validPackage(cornerPackage);
                        policy.corners(cornersActive?width:0,cornersActive?height:0,percent,protectedRects);
                        cornerSeen=SystemClock.uptimeMillis();sync();
                        main.removeCallbacks(expireCorners);main.postDelayed(expireCorners,3000);return;
                    }
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

    private final Runnable expireCorners=()->{
        if(SystemClock.uptimeMillis()-cornerSeen>=2900){cornersActive=false;policy.corners(0,0,0,new int[0]);sync();}
    };

    private void sync(){
        synchronized(filterLock){
            boolean next=!failed&&(regions.length>0||cornersActive||policy.holding()||forwarded.holding());
            try{enable.invoke(nativeService,next||nativeFilter.get(service)!=null);active=next;}
            catch(Exception error){active=false;log.accept("EDGE_INPUT_ENABLE_FAILED "+error);}
        }
    }

    private void disableAfterFailure(String reason){
        failed=true;active=false;regions=new int[0];cornersActive=false;policy.clear();forwarded.clear();
        log.accept("EDGE_INPUT_FAILED "+reason);main.post(this::sync);
    }
}
