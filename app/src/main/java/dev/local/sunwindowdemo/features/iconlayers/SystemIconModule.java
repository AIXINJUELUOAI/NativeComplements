package dev.local.sunwindowdemo.features.iconlayers;

import android.app.Application;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.View;
import io.github.libxposed.api.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Only the already-scoped SystemUI process: notification identity glyphs and system splash artwork. */
public final class SystemIconModule extends XposedModule {
    private boolean eligible,installed;
    private final List<WeakReference<Object>> notificationViews=new ArrayList<>();
    private volatile Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Method updateNotificationDrawable;
    private ClassLoader loader;

    @Override public void onModuleLoaded(ModuleLoadedParam p){
        eligible=!p.isSystemServer()&&IconLayerStore.SYSTEM_UI.equals(p.getProcessName());
    }
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||installed||!IconLayerStore.SYSTEM_UI.equals(p.getPackageName()))return;
        installed=true;loader=p.getDefaultClassLoader();
        try{
            Method attach=Application.class.getDeclaredMethod("attach",Context.class);
            hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();Context c=(Context)chain.getThisObject();
                if(context==null){context=c;IconAssets.onReload(this::refreshNotifications);IconAssets.connect(c);}
                return result;
            });
        }catch(Exception failure){note("CONNECT_UNAVAILABLE "+failure.getClass().getSimpleName());return;}
        try{notifications();}catch(Exception failure){note("NOTIFICATION_UNAVAILABLE "+failure.getClass().getSimpleName());}
        try{splash();}catch(Exception failure){note("SPLASH_UNAVAILABLE "+failure.getClass().getSimpleName());}
    }

    private void notifications()throws Exception{
        Class<?> view=Class.forName("com.android.systemui.statusbar.StatusBarIconView",false,loader);
        Class<?> descriptor=Class.forName("com.android.internal.statusbar.StatusBarIcon",false,loader);
        Field notification=field(view,"mNotification"),conversation=field(view,"mShowsConversation"),sourceIcon=field(descriptor,"icon");
        Constructor<?> scaling=Class.forName("com.android.systemui.statusbar.ScalingDrawableWrapper",false,loader)
            .getDeclaredConstructor(Drawable.class,float.class);scaling.setAccessible(true);
        Method get=view.getDeclaredMethod("getIcon",descriptor);
        updateNotificationDrawable=view.getDeclaredMethod("updateDrawable",boolean.class);updateNotificationDrawable.setAccessible(true);
        hook(get).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object object=chain.getThisObject();
            StatusBarNotification notice=(StatusBarNotification)notification.get(object);
            if(notice==null||conversation.getBoolean(object)||!notice.getUser().equals(android.os.Process.myUserHandle()))return chain.proceed();
            Icon small=notice.getNotification().getSmallIcon(),incoming=(Icon)sourceIcon.get(chain.getArg(0));
            // A conversation avatar or another semantic icon is not the application's identity glyph.
            if(!sameSource(small,incoming))return chain.proceed();
            Context c=((View)object).getContext();Drawable replacement=IconAssets.notification(c,notice.getPackageName());
            track(object);
            if(replacement==null)return chain.proceed();
            // The native getIcon applies this same resource scale; tint remains owned by StatusBarIconView.
            int id=c.getResources().getIdentifier("status_bar_icon_scale_factor","dimen",IconLayerStore.SYSTEM_UI);
            if(id!=0){
                TypedValue value=new TypedValue();c.getResources().getValue(id,value,true);float scale=value.getFloat();
                if(scale>0&&scale!=1f){
                    try{return scaling.newInstance(replacement,scale);}
                    catch(ReflectiveOperationException ignored){}
                }
            }
            return replacement;
        });
        note("NOTIFICATION_READY");
    }
    private void track(Object view){
        synchronized(notificationViews){
            for(Iterator<WeakReference<Object>> it=notificationViews.iterator();it.hasNext();){Object old=it.next().get();if(old==null)it.remove();else if(old==view)return;}
            notificationViews.add(new WeakReference<>(view));
        }
    }
    private void refreshNotifications(){
        main.post(()->{
            List<Object> targets=new ArrayList<>();
            synchronized(notificationViews){for(Iterator<WeakReference<Object>> it=notificationViews.iterator();it.hasNext();){Object view=it.next().get();if(view==null)it.remove();else targets.add(view);}}
            for(Object view:targets)try{updateNotificationDrawable.invoke(view,true);}catch(Exception ignored){}
        });
    }
    private void splash()throws Exception{
        Class<?> builder=Class.forName("com.android.wm.shell.startingsurface.SplashscreenContentDrawer$SplashViewBuilder",false,loader);
        Class<?> drawer=Class.forName("com.android.wm.shell.startingsurface.SplashscreenContentDrawer",false,loader);
        Class<?> attrs=Class.forName("com.android.wm.shell.startingsurface.SplashscreenContentDrawer$SplashScreenWindowAttrs",false,loader);
        Field activity=field(builder,"mActivityInfo"),outer=field(builder,"this$0"),temporary=field(drawer,"mTmpAttrs");
        Field icon=field(attrs,"mSplashScreenIcon"),background=field(attrs,"mIconBgColor"),suggest=field(builder,"mSuggestType");
        Method build=builder.getDeclaredMethod("build");
        hook(build).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            ActivityInfo target=(ActivityInfo)activity.get(chain.getThisObject());Context c=context;
            int type=suggest.getInt(chain.getThisObject());
            if(c==null||target==null||target.applicationInfo==null||type==3||type==4
                ||!UserHandle.getUserHandleForUid(target.applicationInfo.uid).equals(android.os.Process.myUserHandle()))return chain.proceed();
            Drawable replacement=IconAssets.colored(c,target.packageName);
            if(replacement==null)return chain.proceed();
            Object values=temporary.get(outer.get(chain.getThisObject()));Object previous=icon.get(values);int oldBackground=background.getInt(values);
            try{icon.set(values,replacement);background.setInt(values,0);return chain.proceed();}
            finally{icon.set(values,previous);background.setInt(values,oldBackground);}
        });
        note("SPLASH_READY");
    }
    private static Field field(Class<?> cls,String name)throws Exception{Field field=cls.getDeclaredField(name);field.setAccessible(true);return field;}
    private static boolean sameSource(Icon a,Icon b){
        if(a==null||b==null)return false;if(a==b)return true;
        return a.getType()==Icon.TYPE_RESOURCE&&b.getType()==Icon.TYPE_RESOURCE
            &&a.getResId()==b.getResId()&&Objects.equals(a.getResPackage(),b.getResPackage());
    }
    private void note(String s){try{log(4,"NcSystemIcons",s);}catch(Throwable ignored){}}
}
