package dev.local.sunwindowdemo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.Window;
import java.lang.reflect.*;

/** Narrow SystemUI member layouts; no notification or overlay policy lives here. */
final class SystemUiWindowTargets {
    record Screenshot(Field field,Method getter) {
        Window get(Object owner)throws Exception{return (Window)(field!=null?field.get(owner):getter.invoke(owner));}
    }
    static Screenshot screenshot(Class<?> owner)throws Exception{
        for(Class<?> type=owner;type!=null;type=type.getSuperclass())try{
            Field field=type.getDeclaredField("window");
            if(!Window.class.isAssignableFrom(field.getType())||Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException("screenshot Window field type");
            field.setAccessible(true);return new Screenshot(field,null);
        }catch(NoSuchFieldException absent){ }
        Method getter=owner.getDeclaredMethod("getWindow");
        if(!Window.class.isAssignableFrom(getter.getReturnType())||Modifier.isStatic(getter.getModifiers()))throw new NoSuchMethodException("screenshot Window getter type");
        getter.setAccessible(true);return new Screenshot(null,getter);
    }
    record NotificationClick(Method run,Method start,Field runEntry,Field runPending,Field runAnimate,
                             Field startEntry,Field startPending,Field startFill,Field startDisplay,Field sbn) {}
    static NotificationClick notificationClick(ClassLoader loader)throws Exception{
        String base="com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter";
        Class<?> entry=Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry",false,loader);
        Class<?> runner=clickAdapter(loader,base,entry,true),sender=clickAdapter(loader,base,entry,false);
        Method run=runner.getDeclaredMethod("run"),start=sender.getDeclaredMethod("startPendingIntent",Class.forName("android.view.RemoteAnimationAdapter",false,loader));
        if(run.getReturnType()!=void.class||start.getReturnType()!=int.class||Modifier.isStatic(run.getModifiers())||Modifier.isStatic(start.getModifiers()))
            throw new NoSuchMethodException("notification click signatures");
        run.setAccessible(true);start.setAccessible(true);
        return new NotificationClick(run,start,field(runner,"f$1",entry),field(runner,"f$3",PendingIntent.class),field(runner,"f$5",boolean.class),
            field(sender,"f$5",entry),field(sender,"f$3",PendingIntent.class),field(sender,"f$4",Intent.class),field(sender,"f$2",int.class),field(entry,"mSbn",StatusBarNotification.class));
    }
    private static Class<?> clickAdapter(ClassLoader loader,String base,Class<?> entry,boolean runner)throws Exception{
        // R8 lambda numbering is not a source contract; discover by the verified fields and method.
        for(int number=0;number<=64;number++)try{
            Class<?> type=Class.forName(base+"$$ExternalSyntheticLambda"+number,false,loader);
            field(type,runner?"f$1":"f$5",entry);field(type,"f$3",PendingIntent.class);
            if(runner){field(type,"f$5",boolean.class);type.getDeclaredMethod("run");}
            else{field(type,"f$4",Intent.class);field(type,"f$2",int.class);type.getDeclaredMethod("startPendingIntent",Class.forName("android.view.RemoteAnimationAdapter",false,loader));}
            return type;
        }catch(ClassNotFoundException|NoSuchFieldException|NoSuchMethodException mismatch){ }
        throw new NoSuchMethodException("notification click adapter");
    }
    private static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field field=owner.getDeclaredField(name);
        if(field.getType()!=type||Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException(name);
        field.setAccessible(true);return field;
    }
    record NotificationSend(Method send,Method sbn,Method optionsFromBundle) {
        int display(Bundle options)throws Exception{
            return options==null?-1:((android.app.ActivityOptions)optionsFromBundle.invoke(null,options)).getLaunchDisplayId();
        }
    }
    static NotificationSend notificationSend(Class<?> starter,Class<?> entry)throws Exception{
        Method send=starter.getDeclaredMethod("sendPendingIntent",PendingIntent.class,Intent.class,Bundle.class,entry);
        Method sbn=entry.getDeclaredMethod("getSbn");
        if(send.getReturnType()!=int.class||Modifier.isStatic(send.getModifiers())
            ||sbn.getReturnType()!=StatusBarNotification.class||Modifier.isStatic(sbn.getModifiers()))
            throw new NoSuchMethodException("notification send signatures");
        Method fromBundle=android.app.ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class);
        fromBundle.setAccessible(true);send.setAccessible(true);sbn.setAccessible(true);return new NotificationSend(send,sbn,fromBundle);
    }
}
