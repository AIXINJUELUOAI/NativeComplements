package dev.local.sunwindowdemo;

import android.content.*;
import android.os.*;
import android.service.notification.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Full-product companions, absent from the standalone window source set. */
final class ProductFeatures {
    private ProductFeatures() {}
    static void installServer(XposedModule module,ClassLoader loader,Consumer<String> note){
        SuggestionEnvironment.install(module,loader,note);
        installUndimSuggestion(module,loader,note);
        dev.local.sunwindowdemo.features.edgetouch.EdgeInputFilter.install(module,loader,note);
        dev.local.sunwindowdemo.features.motion.MotionAccess.install(module,loader,note);
        dev.local.sunwindowdemo.features.background.BackgroundHost.install(module,loader,note);
    }
    static void installSystemUi(XposedModule module,ClassLoader loader,Consumer<String> note){
        dev.local.sunwindowdemo.features.shade.LandscapeShade.install(module,loader,note);
        dev.local.sunwindowdemo.features.shade.StatusBarOffset.install(module,loader,note);
        dev.local.sunwindowdemo.features.shade.SignalDisplay.install(module,loader,note);
        RotationSuggestion.install(module,loader,note);
    }
    static void installNotifications(XposedModule module,ClassLoader loader,Consumer<String> note){installSuggestionNotifications(module,loader,note);}
    static void connectSystemUi(android.app.Application app,Consumer<String> note){
        try{dev.local.sunwindowdemo.features.shade.LandscapeShade.connect(app);}
        catch(RuntimeException unavailable){note.accept("SHADE_CONNECT_FAILED "+unavailable.getClass().getSimpleName());}
        try{dev.local.sunwindowdemo.features.shade.StatusBarOffset.connect(app);}
        catch(RuntimeException unavailable){note.accept("STATUS_BAR_CONNECT_FAILED "+unavailable.getClass().getSimpleName());}
        dev.local.sunwindowdemo.features.shade.SignalDisplay.connect(app);
        DesktopSleepHost.connect(app,note);
        RotationSuggestion.connect(app);
    }
    static void installLauncher(XposedModule module,ClassLoader loader,Consumer<String> note){
        LauncherRotationSuggestion.install(module,loader,note);
        DesktopDoubleTap.install(module,loader,note);
    }
    static void connectLauncher(Context context){LauncherRotationSuggestion.connect(context);DesktopDoubleTap.connect(context);}
    static HostExtras hostExtras(Context context){return new FullHostExtras(context);}

