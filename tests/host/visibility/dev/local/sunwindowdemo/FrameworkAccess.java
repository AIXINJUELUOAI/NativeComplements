package dev.local.sunwindowdemo;
import android.view.SurfaceControl;
final class FrameworkAccess {
    static final class Task {
        SurfaceControl surface=new SurfaceControl();
        SurfaceControl.Transaction pending=new SurfaceControl.Transaction();
    }
    Object field(Object target,String name){return ((Task)target).surface;}
    Object call(Object target,String name){return ((Task)target).pending;}
    Object call(Object target,String name,Class<?>[] types,Object... args){
        return ((SurfaceControl.Transaction)target).setAlpha((SurfaceControl)args[0],(Float)args[1]);
    }
}
