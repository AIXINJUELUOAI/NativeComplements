package dev.local.sunwindowdemo.features.edgetouch;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserHandle;
import android.view.WindowManager;
import dev.local.sunwindowdemo.core.ReflectiveAccess;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Live window-focus admission for new game touches, never a cached top-task verdict. */
final class GameCornerAccess {
    private final Class<?> wms;
    private final Method getService,display,owningUid,task,windowingMode,imeWindow,visible;
    private final Field globalLock,root,currentFocus,attrs;
    private volatile Object service;

    private GameCornerAccess(ClassLoader loader)throws Exception{
        wms=Class.forName("com.android.server.wm.WindowManagerService",false,loader);
        Class<?> rootType=Class.forName("com.android.server.wm.RootWindowContainer",false,loader);
        Class<?> displayType=Class.forName("com.android.server.wm.DisplayContent",false,loader);
        Class<?> window=Class.forName("com.android.server.wm.WindowState",false,loader);
        Class<?> lock=Class.forName("com.android.server.wm.WindowManagerGlobalLock",false,loader);
        getService=ReflectiveAccess.method(Class.forName("android.os.ServiceManager",false,loader),"getService",String.class);
        if(!Modifier.isStatic(getService.getModifiers())||getService.getReturnType()!=IBinder.class)
            throw new NoSuchMethodException("ServiceManager.getService(String)");
        globalLock=field(wms,"mGlobalLock",lock);root=field(wms,"mRoot",rootType);
        currentFocus=field(displayType,"mCurrentFocus",window);
        attrs=field(window,"mAttrs",WindowManager.LayoutParams.class);
        display=ReflectiveAccess.method(rootType,"getDisplayContent",int.class);
        owningUid=ReflectiveAccess.method(window,"getOwningUid");
        imeWindow=ReflectiveAccess.method(displayType,"getImeWindow");
        visible=ReflectiveAccess.method(window,"isVisible");
        if(imeWindow.getReturnType()!=window||visible.getReturnType()!=boolean.class)
            throw new NoSuchMethodException("game keyboard signature");
        Class<?> taskType=Class.forName("com.android.server.wm.Task",false,loader);
        task=ReflectiveAccess.method(window,"getTask");
        windowingMode=ReflectiveAccess.method(taskType,"getWindowingMode");
        if(task.getReturnType()!=taskType||windowingMode.getReturnType()!=int.class)
            throw new NoSuchMethodException("game fullscreen signature");
        if(display.getReturnType()!=displayType||Modifier.isStatic(display.getModifiers())
            ||owningUid.getReturnType()!=int.class||Modifier.isStatic(owningUid.getModifiers()))
            throw new NoSuchMethodException("game focus signature");
    }

    static GameCornerAccess resolve(ClassLoader loader)throws Exception{return new GameCornerAccess(loader);}

    private static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field field=ReflectiveAccess.field(owner,name);
        if(field.getType()!=type||Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException(name);
        return field;
    }

    boolean allows(Context context,String pkg,int width,int height){
        if(context==null||pkg==null||pkg.isEmpty())return false;
        try{
            PowerManager power=context.getSystemService(PowerManager.class);
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            if(power==null||keyguard==null||!power.isInteractive()||keyguard.isKeyguardLocked())return false;
            android.view.Display screen=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
            android.graphics.Point size=new android.graphics.Point();
            if(screen==null)return false;
            screen.getRealSize(size);
            if(size.x!=width||size.y!=height)return false;
            ApplicationInfo game=context.getPackageManager().getApplicationInfo(pkg,0);
            if(!pkg.equals(game.packageName)||game.uid<0
                ||!UserHandle.getUserHandleForUid(game.uid).equals(android.os.Process.myUserHandle()))return false;
            Object manager=service;
            if(manager==null){
                Object candidate=getService.invoke(null,"window");
                // Only the local WMS gives access to its actual lock/focus. Not-ready is retryable.
                if(!wms.isInstance(candidate))return false;
                service=manager=candidate;
            }
            Object lock=globalLock.get(manager);
            if(lock==null)return false;
            // No PM or service IPC while holding WMS's lock; only local window facts live here.
            synchronized(lock){
                Object container=root.get(manager);
                if(container==null)return false;
                Object dc=display.invoke(container,0);
                Object ime=dc==null?null:imeWindow.invoke(dc);
                if(ime!=null&&Boolean.TRUE.equals(visible.invoke(ime)))return false;
                Object focus=dc==null?null:currentFocus.get(dc);
                if(focus==null)return false;
                Object focusedTask=task.invoke(focus);
                if(focusedTask==null||(Integer)windowingMode.invoke(focusedTask)!=1)return false;
                WindowManager.LayoutParams params=(WindowManager.LayoutParams)attrs.get(focus);
                return params!=null&&matches(pkg,game.uid,params.packageName,(Integer)owningUid.invoke(focus));
            }
        }catch(Exception unavailable){return false;}
    }

    static boolean matches(String requested,int gameUid,String focused,int windowUid){
        return requested!=null&&gameUid>=0&&requested.equals(focused)&&gameUid==windowUid;
    }
}
