package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Launcher swipe-up to mini window. Native Quickstep keeps the gesture; this only reads the finger
 * travel and, on release past the threshold, asks SystemUI to mask the card and land it as the mini
 * window. Only an accepted handoff changes the native end target, and only to HOME.
 */
final class SwipeMini {
    static final String CHANNEL=Wire.PKG+".swipeMini.v1";
    /** Finger travel as a fraction of display height. */
    static final float THRESHOLD=1f/3f;
    private static volatile IBinder sink;
    private static volatile boolean showHint=true;
    private static Consumer<String> log=ignored->{};
    private static WeakReference<Object> owner=new WeakReference<>(null);
    private static WeakReference<Object> observed=new WeakReference<>(null);
    private static float progress;
    private static boolean armed;
    private static WeakReference<TextView> hint=new WeakReference<>(null);
    private static final java.util.Set<Object> acceptedHandlers=java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    static void acceptHandler(Object handler){acceptedHandlers.add(handler);}
    static void finishHandler(Object handler){acceptedHandlers.remove(handler);}
    static Object workspaceTarget(Object handler,Object nativeTarget){
        if(!acceptedHandlers.contains(handler))return nativeTarget;
        // Use the native no-workspace-icon branch: no FloatingIcon/Widget or copied TaskView
        // is created, while normal HOME state/controller completion still runs.
        log.accept("SWIPE_MINI_HOME_FALLBACK hadTarget="+(nativeTarget!=null));
        return null;
    }

    private SwipeMini(){}

    /** SystemUI publishes the channel only while the feature is enabled; null disables. */
    static void configure(IBinder channel,boolean hintEnabled){
        boolean previouslyConnected=sink!=null,previousHint=showHint;
        showHint=hintEnabled;
        sink=channel!=null&&channel.isBinderAlive()?channel:null;
        if(previouslyConnected!=(sink!=null)||previousHint!=hintEnabled)
            log.accept("SWIPE_MINI_CHANNEL connected="+(sink!=null)+" hint="+hintEnabled);
        if(sink==null){reset(null);acceptedHandlers.clear();}
        else if(!hintEnabled){TextView view=hint.get();if(view!=null)view.post(()->hide(view));}
    }

    record Targets(Method displacement,Method endTarget,Method haptic,Field recents,Field gesture,Field context,
                   Method runningIds,Method nativeEnd,Field handles,Method simulator,Method currentRect,Method currentRadius,
                   Method transformParams,Method targetAlpha,Method workspaceView,Method invalidate,Method invalidateLauncher,
                   Object home,Object recentsTarget){}
    /** Live-tile params of an accepted handoff: the system mask owns the visuals, so the native
     *  home fly-out (which shrinks toward the centre when no icon exists) stays invisible. */
    private static final java.util.Set<Object> hiddenTiles=java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    static Targets resolve(ClassLoader loader)throws Exception{
        Class<?> logic=Class.forName("com.android.quickstep.SwipeUpAnimationLogic",false,loader);
        Class<?> handler=Class.forName("com.android.quickstep.AbsSwipeUpHandler",false,loader);
        Class<?> state=Class.forName("com.android.quickstep.GestureState",false,loader);
        Class<?> target=Class.forName("com.android.quickstep.GestureState$GestureEndTarget",false,loader);
        Class<?> recentsView=Class.forName("com.android.quickstep.views.RecentsView",false,loader);
        Class<?> handle=Class.forName("com.android.quickstep.RemoteTargetGluer$RemoteTargetHandle",false,loader);
        Class<?> simulatorType=Class.forName("com.android.quickstep.util.TaskViewSimulator",false,loader);
        if(!logic.isAssignableFrom(handler)||!target.isEnum())throw new NoSuchMethodException("swipe handler hierarchy");
        Method displacement=exact(logic.getDeclaredMethod("updateDisplacement",float.class),void.class);
        // dada: (PointF,float,boolean,boolean,boolean); PixelUI QPR2 appends a float. Accept exactly one.
        Method endTarget=null;
        for(Method m:handler.getDeclaredMethods())
            if(m.getName().equals("calculateEndTarget")&&m.getReturnType()==target&&!Modifier.isStatic(m.getModifiers())){
                if(endTarget!=null)throw new NoSuchMethodException("ambiguous calculateEndTarget");
                endTarget=m;
            }
        if(endTarget==null)throw new NoSuchMethodException("calculateEndTarget");
        endTarget.setAccessible(true);
        Method haptic=exact(handler.getDeclaredMethod("performHapticFeedback"),void.class);
        Field recents=field(handler,"mRecentsView",recentsView),gesture=field(logic,"mGestureState",state),context=field(logic,"mContext",Context.class);
        Method runningIds=exact(state.getDeclaredMethod("getRunningTaskIds",boolean.class),int[].class);
        Method nativeEnd=exact(state.getDeclaredMethod("getEndTarget"),target);
        // The live tile is a transformed surface, not the overview TaskView: read its simulator pose.
        Field handles=field(logic,"mRemoteTargetHandles",java.lang.reflect.Array.newInstance(handle,0).getClass());
        Method simulator=exact(handle.getDeclaredMethod("getTaskViewSimulator"),simulatorType);
        Method currentRect=exact(simulatorType.getDeclaredMethod("getCurrentRect"),RectF.class);
        Method currentRadius=exact(simulatorType.getDeclaredMethod("getCurrentCornerRadius"),float.class);
        Class<?> paramsType=Class.forName("com.android.quickstep.util.TransformParams",false,loader);
        Method transformParams=exact(handle.getDeclaredMethod("getTransformParams"),paramsType);
        Method targetAlpha=exact(paramsType.getDeclaredMethod("setTargetAlpha",float.class),paramsType);
        Class<?> taskType=Class.forName("com.android.quickstep.views.TaskView",false,loader);
        Class<?> launcherHandler=Class.forName("com.android.quickstep.LauncherSwipeHandlerV2",false,loader);
        if(!handler.isAssignableFrom(launcherHandler))throw new NoSuchMethodException("launcher handler hierarchy");
        Method workspaceView=exact(launcherHandler.getDeclaredMethod("findWorkspaceView",List.class,taskType),View.class);
        Method invalidate=exact(handler.getDeclaredMethod("invalidateHandler"),void.class);
        Method invalidateLauncher=exact(handler.getDeclaredMethod("invalidateHandlerWithLauncher"),void.class);
        Object home=null,recentsTarget=null;
        for(Object value:target.getEnumConstants()){
            String name=((Enum<?>)value).name();
            if(name.equals("HOME"))home=value;else if(name.equals("RECENTS"))recentsTarget=value;
        }
        if(home==null||recentsTarget==null)throw new NoSuchFieldException("GestureEndTarget");
        return new Targets(displacement,endTarget,haptic,recents,gesture,context,runningIds,nativeEnd,handles,simulator,currentRect,currentRadius,
            transformParams,targetAlpha,workspaceView,invalidate,invalidateLauncher,home,recentsTarget);
    }

