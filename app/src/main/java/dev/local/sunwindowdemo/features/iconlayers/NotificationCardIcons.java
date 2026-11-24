package dev.local.sunwindowdemo.features.iconlayers;

import android.app.Notification;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Card identity only. Native RemoteViews, caching, tint and layout are never rewritten. */
final class NotificationCardIcons {
    private static final String PREFIX="com.android.systemui.statusbar.notification.";
    final Method contentUpdated,draw;
    private final Field icon,wrapperRow,rowEntry,drawable;
    private final Method getSbn;
    private final Set<Class<?>> iconTypes;
    private final Set<Class<?>> standard;
    // Main-thread only; neither the wrapper nor its row keeps the weak view key alive.
    private final Map<ImageView,Binding> views=new WeakHashMap<>();
    private static final class Binding {
        final WeakReference<Object> wrapper,row;
        Binding(Object wrapper,Object row){this.wrapper=new WeakReference<>(wrapper);this.row=new WeakReference<>(row);}
    }
    @FunctionalInterface interface Draw {Object proceed() throws Throwable;}

    static NotificationCardIcons resolve(ClassLoader loader)throws ReflectiveOperationException{
        Class<?> header=Class.forName(PREFIX+"row.wrapper.NotificationHeaderViewWrapper",false,loader);
        Class<?> row=Class.forName(PREFIX+"row.ExpandableNotificationRow",false,loader);
        Class<?> entry=Class.forName(PREFIX+"collection.EntryAdapter",false,loader);
        Class<?> caching=Class.forName("com.android.internal.widget.CachingIconView",false,loader);
        Set<Class<?>> standard=new HashSet<>();standard.add(header);
        // Exact standard templates seen in both archived SystemUIs. No subclass/name heuristics:
        // messaging, conversation, calls, decorated/custom and unknown templates retain native art.
        for(String name:new String[]{"Template","BigTextTemplate","BigPictureTemplate","MediaTemplate","ProgressTemplate","MetricTemplate"}){
            try{standard.add(Class.forName(PREFIX+"row.wrapper.Notification"+name+"ViewWrapper",false,loader));}
            catch(ClassNotFoundException absent){} // Optional known template; never guess a replacement ABI.
        }
        Class<?> rowIcon;
        try{rowIcon=Class.forName("com.android.internal.widget.NotificationRowIconView",false,loader);}
        catch(ClassNotFoundException absent){return new NotificationCardIcons(header,row,entry,caching,standard);}
        return new NotificationCardIcons(header,row,entry,caching,standard,rowIcon);
    }

    NotificationCardIcons(Class<?> header,Class<?> row,Class<?> entry,Class<?> caching,Set<Class<?>> standard,Class<?>... additionalIcons)throws ReflectiveOperationException{
        if(caching.getSuperclass()!=ImageView.class)throw new NoSuchMethodException("CachingIconView superclass");
        Set<Class<?>> types=new HashSet<>();types.add(caching);
        // The header field is CachingIconView, but redesigned app icons use this exact subclass.
        // Unknown subclasses stay native, especially any with a different draw implementation.
        for(Class<?> type:additionalIcons){
            if(type.getSuperclass()!=caching)throw new NoSuchMethodException("row icon superclass");
            types.add(type);
        }
        for(Class<?> type:types)for(Method method:type.getDeclaredMethods())
            if(method.getName().equals("onDraw"))throw new NoSuchMethodException("icon overrides onDraw");
        for(Class<?> template:standard)if(!header.isAssignableFrom(template))throw new NoSuchFieldException("header template");
        this.standard=Set.copyOf(standard);iconTypes=Set.copyOf(types);
        icon=field(header,"mIcon",caching);
        wrapperRow=field(header.getSuperclass(),"mRow",row);
        rowEntry=field(row,"mEntryAdapter",entry);
        getSbn=entry.getDeclaredMethod("getSbn");
        if(getSbn.getReturnType()!=StatusBarNotification.class)throw new NoSuchMethodException("EntryAdapter.getSbn");
        getSbn.setAccessible(true);
        contentUpdated=header.getDeclaredMethod("onContentUpdated",row);
        if(contentUpdated.getReturnType()!=void.class)throw new NoSuchMethodException("header onContentUpdated");
        contentUpdated.setAccessible(true);
        drawable=field(ImageView.class,"mDrawable",Drawable.class);
        draw=ImageView.class.getDeclaredMethod("onDraw",Canvas.class);draw.setAccessible(true);
    }
    private static Field field(Class<?> type,String name,Class<?> expected)throws ReflectiveOperationException{
        Field result=type.getDeclaredField(name);
        if(result.getType()!=expected)throw new NoSuchFieldException(name+" type");
        result.setAccessible(true);return result;
    }
    private static boolean mainThread(){return Looper.myLooper()==Looper.getMainLooper();}

