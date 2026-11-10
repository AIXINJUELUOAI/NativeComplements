package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.util.List;

/** Small color layer, submitted with the task's pending transaction, never a full-screen window. */
final class TaskDecoration {
    private final FrameworkAccess a;
    private SurfaceControl bar;
    private boolean drawn;
    private int color;
    private Boolean backgroundDark;
    void backgroundDark(boolean dark){backgroundDark=dark;}
    int color(){return color==0?0xfff0f2f2:color;}
    private int resolveColor(Object task,boolean horizontal){
        if(horizontal)return WindowPolicy.handleColor(true,-1,false);
        if(backgroundDark!=null)return WindowPolicy.handleColor(false,-1,backgroundDark);
        int appearance=-1;boolean night=true;
        try{Object display=a.call(task,"getDisplayContent");
            Object window=a.field(a.call(display,"getDisplayPolicy"),"mTopFullscreenOpaqueWindowState");
            if(window!=null&&a.call(window,"getTask")==task)window=null;
            if(window!=null)appearance=(Integer)a.field(a.field(a.field(window,"mAttrs"),"insetsFlags"),"appearance");
        }catch(Exception ignored){}
        return WindowPolicy.handleColor(false,appearance,night);
    }
    private Rect parentBounds;
    TaskDecoration(FrameworkAccess access){a=access;}
    static void preflight(FrameworkAccess a) throws Exception {
        Class<?> b=Class.forName("android.view.SurfaceControl$Builder");
        b.getDeclaredConstructor();
        a.method(b,"setName",String.class);a.method(b,"setParent",SurfaceControl.class);
        a.method(b,"setColorLayer");a.method(b,"setHidden",boolean.class);a.method(b,"build");
        Class<?> t=SurfaceControl.Transaction.class;
        a.method(t,"setRelativeLayer",SurfaceControl.class,SurfaceControl.class,int.class);
        a.method(t,"setLayer",SurfaceControl.class,int.class);
        a.method(t,"setColor",SurfaceControl.class,float[].class);
        a.method(t,"setAlpha",SurfaceControl.class,float.class);
        a.method(t,"show",SurfaceControl.class);a.method(t,"hide",SurfaceControl.class);
        a.method(t,"remove",SurfaceControl.class);
        Class<?> controller=a.field(a.wc,"mTransitionController").getType();
        a.method(controller,"isPlaying");a.method(controller,"isCollecting");
        a.field(controller,"mBuildingTransitionLayers");
        a.method(a.task,"getTopVisibleAppMainWindow");
        Class<?> w=Class.forName("com.android.server.wm.WindowState",false,a.task.getClassLoader());
        a.method(w,"isDrawn");a.method(w,"isVisible");
    }
    private void tx(Object t,String name,Class<?>[] types,Object... args) throws Exception {a.call(t,name,types,args);}
    /** Reapply native sibling ordering after Shell playback; never invent a topmost Z. */
    void order(Object task,Object parent,Object t) throws Exception {
        Object controller=a.field(task,"mTransitionController");
        if(a.bool(controller,"isPlaying")||a.bool(controller,"isCollecting")
            ||(Boolean)a.field(controller,"mBuildingTransitionLayers"))return;
        // Plain assignChildLayers skips root tasks with Shell transitions enabled.
        // The native finish helper enables ordering and rewrites every sibling's
        // cached layer, so a former fullscreen task cannot retain a stale higher Z.
        a.finishLayers.invoke(null,parent,t);
    }
    boolean update(Object task,Object parent,Geometry.Placement p,float density,boolean landscape,float alpha,boolean structural) throws Exception {
        Object t=a.transaction(task);
        if(structural||parentBounds==null){
            order(task,parent,t);
            Object win=a.call(task,"getTopVisibleAppMainWindow");
            drawn=win!=null&&a.bool(win,"isDrawn")&&a.bool(win,"isVisible");
            parentBounds=new Rect((Rect)a.call(parent,"getBounds"));
        }
        if(!drawn||alpha<=0){if(bar!=null)tx(t,"hide",new Class<?>[]{SurfaceControl.class},bar);return drawn;}
        SurfaceControl surface=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(bar==null){
            Object builder=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
            a.call(builder,"setName",new Class<?>[]{String.class},"SunWindow-handle");
            a.call(builder,"setParent",new Class<?>[]{SurfaceControl.class},a.field(parent,"mSurfaceControl"));
            a.call(builder,"setColorLayer");a.call(builder,"setHidden",new Class<?>[]{boolean.class},true);
            bar=(SurfaceControl)a.call(builder,"build");

        }
        int nextColor=resolveColor(task,landscape);
        if(color!=nextColor){color=nextColor;tx(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},bar,
            new float[]{((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f});}
        MotionStyle.Bar b=MotionStyle.bar(p.scale());
        float length=b.length()*density,thickness=b.thickness()*density,gap=b.margin()*density;
        Rect pb=parentBounds;
        float x=landscape?p.x()+p.width()*p.scale()+gap:p.x()+(p.width()*p.scale()-length)/2;
        float y=landscape?p.y()+(p.height()*p.scale()-length)/2:p.y()+p.height()*p.scale()+gap;
        tx(t,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},bar,surface,1);
        tx(t,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},bar,x-pb.left,y-pb.top);
        tx(t,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},bar,
            Math.max(1,Math.round(landscape?thickness:length)),Math.max(1,Math.round(landscape?length:thickness)));
        tx(t,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},bar,thickness/2);
        tx(t,"setAlpha",new Class<?>[]{SurfaceControl.class,float.class},bar,Math.max(0,Math.min(1,alpha)));
        tx(t,"show",new Class<?>[]{SurfaceControl.class},bar);
        return true;
    }
    void hide(Object task) throws Exception {if(bar!=null)tx(a.transaction(task),"hide",new Class<?>[]{SurfaceControl.class},bar);}
    void remove(Object task) throws Exception {
        if(bar!=null){tx(a.transaction(task),"remove",new Class<?>[]{SurfaceControl.class},bar);bar.release();bar=null;color=0;}
    }
    void dispose(){
        if(bar==null)return;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            tx(t,"remove",new Class<?>[]{SurfaceControl.class},bar);t.apply();
        }catch(Exception ignored){}finally{bar.release();bar=null;}
    }
    /** Undo our relative-layer override using current WM ordering after restoration. */
    void restoreLayer(Object task) throws Exception {
        order(task,a.call(task,"getParent"),a.transaction(task));
    }
}
