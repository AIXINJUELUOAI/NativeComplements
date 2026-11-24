package dev.local.sunwindowdemo.features.alive;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Sleep-only drawing suppression, not a replacement clock animation. Native progress and alpha stay intact. */
public final class AliveVisualContent {
    private AliveVisualContent() {}
    private static final long FADE_MS=300;

    /** Run the returned refresh after Host readiness/selection/cancellation changes, including when no frame is scheduled. */
    public static Runnable install(XposedModule module,ClassLoader loader,Supplier<String> visual,
            BooleanSupplier fade,Predicate<View> sameRoot,Consumer<String> log) {
        Content content=new Content(module,visual,fade,sameRoot,log);
        try {
            Class<?> clock=type(loader,"com.android.keyguard.ClockEventController");
            Class<?> controller=type(loader,"com.android.systemui.plugins.keyguard.ui.clocks.ClockController");
            Class<?> face=type(loader,"com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController");
            Field preview=field(clock,"isPreview",boolean.class),currentClock=field(clock,"clock",controller);
            Method small=method(controller,"getSmallClock"),large=method(controller,"getLargeClock"),view=method(face,"getView");
            Probe captureClock=(owner,args)->{
                Object ownerClock=currentClock.get(owner);
                if(preview.getBoolean(owner)||ownerClock==null)return;
                View smallView=(View)view.invoke(small.invoke(ownerClock)),largeView=(View)view.invoke(large.invoke(ownerClock));
                if(!content.eligible(smallView)&&!content.eligible(largeView))return;
                content.clockOwner=new WeakReference<>(owner);
                content.capture(0,smallView);content.capture(1,largeView);
            };
            content.after(clockDoze(clock),(owner,args)->captureClock.inspect(args.length==2?args[0]:owner,args));
            content.after(method(clock,"setClock",controller),(owner,args)->{
                if(content.clockOwner.get()==owner)content.clearClock();
                captureClock.inspect(owner,args);
            });
            Class<?> notifications=type(loader,"com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController");
            Field stack=field(notifications,"mView",type(loader,"com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"));
            content.after(method(notifications,"updateAlpha"),(owner,args)->{
                View child=(View)stack.get(owner);
                if(content.eligible(child))content.capture(2,child);
            });

            content.add(method(ViewGroup.class,"drawChild",Canvas.class,View.class,long.class),chain->{
                boolean clip=false;int alpha=255;Canvas canvas=null;int saved=-1;
                if(content.enabled&&Looper.myLooper()==Looper.getMainLooper())try {
                    View child=(View)chain.getArg(1);ViewGroup parent=(ViewGroup)chain.getThisObject();
                    if(content.matches(child,parent)){
                        content.refresh();
                        clip=content.clipping&&content.eligible(child);
                        if(!clip&&content.eligible(child))alpha=content.fadeAlpha(parent);
                    }
                    if(clip){canvas=(Canvas)chain.getArg(0);saved=canvas.save();canvas.clipRect(0,0,0,0);}
                    else if(alpha<255){canvas=(Canvas)chain.getArg(0);saved=canvas.saveLayerAlpha(0,0,parent.getWidth(),parent.getHeight(),alpha);}
                }catch(Throwable failure){
                    if(saved>=0)try{canvas.restoreToCount(saved);}catch(Throwable ignored){}
                    saved=-1;content.fail(failure);
                }
                // Always preserve the real draw result/animation invalidation, including on an empty clip.
                try{return chain.proceed();}finally{
                    if(saved>=0)try{canvas.restoreToCount(saved);}catch(Throwable failure){content.fail(failure);}
                }
            });
            content.enabled=true;content.log("ALIVE_CONTENT_READY sleep-only exact children");
        }catch(Throwable failure){content.fail(failure);}
        return ()->{
            if(Looper.myLooper()!=Looper.getMainLooper()){content.main.post(content::safeRefresh);return;}
            content.safeRefresh();
        };
    }

