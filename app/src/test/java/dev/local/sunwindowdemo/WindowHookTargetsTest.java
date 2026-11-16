package dev.local.sunwindowdemo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.Window;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35)
public class WindowHookTargetsTest {
    static class OldTask { private boolean isResizeable(boolean check){return check;} }
    static class NewTask { private boolean isResizeable(){return false;} }
    static class InvalidTask { private int isResizeable(){return 0;} }
    static class Owner {}
    static class Callback { private final Owner captured=new Owner(); public Object invoke(Object value){return value;} }
    static class Ambiguous { private Owner one,two; public Object invoke(Object value){return value;} }
    static class ScreenshotField { private Window window; }
    static class ScreenshotGetter { public Window getWindow(){return null;} }
    static class InvalidScreenshot { public Object getWindow(){return null;} }
    static class Entry { public StatusBarNotification getSbn(){return null;} }
    static class Starter { private int sendPendingIntent(PendingIntent pi,Intent fill,Bundle options,Entry entry){return 0;} }
    static class InvalidStarter { private void sendPendingIntent(PendingIntent pi,Intent fill,Bundle options,Entry entry){} }

    public static int secondaryDisplay(){return 1;}
    @Test public void secondaryDisplayCallsNativeOnceAndPropagatesItsFailure()throws Throwable{
        java.util.concurrent.atomic.AtomicReference<io.github.libxposed.api.XposedInterface.Hooker> interceptor=new java.util.concurrent.atomic.AtomicReference<>();
        io.github.libxposed.api.XposedInterface.HookBuilder builder=(io.github.libxposed.api.XposedInterface.HookBuilder)
            java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.github.libxposed.api.XposedInterface.HookBuilder.class},(proxy,method,args)->{
                if(method.getName().equals("setExceptionMode"))return proxy;
                if(method.getName().equals("intercept")){
                    interceptor.set((io.github.libxposed.api.XposedInterface.Hooker)args[0]);
                    return new io.github.libxposed.api.XposedInterface.HookHandle(){
                        public java.lang.reflect.Executable getExecutable(){return null;}
                        public void unhook(){}
                    };
                }
                throw new AssertionError(method);
            });
        BackBridge bridge=new BackBridge();
        bridge.attachFramework((io.github.libxposed.api.XposedInterface)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{io.github.libxposed.api.XposedInterface.class},(proxy,method,args)->{
                if(method.getName().equals("hook"))return builder;
                if(method.getName().equals("log"))return null;
                throw new AssertionError(method);
            }));
        java.lang.reflect.Method install=BackBridge.class.getDeclaredMethod("installEdgeCallback",EdgeBackHookTarget.Callback.class,
            java.lang.reflect.Field.class,java.lang.reflect.Method.class,java.lang.reflect.Method.class,Object.class,
            java.lang.reflect.Field.class,java.lang.reflect.Field.class,java.lang.reflect.Method.class,java.util.concurrent.atomic.AtomicBoolean.class);
        install.setAccessible(true);
        boolean wasReady=BackBridge.cornerReady;BackBridge.cornerReady=false;
        android.view.MotionEvent event=android.view.MotionEvent.obtain(1,1,android.view.MotionEvent.ACTION_DOWN,0,0,0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{
            install.invoke(bridge,EdgeBackHookTarget.captured(Callback.class,Owner.class),null,null,
                getClass().getDeclaredMethod("secondaryDisplay"),new Object(),null,null,null,new java.util.concurrent.atomic.AtomicBoolean(true));
            assertNotNull(interceptor.get());
            java.util.concurrent.atomic.AtomicInteger nativeCalls=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<RuntimeException> nativeFailure=new java.util.concurrent.atomic.AtomicReference<>();
            Object sentinel=new Object();
            io.github.libxposed.api.XposedInterface.Chain chain=(io.github.libxposed.api.XposedInterface.Chain)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{io.github.libxposed.api.XposedInterface.Chain.class},(proxy,method,args)->{
                    if(method.getName().equals("getArg")){assertEquals(0,args[0]);return event;}
                    if(method.getName().equals("proceed")){
                        nativeCalls.incrementAndGet();if(nativeFailure.get()!=null)throw nativeFailure.get();return sentinel;
                    }
                    throw new AssertionError(method);
                });
            assertSame(sentinel,interceptor.get().intercept(chain));assertEquals(1,nativeCalls.get());
            nativeCalls.set(0);RuntimeException failed=new IllegalStateException("native failure after dispatch");nativeFailure.set(failed);
            Throwable observed=null;
            try{interceptor.get().intercept(chain);}catch(Throwable error){observed=error;}
            assertSame(failed,observed);assertEquals("Native failure must never retry the stream",1,nativeCalls.get());
        }finally{event.recycle();BackBridge.cornerReady=wasReady;}
    }

    @Test public void resolvesOnlyKnownLayoutsAndRejectsAmbiguousTargets()throws Exception{
        assertEquals(1,WindowServerTargets.taskResize(OldTask.class).getParameterCount());
        assertEquals(0,WindowServerTargets.taskResize(NewTask.class).getParameterCount());
        assertThrows(NoSuchMethodException.class,()->WindowServerTargets.taskResize(InvalidTask.class));
        EdgeBackHookTarget.Callback callback=EdgeBackHookTarget.captured(Callback.class,Owner.class);
        Callback owner=new Callback();assertSame(owner.captured,callback.owner().get(owner));
        assertEquals("invoke",callback.invoke().getName());
        assertThrows(NoSuchFieldException.class,()->EdgeBackHookTarget.captured(Ambiguous.class,Owner.class));
        assertNotNull(SystemUiWindowTargets.screenshot(ScreenshotField.class).field());
        assertNotNull(SystemUiWindowTargets.screenshot(ScreenshotGetter.class).getter());
        assertThrows(NoSuchMethodException.class,()->SystemUiWindowTargets.screenshot(InvalidScreenshot.class));
        assertEquals(int.class,SystemUiWindowTargets.notificationSend(Starter.class,Entry.class).send().getReturnType());
        assertThrows(NoSuchMethodException.class,()->SystemUiWindowTargets.notificationSend(InvalidStarter.class,Entry.class));
    }
}
