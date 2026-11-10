package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceControl;

/** A temporary sibling surface. App buffers and lifecycle continue underneath it. */
final class TaskCover {
    final TaskVisibilityGuard mask;
    private final FrameworkAccess a;
    private final Object task,parent;
    private SurfaceControl background,icon;
    private final RectF from,to;
    private final float radiusFrom;
    private float radiusTo;
    private final int iconSize;
    private final float splashPx;
    private final float[] color;
    private long began=SystemClock.elapsedRealtime(),notBefore;
    private final long duration,fadeDuration;
    private long entryAt=-1,fadeAt=-1,hiddenAt=-1;
    private boolean closed,exiting;
    private float ownerAlpha;
    private final boolean fadeIn;
    TaskCover(FrameworkAccess a,Context context,Object task,Object parent,String pkg,
            RectF from,RectF to,float radiusFrom,float radiusTo,float ownerAlpha,boolean fadeIn)throws Exception{
        this.a=a;this.task=task;this.parent=parent;this.from=new RectF(from);this.to=new RectF(to);
        this.radiusFrom=radiusFrom;this.radiusTo=radiusTo;this.ownerAlpha=ownerAlpha;this.fadeIn=fadeIn;
        float density=context.getResources().getDisplayMetrics().density;
        float shade=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES?.09f:.94f;
        color=splashColor(context,pkg,shade);
        splashPx=288*density;
        iconSize=Math.max(48,Math.min(512,Math.round(splashPx)));
        float animationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        duration=Math.max(0,Math.round(320*animationScale));fadeDuration=Math.max(0,Math.round(150*animationScale));
        mask=new TaskVisibilityGuard(a,task);
        try{
            background=build("NativeComplements-cover",0);
            icon=build("NativeComplements-cover-icon",iconSize);
            Drawable drawable=dev.local.sunwindowdemo.features.iconlayers.IconAssets.colored(context,pkg);
            if(drawable==null){
                try{drawable=context.getPackageManager().getApplicationIcon(pkg);}
                catch(android.content.pm.PackageManager.NameNotFoundException missing){drawable=context.getPackageManager().getDefaultActivityIcon();}
            }
            Surface canvasSurface=new Surface(icon);
            try{
                Canvas canvas=canvasSurface.lockCanvas(null);
                try{canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);drawable.setBounds(0,0,iconSize,iconSize);drawable.draw(canvas);}
                finally{canvasSurface.unlockCanvasAndPost(canvas);}
            }finally{canvasSurface.release();}
            paint(0,fadeIn?0:1);mask.hide();
        }catch(Throwable error){close();throw error;}
    }
    /** Same source as the native splash: the application theme's splash background, then its window background. */
    private static float[] splashColor(Context context,String pkg,float fallback){
        try{
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(pkg,0);
            Context owner=context.createPackageContext(pkg,0);
            android.content.res.Resources.Theme theme=owner.getResources().newTheme();
            theme.applyStyle(info.theme!=0?info.theme:android.R.style.Theme_DeviceDefault_DayNight,true);
            android.util.TypedValue value=new android.util.TypedValue();
            int[] attrs={android.R.attr.windowSplashScreenBackground,android.R.attr.windowBackground};
            for(int attr:attrs){
                if(theme.resolveAttribute(attr,value,true)&&value.type>=android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    &&value.type<=android.util.TypedValue.TYPE_LAST_COLOR_INT){
                    int c=value.data;if(Color.alpha(c)==255)return new float[]{Color.red(c)/255f,Color.green(c)/255f,Color.blue(c)/255f};
                }
            }
        }catch(Exception ignored){}
        return new float[]{fallback,fallback,fallback};
    }
    private SurfaceControl build(String name,int size)throws Exception{
        Object b=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
        a.call(b,"setName",new Class<?>[]{String.class},name);
        a.call(b,"setParent",new Class<?>[]{SurfaceControl.class},a.field(parent,"mSurfaceControl"));
        a.call(b,"setHidden",new Class<?>[]{boolean.class},true);
        if(size==0)a.call(b,"setColorLayer");
        else{
            a.call(b,"setBufferSize",new Class<?>[]{int.class,int.class},size,size);
            a.call(b,"setFormat",new Class<?>[]{int.class},PixelFormat.TRANSLUCENT);
        }
        return (SurfaceControl)a.call(b,"build");
    }
    void ownerAlpha(float alpha){ownerAlpha=alpha;}
    void target(RectF bounds){if(!exiting)to.set(bounds);}
    void resumeTo(RectF bounds,float radius){to.set(bounds);radiusTo=radius;began=SystemClock.elapsedRealtime();}
    void delayEntry(long delay){notBefore=SystemClock.elapsedRealtime()+Math.max(0,delay);}
    boolean exiting(){return exiting;}
    long exit(){
        if(exiting)return 0;
        exiting=true;ownerAlpha=0;began=SystemClock.elapsedRealtime();
        from.set(to);float dx=from.width()*.025f,dy=from.height()*.025f;to.inset(dx,dy);
        return fadeDuration;
    }
    boolean step(boolean ready,boolean visible)throws Exception{
        if(closed)return false;
        long now=SystemClock.elapsedRealtime();
        if(!visible){
            if(hiddenAt<0)hiddenAt=now;
            if(mask.holding())mask.hold();
            paint(1,0);return true;
        }
        if(hiddenAt>=0){
            long paused=now-hiddenAt;
            began+=paused;if(entryAt>=0)entryAt+=paused;if(fadeAt>=0)fadeAt+=paused;
            if(notBefore>0)notBefore+=paused;
            hiddenAt=-1;
        }
        if(exiting){
            float q=fadeDuration==0?1:Math.min(1,(now-began)/(float)fadeDuration);
            if(q>=1){close();return false;}
            if(mask.holding())mask.hold();paint(1-(float)Math.pow(1-q,3),visible?1-q:0);return true;
        }
        if(now-began>=5000)throw new IllegalStateException("cover target frame timeout");
        if(fadeIn&&visible&&entryAt<0&&now>=notBefore)entryAt=now;
        long elapsed=fadeIn?(entryAt<0?0:now-entryAt):now-began;
        float p=duration==0?1:Math.min(1,elapsed/(float)duration);
        if(ready&&p==1&&fadeAt<0){mask.release(ownerAlpha);fadeAt=now;}
        if(fadeAt>=0&&now-fadeAt>=fadeDuration){close();return false;}
        if(mask.holding())mask.hold();
        float alpha=fadeAt<0?(fadeIn&&fadeDuration>0?Math.min(1,elapsed/(float)fadeDuration):1):1-(now-fadeAt)/(float)fadeDuration;
        paint(1-(float)Math.pow(1-p,3),visible?alpha:0);
        return true;
    }
    private void paint(float p,float alpha)throws Exception{
        float x=from.left+(to.left-from.left)*p,y=from.top+(to.top-from.top)*p;
        float w=Math.max(1,from.width()+(to.width()-from.width())*p),h=Math.max(1,from.height()+(to.height()-from.height())*p);
        float size=Math.min(splashPx,Math.min(w,h)*.7f)/iconSize;
        Rect pb=(Rect)a.call(parent,"getBounds");
        SurfaceControl anchor=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(anchor==null||!anchor.isValid()||background==null||!background.isValid()||icon==null||!icon.isValid())throw new IllegalStateException("cover surface detached");
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},background,anchor,1);
            a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},icon,background,1);
            a.call(tx,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},background,color);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},background,x-pb.left,y-pb.top);
            a.call(tx,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},background,Math.round(w),Math.round(h));
            a.call(tx,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},background,Math.min(Math.min(w,h)/2,radiusFrom+(radiusTo-radiusFrom)*p));
            a.call(tx,"setScale",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,size,size);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,x-pb.left+(w-iconSize*size)/2,y-pb.top+(h-iconSize*size)/2);
            if(mask.holding())mask.inTransaction(tx,null);
            tx.setAlpha(background,alpha).setAlpha(icon,alpha);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},background);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},icon);
            tx.apply();
        }
    }
    void close(){
        if(closed)return;closed=true;
        try{mask.release(ownerAlpha);}catch(Exception ignored){}
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            if(icon!=null&&icon.isValid())a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},icon);
            if(background!=null&&background.isValid())a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},background);
            tx.apply();
        }catch(Exception ignored){}
        finally{if(icon!=null){icon.release();icon=null;}if(background!=null){background.release();background=null;}}
    }
}