    private interface Probe {void inspect(Object owner,Object[] args)throws Throwable;}
    private static final class Content {
        final Handler main=new Handler(Looper.getMainLooper());
        final XposedModule module;
        final Supplier<String> visual;
        final BooleanSupplier fade;
        final Predicate<View> sameRoot;
        final Consumer<String> logger;
        long fadeStart,fadeEnd;
        final List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        final List<WeakReference<View>> targets=new ArrayList<>();
        WeakReference<Object> clockOwner=new WeakReference<>(null);
        volatile boolean enabled;
        String current;
        boolean clipping;
        Content(XposedModule module,Supplier<String> visual,BooleanSupplier fade,Predicate<View> sameRoot,Consumer<String> log){
            this.module=module;this.visual=visual;this.fade=fade;this.sameRoot=sameRoot;this.logger=log;
            for(int i=0;i<3;i++)targets.add(new WeakReference<>(null));
        }
        void add(Method method,XposedInterface.Hooker hook)throws Throwable {
            if(!module.deoptimize(method))throw new IllegalStateException("content deoptimize");
            hooks.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hook));
        }
        void after(Method method,Probe probe)throws Throwable {
            add(method,chain->{
                Object result=chain.proceed();
                if(enabled&&Looper.myLooper()==Looper.getMainLooper())try{
                    probe.inspect(chain.getThisObject(),chain.getArgs().toArray());
                    safeRefresh();
                }catch(Throwable failure){fail(failure);}
                return result;
            });
        }
        boolean eligible(View child){
            return child!=null&&child.isAttachedToWindow()&&child.getDisplay()!=null
                    &&child.getDisplay().getDisplayId()==Display.DEFAULT_DISPLAY&&sameRoot.test(child);
        }
        boolean baseParent(View child)throws ReflectiveOperationException {
            if(!(child.getParent() instanceof ViewGroup parent))return false;
            for(Class<?> owner=parent.getClass();owner!=null;owner=owner.getSuperclass())try {
                owner.getDeclaredMethod("drawChild",Canvas.class,View.class,long.class);
                return owner==ViewGroup.class; // An unknown override may bypass base; leave this target completely native.
            }catch(NoSuchMethodException ignored){}
            return false;
        }
        void capture(int index,View child)throws ReflectiveOperationException {
            View old=targets.get(index).get();
            View candidate=eligible(child)&&baseParent(child)?child:null;
            if(old==candidate)return;
            invalidate(old);targets.set(index,new WeakReference<>(candidate));invalidate(candidate);
        }
        void clearClock(){for(int i=0;i<2;i++){invalidate(targets.get(i).get());targets.set(i,new WeakReference<>(null));}}
        boolean matches(View child,ViewGroup parent)throws ReflectiveOperationException {
            for(WeakReference<View> target:targets)if(target.get()==child)return child.getParent()==parent&&baseParent(child);
            return false;
        }
        void refresh(){
            String ready=enabled?visual.get():null;
            boolean next=ready!=null;
            if(next==clipping&&java.util.Objects.equals(current,ready))return;
            String previous=current;current=ready;
            // Bounded sleep release fades back in; wake/cancel/failure returns native output at once.
            long now=SystemClock.uptimeMillis();
            if(clipping&&!next&&fade.getAsBoolean()){fadeStart=now;fadeEnd=now+FADE_MS;}else{fadeStart=fadeEnd=0;}
            clipping=next;
            for(WeakReference<View> target:targets)invalidate(target.get());
            log("ALIVE_CONTENT clip="+clipping+" ep="+(ready==null?previous:ready));
        }
        void safeRefresh(){try{refresh();}catch(Throwable failure){fail(failure);}}
        int fadeAlpha(ViewGroup parent){
            long now=SystemClock.uptimeMillis();
            if(fadeEnd==0||now>=fadeEnd){fadeStart=fadeEnd=0;return 255;}
            parent.postInvalidateOnAnimation();
            return Math.max(0,Math.min(255,(int)(255*(now-fadeStart)/FADE_MS)));
        }
        void fail(Throwable failure){
            enabled=false;clipping=false;fadeStart=fadeEnd=0;
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            hooks.clear();for(WeakReference<View> target:targets)invalidate(target.get());
            log("ALIVE_CONTENT_UNAVAILABLE "+failure.getClass().getSimpleName());
        }
        void invalidate(View child){
            if(child==null)return;
            try{
                child.invalidate();
                if(child.getParent() instanceof View parent)parent.invalidate();
            }catch(Throwable ignored){/* Failure cleanup cannot escape into native drawing or Host cancellation. */}
        }
        void log(String message){try{logger.accept(message);}catch(Throwable ignored){}}
    }
    private static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> owner,String name,Class<?> expected)throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);if(field.getType()!=expected)throw new NoSuchFieldException(name);
        field.setAccessible(true);return field;
    }
    private static Method method(Class<?> owner,String name,Class<?>...args)throws ReflectiveOperationException {
        Method method=owner.getDeclaredMethod(name,args);method.setAccessible(true);return method;
    }
    /**
     * Clock doze entry: the static R8 bridge {@code access$handleDoze(controller,amount)} on older
     * SystemUI, or QPR2's instance {@code handleDoze(amount)} once the bridge is inlined away.
     * Two arguments therefore mean (controller, amount); one means receiver + amount.
     */
    static Method clockDoze(Class<?> clock)throws ReflectiveOperationException {
        Method doze;
        try{doze=method(clock,"access$handleDoze",clock,float.class);}
        catch(NoSuchMethodException inlined){doze=method(clock,"handleDoze",float.class);}
        boolean bridge=doze.getParameterCount()==2;
        if(java.lang.reflect.Modifier.isStatic(doze.getModifiers())!=bridge||doze.getReturnType()!=void.class)
            throw new NoSuchMethodException("ClockEventController handleDoze shape");
        return doze;
    }
}