    void updated(Object wrapper,Object row){
        if(!mainThread()||wrapper==null)return;
        try{
            Object view=icon.get(wrapper);
            if(view!=null&&iconTypes.contains(view.getClass())){
                if(standard.contains(wrapper.getClass())&&wrapperRow.get(wrapper)==row)
                    views.put((ImageView)view,new Binding(wrapper,row));
                else views.remove(view);
            }
        }catch(ReflectiveOperationException|RuntimeException ignored){} // Card failure stays local.
    }
    void refresh(){if(mainThread())for(ImageView view:views.keySet())view.invalidate();}

    Object draw(ImageView view,Draw nativeDraw)throws Throwable{
        if(!mainThread()||!iconTypes.contains(view.getClass()))return nativeDraw.proceed();
        Drawable replacement=null;
        try{
            Binding binding=views.get(view);
            if(binding!=null){
                Object wrapper=binding.wrapper.get(),row=binding.row.get();
                if(wrapper==null||row==null||!standard.contains(wrapper.getClass())
                    ||icon.get(wrapper)!=view||wrapperRow.get(wrapper)!=row){views.remove(view);}
                else{
                    Object entry=rowEntry.get(row);
                    StatusBarNotification notice=entry==null?null:(StatusBarNotification)getSbn.invoke(entry);
                    if(eligible(notice))replacement=IconAssets.colored(view.getContext(),notice.getPackageName());
                }
            }
        }catch(ReflectiveOperationException|RuntimeException ignored){} // No Binder, package lookup or decoding here.
        if(replacement==null)return nativeDraw.proceed();
        return drawWith(view,replacement,nativeDraw);
    }

    static boolean eligible(StatusBarNotification notice){
        if(notice==null||!android.os.Process.myUserHandle().equals(notice.getUser())
            ||!android.os.Process.myUserHandle().equals(UserHandle.getUserHandleForUid(notice.getUid())))return false;
        Notification n=notice.getNotification();
        if(n==null||n.getShortcutId()!=null||n.contentView!=null||n.bigContentView!=null||n.headsUpContentView!=null)return false;
        String style=n.extras==null?null:n.extras.getString(Notification.EXTRA_TEMPLATE);
        return style==null||style.equals("android.app.Notification$BigTextStyle")
            ||style.equals("android.app.Notification$BigPictureStyle")||style.equals("android.app.Notification$InboxStyle")
            ||style.equals("android.app.Notification$MediaStyle");
    }

    Object drawWith(ImageView view,Drawable replacement,Draw nativeDraw)throws Throwable{
        Drawable original=(Drawable)drawable.get(view);
        if(original==null||original.getBounds().isEmpty())return nativeDraw.proceed();
        Rect bounds=new Rect(replacement.getBounds());int alpha=replacement.getAlpha();
        try{
            // ImageView.onDraw uses its native mDrawMatrix and padding. Match the native drawable
            // bounds, NOT ImportedIcon's intrinsic size; keep the native tint/filter on the original.
            replacement.setBounds(original.getBounds());replacement.setAlpha(original.getAlpha());
            drawable.set(view,replacement);
            return nativeDraw.proceed();
        }finally{
            drawable.set(view,original);
            replacement.setBounds(bounds);replacement.setAlpha(alpha);
        }
    }
}