    static void install(XposedModule module,ClassLoader loader,Consumer<String> logger){
        log=message->{logger.accept(message);android.util.Log.i("SwipeMini",message);};
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Targets t=resolve(loader);
            hooks.add(module.hook(t.displacement()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                Object handler=chain.getThisObject();
                if(observed.get()!=handler){
                    observed=new WeakReference<>(handler);
                    log.accept("SWIPE_MINI_TRACK connected="+(sink!=null)+" handler="+handler.getClass().getName());
                }
                if(sink!=null&&t.recents().getDeclaringClass().isInstance(handler))
                    track(t,handler,(Float)chain.getArg(0));
                return result;
            }));
            hooks.add(module.hook(t.endTarget()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                Object handler=chain.getThisObject();
                boolean selected,tracked;float releaseProgress;
                synchronized(SwipeMini.class){tracked=owner.get()==handler;selected=armed&&tracked;releaseProgress=progress;}
                log.accept("SWIPE_MINI_RELEASE tracked="+tracked+" armed="+selected
                    +" progress="+releaseProgress+" target="+result+" connected="+(sink!=null));
                reset(handler);
                if(!selected||(result!=t.home()&&result!=t.recentsTarget()))return result;
                if(!handoff(t,handler))return result;
                hideLiveTile(t,handler);
                acceptHandler(handler);
                return t.home();
            }));
            hooks.add(module.hook(t.targetAlpha()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->
                hiddenTiles.contains(chain.getThisObject())?chain.proceed(new Object[]{0f}):chain.proceed()));
            hooks.add(module.hook(t.workspaceView()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->
                workspaceTarget(chain.getThisObject(),chain.proceed())));
            for(Method finish:new Method[]{t.invalidate(),t.invalidateLauncher()})
                hooks.add(module.hook(finish).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    try{return chain.proceed();}finally{finishHandler(chain.getThisObject());}
                }));
            log.accept("SWIPE_MINI_READY hooks=6 endTarget="+t.endTarget().getParameterCount());
        }catch(Throwable failure){
            for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}
            log.accept("SWIPE_MINI_DISABLED "+failure.getClass().getSimpleName()+": "+failure.getMessage());
        }
    }

    private static void track(Targets t,Object handler,float displacement)throws Exception{
        Context c=(Context)t.context().get(handler);
        int height=c.getResources().getDisplayMetrics().heightPixels;
        if(height<=0)return;
        boolean was,now,fresh;
        synchronized(SwipeMini.class){
            fresh=owner.get()!=handler;
            if(fresh){owner=new WeakReference<>(handler);armed=false;hiddenTiles.clear();}
            progress=-displacement/height;
            was=armed;now=progress>THRESHOLD;armed=now;
        }
        if(was==now&&!(fresh&&!now))return;
        log.accept("SWIPE_MINI_THRESHOLD armed="+now+" displacement="+displacement+" height="+height);
        if(now)t.haptic().invoke(handler);
        View anchor=(View)t.recents().get(handler);
        showHint(anchor,now);
    }

    private static boolean handoff(Targets t,Object handler){
        IBinder channel=sink;
        if(channel==null)return false;
        try{
            int[] ids=(int[])t.runningIds().invoke(t.gesture().get(handler),false);
            if(ids==null||ids.length!=1||ids[0]<=0)return reject("task-ids"); // Split pairs keep native behavior.
            Object[] remote=(Object[])t.handles().get(handler);
            if(remote==null||remote.length!=1||remote[0]==null)return reject("remote-handles");
            Object simulator=t.simulator().invoke(remote[0]);
            if(simulator==null)return reject("simulator");
            // Display-space pose of the live tile the user is holding at release.
            RectF screen=new RectF((RectF)t.currentRect().invoke(simulator));
            float radius=(Float)t.currentRadius().invoke(simulator);
            if(screen.isEmpty()||!Float.isFinite(radius))return reject("pose");
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken(CHANNEL);data.writeInt(ids[0]);data.writeTypedObject(screen,0);data.writeFloat(radius);
                if(!channel.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))return false;
                reply.readException();
                boolean accepted=reply.readBoolean();
                log.accept("SWIPE_MINI_HANDOFF task="+ids[0]+" accepted="+accepted);
                return accepted;
            }finally{data.recycle();reply.recycle();}
        }catch(Throwable failure){
            log.accept("SWIPE_MINI_HANDOFF_FAILED "+failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean reject(String reason){log.accept("SWIPE_MINI_REJECT reason="+reason);return false;}

    private static void hideLiveTile(Targets t,Object handler){
        try{
            for(Object remote:(Object[])t.handles().get(handler)){
                Object params=remote==null?null:t.transformParams().invoke(remote);
                if(params==null)continue;
                hiddenTiles.add(params);
                t.targetAlpha().invoke(params,0f);
            }
        }catch(Throwable failure){
            // The system mask still hides the task surface; this only removes the duplicate launcher pose.
            log.accept("SWIPE_MINI_TILE_UNHIDDEN "+failure.getClass().getSimpleName());
        }
    }

    private static void reset(Object handler){
        synchronized(SwipeMini.class){
            if(handler!=null&&owner.get()!=handler)return;
            armed=false;progress=0;owner=new WeakReference<>(null);
        }
        TextView view=hint.get();
        if(view!=null)view.post(()->hide(view));
    }

    private static void showHint(View anchor,boolean show){
        if(anchor==null)return;
        TextView view=hint.get();
        if(!show||!showHint){if(view!=null)hide(view);return;}
        View root=anchor.getRootView();
        if(!(root instanceof ViewGroup group))return;
        if(view==null||view.getParent()!=group){
            if(view!=null&&view.getParent() instanceof ViewGroup old)old.removeView(view);
            view=new TextView(anchor.getContext());
            float density=anchor.getResources().getDisplayMetrics().density;
            view.setText("松手挂起小窗");
            // No background: a soft shadow keeps white text legible over app content or wallpaper.
            view.setTextColor(Color.WHITE);view.setTextSize(15);
            view.setShadowLayer(6*density,0,density,0x99000000);
            view.setAlpha(0);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
            WindowInsets insets=group.getRootWindowInsets();
            lp.topMargin=(insets==null?0:insets.getInsets(WindowInsets.Type.statusBars()).top)+Math.round(24*density);
            group.addView(view,lp);
            hint=new WeakReference<>(view);
        }
        view.animate().cancel();
        view.setScaleX(.9f);view.setScaleY(.9f);
        view.animate().alpha(1).scaleX(1).scaleY(1).setDuration(160).start();
    }

    private static void hide(TextView view){
        view.animate().cancel();
        view.animate().alpha(0).setDuration(140).withEndAction(()->{
            if(view.getAlpha()==0&&view.getParent() instanceof ViewGroup parent)parent.removeView(view);
        }).start();
    }

    static Method exact(Method m,Class<?> returnType)throws NoSuchMethodException{
        if(m.getReturnType()!=returnType||Modifier.isStatic(m.getModifiers()))throw new NoSuchMethodException(m.getName()+" signature");
        m.setAccessible(true);return m;
    }
    static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field f=owner.getDeclaredField(name);
        if(f.getType()!=type||Modifier.isStatic(f.getModifiers()))throw new NoSuchFieldException(name+" type");
        f.setAccessible(true);return f;
    }
}
