package dev.local.sunwindowdemo;

import android.graphics.PointF;
import android.os.IBinder;
import android.os.Parcel;
import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Denies only the native upward navigation path, before it can move any task surface. */
final class DefaultWindowNavigation {
    static final int QUERY=IBinder.FIRST_CALL_TRANSACTION+1;
    private static final int NAV_BAR_EDGE=256; // LauncherInputConsumer's EDGE_NAV_BAR flag.
    private static final double QUICK_SWITCH_SLOPE=Math.tan(Math.toRadians(15));
    private static volatile IBinder channel;

    private DefaultWindowNavigation(){}

    static void configure(IBinder value){channel=value;}

    private static boolean blocked(){
        IBinder binder=channel;
        if(binder==null||!binder.isBinderAlive())return false;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(SwipeMini.CHANNEL);
            if(!binder.transact(QUERY,data,reply,0))return false;
            reply.readException();return reply.readBoolean();
        }catch(android.os.RemoteException|RuntimeException unavailable){return false;}
        finally{data.recycle();reply.recycle();}
    }

    static boolean upward(float dx,float dy){return -dy>QUICK_SWITCH_SLOPE*Math.abs(dx);}

    private static boolean fingerDown(MotionEvent event,Method display)throws ReflectiveOperationException{
        return event.getActionMasked()==MotionEvent.ACTION_DOWN&&event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
            &&event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&(Integer)display.invoke(event)==0;
    }

    static void install(XposedModule module,ClassLoader loader,Method display,Consumer<String> log){
        var hooks=new ArrayList<XposedInterface.HookHandle>();
        try{
            String qs="com.android.quickstep.",consumers=qs+"inputconsumers.";
            String controllers="com.android.launcher3.uioverrides.touchcontrollers.";
            var other=Class.forName(consumers+"OtherActivityInputConsumer",false,loader);
            var withoutFocus=Class.forName(consumers+"LauncherWithoutFocusInputConsumer",false,loader);
            var launcherOverview=Class.forName(controllers+"NoButtonNavbarToOverviewTouchController",false,loader);
            var launcherHome=Class.forName(controllers+"NavBarToHomeTouchController",false,loader);
            var stateType=Class.forName(qs+"GestureState",false,loader);
            var deviceStateType=Class.forName(qs+"RecentsAnimationDeviceState",false,loader);
            Method input=motion(other,"onMotionEvent",void.class),cancel=motion(other,"forceCancelGesture",void.class);
            Method desktopInput=motion(withoutFocus,"onMotionEvent",void.class);
            Method overview=motion(launcherOverview,"canInterceptTouch",boolean.class);
            Method home=motion(launcherHome,"onControllerInterceptTouchEvent",boolean.class);
            Method defer;
            try{defer=other.getDeclaredMethod("shouldDeferDown");}
            catch(NoSuchMethodException older){defer=other.getDeclaredMethod("needDeferDown");}
            SwipeMini.exact(defer,boolean.class);
            Method gestural=SwipeMini.exact(deviceStateType.getDeclaredMethod("isFullyGesturalNavMode"),boolean.class);
            Method extendedSlop=SwipeMini.exact(stateType.getDeclaredMethod("isInExtendedSlopRegion"),boolean.class);
            var state=SwipeMini.field(other,"mGestureState",stateType);
            var device=SwipeMini.field(other,"mDeviceState",deviceStateType);
            var down=SwipeMini.field(other,"mDownPos",PointF.class);
            var pointer=SwipeMini.field(other,"mActivePointerId",int.class);
            var slop=SwipeMini.field(other,"mSquaredTouchSlop",float.class);
            var started=SwipeMini.field(other,"mPassedPilferInputSlop",boolean.class);
            // Quickstep dispatches each consumer on its input thread. Keep identity per gesture, not a global input freeze.
            var pending=new WeakHashMap<Object,Long>();
            var desktopBlocked=new WeakHashMap<Object,Long>();
            hooks.add(module.hook(defer).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->
                pending.containsKey(chain.getThisObject())?true:chain.proceed()));
            hooks.add(module.hook(input).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject();MotionEvent event=(MotionEvent)chain.getArg(0);
                int action=event.getActionMasked();long downTime=event.getDownTime();
                if(action==MotionEvent.ACTION_DOWN){
                    pending.remove(owner);
                    if(fingerDown(event,display)&&!started.getBoolean(owner)
                            &&(Boolean)gestural.invoke(device.get(owner))&&blocked())pending.put(owner,downTime);
                    return chain.proceed();
                }
                if(!Long.valueOf(downTime).equals(pending.get(owner)))return chain.proceed();
                if(action==MotionEvent.ACTION_MOVE){
                    int index=event.findPointerIndex(pointer.getInt(owner));
                    if(index<0)return chain.proceed();
                    PointF start=(PointF)down.get(owner);float dx=event.getX(index)-start.x,dy=event.getY(index)-start.y;
                    if(dx*dx+dy*dy>=slop.getFloat(owner)&&!(Boolean)extendedSlop.invoke(state.get(owner))){
                        try{
                            if(upward(dx,dy)&&blocked()){
                                int originalAction=event.getAction();
                                try{cancel.invoke(owner,event);}finally{event.setAction(originalAction);}
                                log.accept("WINDOW_NAV_UP_BLOCKED");return null;
                            }
                            // Keep native deferred start enabled for this first lateral MOVE; otherwise Quickstep
                            // expects a handler to exist from DOWN. Later motion uses the native running handler.
                            return chain.proceed();
                        }finally{pending.remove(owner);}
                    }
                }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                    pending.remove(owner);
                }
                return chain.proceed();
            }));
            // The unfocused desktop consumer only recognizes Home. Never feed it an orphan MOVE.
            hooks.add(module.hook(desktopInput).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject();MotionEvent event=(MotionEvent)chain.getArg(0);
                int action=event.getActionMasked();long downTime=event.getDownTime();
                if(action==MotionEvent.ACTION_DOWN){
                    desktopBlocked.remove(owner);
                    if(fingerDown(event,display)&&blocked())desktopBlocked.put(owner,downTime);
                }
                if(!Long.valueOf(downTime).equals(desktopBlocked.get(owner)))return chain.proceed();
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)desktopBlocked.remove(owner);
                return null;
            }));
            // Focused desktop: these controllers are separate from the horizontal quick-switch controller.
            for(Method method:new Method[]{overview,home})hooks.add(module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    MotionEvent event=(MotionEvent)chain.getArg(0);
                    if((event.getEdgeFlags()&NAV_BAR_EDGE)!=0&&fingerDown(event,display)&&blocked())return false;
                    return chain.proceed();
                }));
            log.accept("WINDOW_NAV_GUARD_READY");
        }catch(Exception|LinkageError unavailable){
            for(var hook:hooks)try{hook.unhook();}catch(RuntimeException ignored){}
            log.accept("WINDOW_NAV_GUARD_UNAVAILABLE "+unavailable.getClass().getSimpleName());
        }
    }

    private static Method motion(Class<?> owner,String name,Class<?> result)throws NoSuchMethodException{
        return SwipeMini.exact(owner.getDeclaredMethod(name,MotionEvent.class),result);
    }
}