    private static void installUndimSuggestion(XposedModule module,ClassLoader loader,Consumer<String> note){
        try{
            Class<?> power=Class.forName("com.android.server.power.PowerManagerService",false,loader);
            Class<?> group=Class.forName("com.android.server.power.PowerGroup",false,loader);
            Method event=power.getDeclaredMethod("userActivityNoUpdateLocked",group,long.class,int.class,int.class,int.class);
            Method dim=group.getDeclaredMethod("isPolicyDimLocked"),id=group.getDeclaredMethod("getGroupId"),wake=group.getDeclaredMethod("getWakefulnessLocked");
            Field context=power.getDeclaredField("mContext");
            int touch=android.os.PowerManager.class.getDeclaredField("USER_ACTIVITY_EVENT_TOUCH").getInt(null);
            int noLights=android.os.PowerManager.class.getDeclaredField("USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS").getInt(null);
            if(event.getReturnType()!=boolean.class||dim.getReturnType()!=boolean.class||id.getReturnType()!=int.class
                ||wake.getReturnType()!=int.class||context.getType()!=Context.class)throw new NoSuchMethodException("undim signature");
            event.setAccessible(true);dim.setAccessible(true);id.setAccessible(true);wake.setAccessible(true);context.setAccessible(true);
            Handler main=new Handler(Looper.getMainLooper());
            module.hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean offer=false;long time=(Long)chain.getArg(1);
                try{Object g=chain.getArg(0);
                    boolean touched=(Integer)chain.getArg(2)==touch,lights=touched&&((Integer)chain.getArg(3)&noLights)==0;
                    int groupId=lights?(Integer)id.invoke(g):-1,wakefulness=lights&&groupId==0?(Integer)wake.invoke(g):-1;
                    boolean dimmed=lights&&groupId==0&&wakefulness==1&&(Boolean)dim.invoke(g);
                    offer=dev.local.sunwindowdemo.features.suggestions.SuggestionRules.undimEvent(touched,lights,groupId,wakefulness,dimmed);}
                catch(Exception ignored){}
                Object result=chain.proceed();
                if(offer&&Boolean.TRUE.equals(result))try{
                    Context c=(Context)context.get(chain.getThisObject());
                    main.post(()->{try{
                        long age=SystemClock.uptimeMillis()-time;
                        boolean fresh=age>=0&&age<=1500;
                        boolean interactive=fresh&&c.getSystemService(PowerManager.class).isInteractive();
                        boolean keyguard=interactive&&c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
                        if(!dev.local.sunwindowdemo.features.suggestions.SuggestionRules.undimReady(age,interactive,keyguard))return;
                        c.sendBroadcast(new Intent(dev.local.sunwindowdemo.features.suggestions.SuggestionStore.AWAKE)
                            .setPackage(BackBridge.PACKAGE).putExtra("time",time),null,
                            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                    }catch(RuntimeException ignored){}});
                }catch(Exception ignored){}
                return result;
            });
            note.accept("SUGGESTIONS_UNDIM_READY");
        }catch(Throwable unavailable){note.accept("SUGGESTIONS_UNDIM_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }
    private static void installSuggestionNotifications(XposedModule module,ClassLoader loader,Consumer<String> note){
        List<XposedInterface.HookHandle> installed=new ArrayList<>();
        try{
            Class<?> listener=Class.forName("com.android.systemui.statusbar.NotificationListener",false,loader);
            if(!NotificationListenerService.class.isAssignableFrom(listener))throw new NoSuchMethodException("notification service");
            Method posted=listener.getDeclaredMethod("onNotificationPosted",StatusBarNotification.class,NotificationListenerService.RankingMap.class);
            Method removed=listener.getDeclaredMethod("onNotificationRemoved",StatusBarNotification.class,NotificationListenerService.RankingMap.class,int.class);
            if(posted.getReturnType()!=void.class||removed.getReturnType()!=void.class)throw new NoSuchMethodException("notification callbacks");
            installed.add(module.hook(posted).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();SystemUiHost host=SystemUiHost.live;
                if(host!=null)host.suggestionNotification((NotificationListenerService)chain.getThisObject(),(StatusBarNotification)chain.getArg(0));return result;
            }));
            installed.add(module.hook(removed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();SystemUiHost host=SystemUiHost.live;
                StatusBarNotification notification=(StatusBarNotification)chain.getArg(0);
                if(host!=null&&notification!=null)host.suggestionNotificationRemoved(notification);return result;
            }));
            note.accept("SUGGESTIONS_NOTIFICATIONS_READY");
        }catch(Throwable unavailable){for(XposedInterface.HookHandle h:installed)try{h.unhook();}catch(Throwable ignored){}note.accept("SUGGESTIONS_NOTIFICATIONS_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }

    private static final class FullHostExtras implements HostExtras {
        private final Context context;
        private SuggestionHost suggestions;
        private BarrageHost barrage;
        private GameModeHost gameMode;
        private volatile dev.local.sunwindowdemo.features.edgetouch.EdgeTouchHost edgeTouch;
        FullHostExtras(Context context){this.context=context;}
        public void startCompanions(){
            try{suggestions=new SuggestionHost(context.getApplicationContext());}catch(RuntimeException unavailable){android.util.Log.w("NativeComplements","SUGGESTIONS_HOST_UNAVAILABLE");}
            edgeTouch=new dev.local.sunwindowdemo.features.edgetouch.EdgeTouchHost(context.getApplicationContext());
        }
        public void startBarrage(){
            try{barrage=new BarrageHost(context);}catch(RuntimeException unavailable){android.util.Log.w("NativeComplements","BARRAGE_HOST_UNAVAILABLE",unavailable);}
            try{gameMode=new GameModeHost(context,pkg->{dev.local.sunwindowdemo.Wire.openApp(context,pkg,false);return kotlin.Unit.INSTANCE;},id->{quick(id);return kotlin.Unit.INSTANCE;});}
            catch(RuntimeException unavailable){android.util.Log.w("NativeComplements","GAMEMODE_HOST_UNAVAILABLE",unavailable);}
        }
        private void quick(String id){SystemUiHost host=SystemUiHost.live;if(host!=null)host.quickAction(id);}
        public void notification(NotificationListenerService source,StatusBarNotification notification){if(suggestions!=null)suggestions.notification(source,notification);if(barrage!=null)barrage.notification(source,notification);}
        public void notificationRemoved(StatusBarNotification notification){if(suggestions!=null)suggestions.notificationRemoved(notification.getKey());if(barrage!=null)barrage.notificationRemoved(notification);}
        public boolean rotationSuggestion(Runnable click,Runnable restore){if(suggestions==null){RotationDiagnostics.update("host","建议宿主未初始化");return false;}return suggestions.rotationSuggestion(click,restore);}
        public void publishRotationSettings(){if(suggestions!=null)suggestions.publishRotationSettings();}
        public boolean blocksEdgeTouch(float x,float y){return edgeTouch!=null&&edgeTouch.blocks(x,y);}
        public void environment(String foreground,boolean fullscreen,boolean systemFocus,String title,boolean keyboard,boolean screenshotVisible,boolean panelShown,boolean openWaitShown,int imeTop,String actualForeground){
            boolean suspended=BarrageHost.shouldSuspend(systemFocus,title,keyboard,panelShown,openWaitShown);
            if(barrage!=null)barrage.environment(foreground,fullscreen,suspended,actualForeground);
            if(gameMode!=null)gameMode.environment(foreground,fullscreen,suspended||screenshotVisible,actualForeground);
            if(suggestions!=null)suggestions.environment(screenshotVisible||systemFocus||panelShown||openWaitShown,imeTop);
        }
        public void edgeEnvironment(boolean systemOverlay,boolean keyboard){if(edgeTouch!=null)edgeTouch.setEnvironment(systemOverlay,keyboard);}
        public void protectedRects(int[] rectangles){if(edgeTouch!=null)edgeTouch.setProtectedRects(rectangles);if(gameMode!=null)gameMode.protectedRects(rectangles);}
        public void closeBarrage(){if(barrage!=null){barrage.close();barrage=null;}if(gameMode!=null){gameMode.close();gameMode=null;}}
        public void close(){if(suggestions!=null){suggestions.close();suggestions=null;}if(edgeTouch!=null){edgeTouch.close();edgeTouch=null;}}
    }
}
