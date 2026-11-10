package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Use Shell's native composition sampler; no screen capture or bitmap polling. */
final class HandleContrast {
    private static final Executor worker=Executors.newSingleThreadExecutor(r->new Thread(r,"window-handle-sampling"));
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Rect region=new Rect();
    private final Consumer<Boolean> changed;
    private Object helper;
    private Method start,stop,update;
    private Field requestBounds;
    private boolean running,closed;
    private Boolean pending,sent;
    private final Runnable publish=this::publish;
    private void publish(){if(!closed&&running&&pending!=null&&!pending.equals(sent)){sent=pending;changed.accept(sent);}}
    HandleContrast(View view,Consumer<Boolean> changed){
        this.changed=changed;
        try{
            ClassLoader loader=view.getContext().getClassLoader();
            Class<?> type=Class.forName("com.android.wm.shell.shared.handles.RegionSamplingHelper",false,loader);
            Class<?> callback=Class.forName(type.getName()+"$SamplingCallback",false,loader);
            start=type.getMethod("start",Rect.class);stop=type.getMethod("stop");update=type.getMethod("updateSamplingListener");
            requestBounds=type.getField("mSamplingRequestBounds");
            Object proxy=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(p,m,args)->switch(m.getName()){
                case "getSampledRegion" -> new Rect(region);
                case "isSamplingEnabled" -> !closed;
                case "onRegionDarknessChanged" -> {sample((Boolean)args[0]);yield null;}
                case "toString" -> "WindowHandleSampling";
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p==args[0];
                default -> null;
            });
            helper=type.getConstructor(View.class,callback,Executor.class,Executor.class).newInstance(view,proxy,(Executor)main::post,worker);
            type.getField("mWindowVisible").setBoolean(helper,true);
        }catch(Throwable unavailable){android.util.Log.w("NativeComplements","HANDLE_SAMPLING_UNAVAILABLE "+unavailable.getClass().getSimpleName());helper=null;}
    }
    private void sample(boolean dark){
        if(closed||!running||Boolean.valueOf(dark).equals(pending))return;
        pending=dark;main.removeCallbacks(publish);main.postDelayed(publish,180);
    }
    void update(Rect bounds,boolean enabled){
        if(helper==null||closed)return;
        try{
            if(!enabled||bounds==null||bounds.isEmpty()){
                if(running)stop.invoke(helper);running=false;pending=null;main.removeCallbacks(publish);return;
            }
            boolean moved=!region.equals(bounds);region.set(bounds);
            if(!running){running=true;sent=null;start.invoke(helper,new Rect(region));}
            else if(moved){((Rect)requestBounds.get(helper)).set(region);update.invoke(helper);}
        }catch(Throwable failure){close();}
    }
    void close(){
        closed=true;main.removeCallbacksAndMessages(null);
        if(helper!=null)try{stop.invoke(helper);}catch(Exception ignored){}
        helper=null;running=false;
        // The native helper destroys its listener when this view detaches.
    }
}
