package dev.local.sunwindowdemo.features.shade;

import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** A user multiplier over the current native scale, never a replacement sizing algorithm. */
public final class StatusIconScale {
    private static volatile int notifications=100,system=100;
    private static final List<WeakReference<ImageView>> views=new ArrayList<>();
    private StatusIconScale() {}
    static boolean systemSlot(String slot){
        if(slot==null)return false;
        return switch(slot){
            case "alarm_clock","bluetooth","hotspot","volume","zen","cast","location","headset","tty","vpn","nfc","managed_profile","rotate","data_saver","sensors_off" -> true;
            default -> false;
        };
    }
    public static void install(XposedModule module,ClassLoader loader)throws ReflectiveOperationException {
        Class<?> type=Class.forName("com.android.systemui.statusbar.StatusBarIconView",false,loader);
        Field notification=field(type,"mNotification"),bundle=field(type,"mBundleEntry"),slot=field(type,"mSlot"),scale=field(type,"mIconScale");
        module.hook(type.getDeclaredMethod("onDraw",android.graphics.Canvas.class))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                ImageView view=(ImageView)chain.getThisObject();
                if(bundle.get(view)!=null)return chain.proceed();
                boolean notice=notification.get(view)!=null;
                if(!notice&&!systemSlot((String)slot.get(view)))return chain.proceed();
                remember(view);
                int percent=notice?notifications:system;
                if(percent==100)return chain.proceed(); // Bit-for-bit native geometry at the default.
                float original=scale.getFloat(view);
                float adjusted=original*multiplier(view,original,percent);
                try{scale.setFloat(view,adjusted);return chain.proceed();}
                finally{scale.setFloat(view,original);} // Preserve native animation, pivots and dot drawing.
            });
    }
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void remember(ImageView view){
        for(Iterator<WeakReference<ImageView>> it=views.iterator();it.hasNext();){
            ImageView old=it.next().get();if(old==null)it.remove();else if(old==view)return;
        }
        views.add(new WeakReference<>(view));
    }
    static void set(int notificationPercent,int systemPercent){
        notifications=Math.max(80,Math.min(120,notificationPercent));
        system=Math.max(80,Math.min(120,systemPercent));
        for(Iterator<WeakReference<ImageView>> it=views.iterator();it.hasNext();){
            ImageView view=it.next().get();if(view==null)it.remove();else view.invalidate();
        }
    }
    private static float multiplier(ImageView view,float nativeScale,int percent){
        Drawable drawable=view.getDrawable();
        if(drawable==null)return 1;
        RectF bounds=new RectF(drawable.getBounds());
        view.getImageMatrix().mapRect(bounds);
        return multiplier(percent,bounds.width()*nativeScale,bounds.height()*nativeScale,
            view.getWidth()-view.getPaddingLeft()-view.getPaddingRight(),
            view.getHeight()-view.getPaddingTop()-view.getPaddingBottom());
    }
    static float multiplier(int percent,float nativeWidth,float nativeHeight,int width,int height){
        float requested=Math.max(80,Math.min(120,percent))/100f;
        if(requested<=1)return requested;
        if(!Float.isFinite(nativeWidth)||!Float.isFinite(nativeHeight)||nativeWidth<=0||nativeHeight<=0||width<=0||height<=0)return 1;
        float room=Math.min(width/nativeWidth,height/nativeHeight);
        // Never worsen an existing native clip; enlargement stops at the current content viewport.
        return Math.min(requested,Math.max(1,room));
    }
}
