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
    public static void install(XposedModule module,ClassLoader loader)throws ReflectiveOperationException {
        Class<?> type=Class.forName("com.android.systemui.statusbar.StatusBarIconView",false,loader);
        Field notification=field(type,"mNotification"),bundle=field(type,"mBundleEntry"),slot=field(type,"mSlot"),scale=field(type,"mIconScale");
        module.hook(type.getDeclaredMethod("onDraw",android.graphics.Canvas.class))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                ImageView view=(ImageView)chain.getThisObject();
                if(bundle.get(view)!=null)return chain.proceed();
                boolean notice=notification.get(view)!=null;
                if(!notice&&!ShadePolicy.systemSlot((String)slot.get(view)))return chain.proceed();
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
        notifications=ShadePolicy.iconScale(notificationPercent);
        system=ShadePolicy.iconScale(systemPercent);
        for(Iterator<WeakReference<ImageView>> it=views.iterator();it.hasNext();){
            ImageView view=it.next().get();if(view==null)it.remove();else view.invalidate();
        }
    }
    private static float multiplier(ImageView view,float nativeScale,int percent){
        Drawable drawable=view.getDrawable();
        if(drawable==null)return 1;
        RectF bounds=new RectF(drawable.getBounds());
        view.getImageMatrix().mapRect(bounds);
        return ShadePolicy.multiplier(percent,bounds.width()*nativeScale,bounds.height()*nativeScale,
            view.getWidth()-view.getPaddingLeft()-view.getPaddingRight(),
            view.getHeight()-view.getPaddingTop()-view.getPaddingBottom());
    }
}
