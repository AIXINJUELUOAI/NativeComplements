package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.util.List;

/** Small color layer, submitted with the task's pending transaction, never a full-screen window. */
final class TaskDecoration {
    private final FrameworkAccess a;
    private SurfaceControl bar;
    private boolean drawn;
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
        a.field(a.wc,"mChildren");a.field(a.wc,"mLastLayer");a.field(a.wc,"mLastRelativeToLayer");
        a.method(a.task,"getTopVisibleAppMainWindow");
        Class<?> w=Class.forName("com.android.server.wm.WindowState",false,a.task.getClassLoader());
        a.method(w,"isDrawn");a.method(w,"isVisible");
    }
    private void tx(Object t,String name,Class<?>[] types,Object... args) throws Exception {a.call(t,name,types,args);}
    /** WM keeps its logical order; explicitly restore the owned surface above its normal siblings. */
    void order(Object task,Object parent,Object t) throws Exception {
        SurfaceControl surface=(SurfaceControl)a.field(task,"mSurfaceControl"),anchor=null;
        for(Object child:(List<?>)a.field(parent,"mChildren")){
            if(child==task||!a.task.isInstance(child)||a.integer(child,"getWindowingMode")!=1)continue;
            if(a.animating(child))continue;
            SurfaceControl candidate=(SurfaceControl)a.field(child,"mSurfaceControl");
            if(candidate!=null&&candidate.isValid())anchor=candidate;
        }
        if(anchor!=null)tx(t,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},surface,anchor,1);
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
            tx(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},bar,new float[]{.941f,.949f,.949f});
        }
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
        if(bar!=null){tx(a.transaction(task),"remove",new Class<?>[]{SurfaceControl.class},bar);bar.release();bar=null;}
    }
    void dispose(){
        if(bar==null)return;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            tx(t,"remove",new Class<?>[]{SurfaceControl.class},bar);t.apply();
        }catch(Exception ignored){}finally{bar.release();bar=null;}
    }
    /** Undo our relative-layer override using current WM ordering after restoration. */
    void restoreLayer(Object task) throws Exception {
        Object parent=a.call(task,"getParent"),t=a.transaction(task);
        SurfaceControl surface=(SurfaceControl)a.field(task,"mSurfaceControl");
        List<?> children=(List<?>)a.field(parent,"mChildren");
        int layer=0;boolean found=false;
        // Same home / ordinary / always-on-top grouping as TaskDisplayArea.assignRootTaskOrdering.
        for(int group=0;group<3&&!found;group++)for(Object child:children){
            int bucket=a.bool(child,"isAlwaysOnTop")?2:a.integer(child,"getActivityType")==2?0:1;
            if(bucket!=group)continue;
            if(child==task){found=true;break;}layer++;
        }
        if(found){
            tx(t,"setLayer",new Class<?>[]{SurfaceControl.class,int.class},surface,layer);
            a.field(a.wc,"mLastLayer").setInt(task,layer);
            a.field(a.wc,"mLastRelativeToLayer").set(task,null);
        }
    }
}
