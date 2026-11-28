package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Observes only workspace blank-cell touches; never consumes or replaces native input. */
final class DesktopDoubleTap {
    private static IBinder channel;
    private static Context application;
    private static boolean connected;
    private static Consumer<String> log=ignored->{};
    private static final Map<Object,Taps> gestures=new WeakHashMap<>();

    static final class Taps {
        private long down=-1,previousUp=-1;
        private float x,y,previousX,previousY;
        private boolean candidate;
        java.lang.ref.WeakReference<View> page=new java.lang.ref.WeakReference<>(null);
        void reset(){down=-1;previousUp=-1;candidate=false;}
        boolean event(int action,long time,float px,float py,int pointers,boolean allowed,
                      int touchSlop,int doubleSlop,int timeout,int longPress){
            if(!allowed||pointers!=1||!Float.isFinite(px)||!Float.isFinite(py)){reset();return false;}
            if(action==MotionEvent.ACTION_DOWN){down=time;x=px;y=py;candidate=true;return false;}
            if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_DOWN){reset();return false;}
            if(!candidate)return false;
            if(time<down||time-down>=longPress||distance(px-x,py-y)>touchSlop*touchSlop){reset();return false;}
            if(action!=MotionEvent.ACTION_UP)return false;
            candidate=false;
            boolean doubleTap=previousUp>=0&&down>=previousUp&&down-previousUp<=timeout
                &&distance(px-previousX,py-previousY)<=doubleSlop*doubleSlop;
            if(doubleTap){reset();return true;}
            previousUp=time;previousX=px;previousY=py;return false;
        }
        private static float distance(float x,float y){return x*x+y*y;}
    }

    record Targets(Method touch,Field launcher,Field workspace,Method inState,Object normal,
                   Method topOpen,Field switching,Field overlay,Method pageMoving,Method handling,
                   Method currentPage,Method pageAt,Method dragController,Method dragging,Class<?> cellLayout,Method pointToCell,Method occupied,Method cellRect,Field countX,Field countY){}
    static Targets resolve(ClassLoader loader)throws Exception{
        Class<?> listener=Class.forName("com.android.launcher3.touch.WorkspaceTouchListener",false,loader);
        Class<?> launcher=Class.forName("com.android.launcher3.Launcher",false,loader);
        Class<?> workspace=Class.forName("com.android.launcher3.Workspace",false,loader);
        Class<?> state=Class.forName("com.android.launcher3.LauncherState",false,loader);
        Class<?> baseState=Class.forName("com.android.launcher3.statemanager.BaseState",false,loader);
        Class<?> container=Class.forName("com.android.launcher3.statemanager.StatefulContainer",false,loader);
        Class<?> drag=Class.forName("com.android.launcher3.dragndrop.DragController",false,loader);
        Class<?> activity=Class.forName("com.android.launcher3.views.ActivityContext",false,loader);
        Class<?> floating=Class.forName("com.android.launcher3.AbstractFloatingView",false,loader);
        Class<?> paged=Class.forName("com.android.launcher3.PagedView",false,loader);
        Class<?> cell=Class.forName("com.android.launcher3.CellLayout",false,loader);
        Field l=field(listener,"mLauncher",launcher),w=field(listener,"mWorkspace",workspace);
        Field normal=state.getDeclaredField("NORMAL");normal.setAccessible(true);
        if(!Modifier.isStatic(normal.getModifiers())||!state.isAssignableFrom(normal.getType()))throw new NoSuchFieldException("NORMAL");
        // LineageOS 24.0 narrows the return to LauncherDragController; PixelOS keeps DragController.
        Method dragController=dragController(launcher,drag);
        return new Targets(method(listener,"onTouch",boolean.class,View.class,MotionEvent.class),l,w,
            method(container,"isInState",boolean.class,baseState),normal.get(null),
            method(floating,"getTopOpenView",floating,activity),field(workspace,"mIsSwitchingState",boolean.class),
            field(workspace,"mOverlayShown",boolean.class),method(paged,"isPageInTransition",boolean.class),
            method(paged,"isHandlingTouch",boolean.class),method(paged,"getCurrentPage",int.class),
            method(paged,"getPageAt",View.class,int.class),dragController,
            method(drag,"isDragging",boolean.class),cell,
            method(cell,"pointToCellExact",void.class,int.class,int.class,int[].class),
            method(cell,"isOccupied",boolean.class,int.class,int.class),
            method(cell,"cellToRect",void.class,int.class,int.class,int.class,int.class,Rect.class),
            field(cell,"mCountX",int.class),field(cell,"mCountY",int.class));
    }
    static Method dragController(Class<?> launcher,Class<?> drag)throws NoSuchMethodException{
        Method method=launcher.getDeclaredMethod("getDragController");
        if(!drag.isAssignableFrom(method.getReturnType())||Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException("getDragController return");
        method.setAccessible(true);return method;
    }
    private static Method method(Class<?> type,String name,Class<?> result,Class<?>...args)throws Exception{
        Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);
        if(m.getReturnType()!=result)throw new NoSuchMethodException(name);return m;
    }
    private static Field field(Class<?> type,String name,Class<?> result)throws Exception{
        Field f=type.getDeclaredField(name);f.setAccessible(true);
        if(f.getType()!=result||Modifier.isStatic(f.getModifiers()))throw new NoSuchFieldException(name);return f;
    }
    static void install(XposedModule module,ClassLoader loader,Consumer<String> logger){
        log=logger;
        try{
            Targets t=resolve(loader);
            module.hook(t.touch()).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{
                    Object listener=chain.getThisObject();View view=(View)chain.getArg(0);MotionEvent e=(MotionEvent)chain.getArg(1);
                    if(!connected)connect(view.getContext()); // Also works when optional Quickstep guard hooks are absent.
                    Taps taps=gestures.computeIfAbsent(listener,key->new Taps());
                    ViewConfiguration config=ViewConfiguration.get(view.getContext());
                    View page=channel!=null&&channel.isBinderAlive()&&e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                        &&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER?eligible(t,listener,view,e):null;
                    if(taps.page.get()!=page){taps.reset();taps.page=new java.lang.ref.WeakReference<>(page);}
                    boolean allowed=page!=null;
                    if(taps.event(e.getActionMasked(),e.getEventTime(),e.getRawX(),e.getRawY(),e.getPointerCount(),allowed,
                        config.getScaledTouchSlop(),config.getScaledDoubleTapSlop(),ViewConfiguration.getDoubleTapTimeout(),ViewConfiguration.getLongPressTimeout()))
                        request(e.getEventTime());
                }catch(Exception unavailable){gestures.clear();log.accept("DESKTOP_DOUBLE_TAP_REJECTED "+unavailable.getClass().getSimpleName());}
                return result;
            });
            log.accept("DESKTOP_DOUBLE_TAP_READY");
        }catch(Throwable unavailable){log.accept("DESKTOP_DOUBLE_TAP_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
    }
    static View eligible(Targets t,Object listener,View view,MotionEvent event)throws Exception{
        // A notification can take window focus while blank workspace touches still arrive.
        // The SystemUI endpoint independently rechecks the actual foreground HOME before sleep.
        if(view!=t.workspace().get(listener)||!view.isAttachedToWindow()
            ||view.getWindowVisibility()!=View.VISIBLE||view.getDisplay()==null||view.getDisplay().getDisplayId()!=0)return null;
        Object launcher=t.launcher().get(listener);
        if(!(Boolean)t.inState().invoke(launcher,t.normal())||t.topOpen().invoke(null,launcher)!=null
            ||t.switching().getBoolean(view)||t.overlay().getBoolean(view)||(Boolean)t.pageMoving().invoke(view)
            ||(Boolean)t.handling().invoke(view)||(Boolean)t.dragging().invoke(t.dragController().invoke(launcher)))return null;
        // A child normally consumes icon/widget touches; also reject occupied cells defensively.
        View page=(View)t.pageAt().invoke(view,t.currentPage().invoke(view));
        Rect visible=new Rect(),cellRect=new Rect();int[] location=new int[2],cell=new int[2];
        if(!t.cellLayout().isInstance(page)||!page.getGlobalVisibleRect(visible)
            ||!visible.contains((int)event.getRawX(),(int)event.getRawY()))return null;
        page.getLocationOnScreen(location);int x=(int)event.getRawX()-location[0],y=(int)event.getRawY()-location[1];
        if(x<page.getPaddingLeft()||y<page.getPaddingTop()||x>=page.getWidth()-page.getPaddingRight()
            ||y>=page.getHeight()-page.getPaddingBottom())return null;
        t.pointToCell().invoke(page,x,y,cell);
        if(cell[0]<0||cell[1]<0||cell[0]>=t.countX().getInt(page)||cell[1]>=t.countY().getInt(page))return null;
        t.cellRect().invoke(page,cell[0],cell[1],1,1,cellRect);
        return cellRect.contains(x,y)&&!(Boolean)t.occupied().invoke(page,cell[0],cell[1])?page:null;
    }
    static void connect(Context context){
        if(connected)return;application=context.getApplicationContext();
        if(application==null)application=context;
        application.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            try{
                if(getSentFromUid()!=c.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)return;
                IBinder next=i.getExtras()==null?null:i.getExtras().getBinder("sleepChannel");
                channel=i.getBooleanExtra("enabled",false)&&next!=null&&next.isBinderAlive()?next:null;
                gestures.clear();
            }catch(Exception unavailable){channel=null;gestures.clear();}
        }},new IntentFilter(DesktopSleepHost.STATE),null,new Handler(Looper.getMainLooper()),Context.RECEIVER_EXPORTED);
        connected=true;
        application.sendBroadcast(new Intent(DesktopSleepHost.QUERY).setPackage(BackBridge.PACKAGE),null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private static void request(long eventTime){
        IBinder target=channel;if(target==null)return;
        Parcel data=Parcel.obtain();
        try{
            data.writeInterfaceToken(DesktopSleepHost.CHANNEL);data.writeLong(eventTime);data.writeInt(0);
            if(!target.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY))channel=null;
        }catch(RemoteException unavailable){channel=null;}finally{data.recycle();}
    }
}
