package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Gravity;
import android.view.RoundedCorner;
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
    static final float THRESHOLD=0.4f;
    private static volatile IBinder sink;
    private static Consumer<String> log=ignored->{};
    private static WeakReference<Object> owner=new WeakReference<>(null);
    private static float progress;
    private static boolean armed;
    private static WeakReference<TextView> hint=new WeakReference<>(null);

    private SwipeMini(){}

    /** SystemUI publishes the channel only while the feature is enabled; null disables. */
    static void configure(IBinder channel){
        sink=channel!=null&&channel.isBinderAlive()?channel:null;
        if(sink==null)reset(null);
    }

    record Targets(Method displacement,Method endTarget,Method haptic,Field recents,Field gesture,Field context,
                   Method runningIds,Method nativeEnd,Method runningView,Method thumbnail,Object home,Object recentsTarget){}

    static Targets resolve(ClassLoader loader)throws Exception{
        Class<?> logic=Class.forName("com.android.quickstep.SwipeUpAnimationLogic",false,loader);
        Class<?> handler=Class.forName("com.android.quickstep.AbsSwipeUpHandler",false,loader);
        Class<?> state=Class.forName("com.android.quickstep.GestureState",false,loader);
        Class<?> target=Class.forName("com.android.quickstep.GestureState$GestureEndTarget",false,loader);
        Class<?> recentsView=Class.forName("com.android.quickstep.views.RecentsView",false,loader);
        Class<?> taskView=Class.forName("com.android.quickstep.views.TaskView",false,loader);
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
        Method runningView=exact(recentsView.getDeclaredMethod("getRunningTaskView"),taskView);
        Method thumbnail=exact(taskView.getDeclaredMethod("getThumbnailBounds",Rect.class,boolean.class),void.class);
        Object home=null,recentsTarget=null;
        for(Object value:target.getEnumConstants()){
            String name=((Enum<?>)value).name();
            if(name.equals("HOME"))home=value;else if(name.equals("RECENTS"))recentsTarget=value;
        }
        if(home==null||recentsTarget==null)throw new NoSuchFieldException("GestureEndTarget");
        return new Targets(displacement,endTarget,haptic,recents,gesture,context,runningIds,nativeEnd,runningView,thumbnail,home,recentsTarget);
    }

    static void install(XposedModule module,ClassLoader loader,Consumer<String> logger){
        log=logger;
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Targets t=resolve(loader);
            hooks.add(module.hook(t.displacement()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                Object handler=chain.getThisObject();
                if(sink!=null&&t.recents().getDeclaringClass().isInstance(handler))
                    track(t,handler,(Float)chain.getArg(0));
                return result;
            }));
            hooks.add(module.hook(t.endTarget()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                Object handler=chain.getThisObject();
                boolean selected;
                synchronized(SwipeMini.class){selected=armed&&owner.get()==handler;}
                reset(handler);
                if(!selected||(result!=t.home()&&result!=t.recentsTarget()))return result;
                return handoff(t,handler)?t.home():result;
            }));
            log.accept("SWIPE_MINI_READY hooks=2 endTarget="+t.endTarget().getParameterCount());
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
            if(fresh){owner=new WeakReference<>(handler);armed=false;}
            progress=-displacement/height;
            was=armed;now=progress>=THRESHOLD;armed=now;
        }
        if(was==now&&!(fresh&&!now))return;
        if(now)t.haptic().invoke(handler);
        View anchor=(View)t.recents().get(handler);
        showHint(anchor,now);
    }

    private static boolean handoff(Targets t,Object handler){
        IBinder channel=sink;
        if(channel==null)return false;
        try{
            int[] ids=(int[])t.runningIds().invoke(t.gesture().get(handler),false);
            if(ids==null||ids.length!=1||ids[0]<=0)return false; // Split pairs keep native behavior.
            View recents=(View)t.recents().get(handler);
            Object view=recents==null?null:t.runningView().invoke(recents);
            if(!(view instanceof View card))return false;
            Rect bounds=new Rect();
            t.thumbnail().invoke(card,bounds,true); // Relative to Launcher's DragLayer.
            View layer=dragLayer(card);
            int[] origin=new int[2];if(layer!=null)layer.getLocationOnScreen(origin);
            RectF screen=new RectF(bounds);screen.offset(origin[0],origin[1]);
            if(screen.isEmpty())return false;
            float radius=cardRadius(card,screen);
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

    /** The card's visible corner scales with it; start from the display corner at that scale. */
    private static float cardRadius(View card,RectF screen){
        WindowInsets insets=card.getRootWindowInsets();
        RoundedCorner corner=insets==null?null:insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
        int width=card.getResources().getDisplayMetrics().widthPixels;
        float display=corner==null?0:corner.getRadius();
        return width<=0?0:display*screen.width()/width;
    }

    private static View dragLayer(View view){
        for(Object v=view;v instanceof View current;v=current.getParent())
            if(current.getClass().getName().endsWith(".DragLayer"))return current;
        return null;
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
        if(!show){if(view!=null)hide(view);return;}
        View root=anchor.getRootView();
        if(!(root instanceof ViewGroup group))return;
        if(view==null||view.getParent()!=group){
            if(view!=null&&view.getParent() instanceof ViewGroup old)old.removeView(view);
            view=new TextView(anchor.getContext());
            float density=anchor.getResources().getDisplayMetrics().density;
            view.setText("松手挂起为迷你窗");
            view.setTextColor(Color.WHITE);view.setTextSize(14);
            int h=Math.round(16*density),v=Math.round(9*density);view.setPadding(h,v,h,v);
            GradientDrawable pill=new GradientDrawable();pill.setColor(0xCC202124);pill.setCornerRadius(24*density);
            view.setBackground(pill);view.setAlpha(0);
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

    private static Method exact(Method m,Class<?> returnType)throws NoSuchMethodException{
        if(m.getReturnType()!=returnType||Modifier.isStatic(m.getModifiers()))throw new NoSuchMethodException(m.getName()+" signature");
        m.setAccessible(true);return m;
    }
    private static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field f=owner.getDeclaredField(name);
        if(f.getType()!=type||Modifier.isStatic(f.getModifiers()))throw new NoSuchFieldException(name+" type");
        f.setAccessible(true);return f;
    }
}
