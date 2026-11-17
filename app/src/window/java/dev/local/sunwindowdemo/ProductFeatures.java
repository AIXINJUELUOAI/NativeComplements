package dev.local.sunwindowdemo;

import android.content.*;
import android.os.Handler;
import android.service.notification.*;
import io.github.libxposed.api.XposedModule;
import java.util.function.Consumer;

/** Standalone window product: no optional feature hooks, threads, or host subscriptions. */
final class ProductFeatures {
    private ProductFeatures() {}
    static void installServer(XposedModule module,ClassLoader loader,Consumer<String> note) {}
    static void installSystemUi(XposedModule module,ClassLoader loader,Consumer<String> note) {}
    static void installNotifications(XposedModule module,ClassLoader loader,Consumer<String> note) {}
    static void connectSystemUi(android.app.Application app,Consumer<String> note) {}
    static void installLauncher(XposedModule module,ClassLoader loader,Consumer<String> note) {}
    static void connectLauncher(Context context) {}
    static Consumer<Intent> createInput(FrameworkAccess access,Context context,Object root,Object lock,Handler handler,Consumer<String> log){return null;}
    static HostExtras hostExtras(Context context){return new WindowHostExtras();}
    private static final class WindowHostExtras implements HostExtras {
        public void startCompanions() {}
        public void startBarrage() {}
        public void notification(NotificationListenerService source,StatusBarNotification notification) {}
        public void notificationRemoved(StatusBarNotification notification) {}
        public boolean rotationSuggestion(Runnable click,Runnable restore){return false;}
        public void publishRotationSettings() {}
        public boolean blocksEdgeTouch(float x,float y){return false;}
        public void environment(String foreground,boolean fullscreen,boolean systemFocus,String title,boolean keyboard,boolean screenshotVisible,boolean panelShown,boolean openWaitShown,int imeTop,String actualForeground) {}
        public void edgeEnvironment(boolean systemOverlay,boolean keyboard) {}
        public void protectedRects(int[] rectangles) {}
        public void closeBarrage() {}
        public void close() {}
    }
}
