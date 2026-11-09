package dev.local.sunwindowdemo;
import android.view.SurfaceControl;

public final class TaskVisibilityGuardChecks {
    private static void require(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        FrameworkAccess access=new FrameworkAccess();
        FrameworkAccess.Task task=new FrameworkAccess.Task();
        TaskVisibilityGuard mask=new TaskVisibilityGuard(access,task);
        mask.hide();require(task.surface.alpha==0);
        task.pending.apply();require(task.surface.alpha==0);
        mask.release();require(task.surface.alpha==1&&!mask.holding());
        mask.hide();mask.hold();task.pending.apply();require(task.surface.alpha==1);

        mask=new TaskVisibilityGuard(access,task);mask.hide();mask.transfer();
        task.surface.alpha=.4f;mask.release();mask.hide();
        require(task.surface.alpha==.4f&&!mask.holding()); // stale timeout must not undo an entry fade

        task=new FrameworkAccess.Task();mask=new TaskVisibilityGuard(access,task);mask.hide();
        SurfaceControl original=task.surface;task.surface=new SurfaceControl();task.surface.alpha=.6f;
        try{mask.hold();throw new AssertionError();}catch(IllegalStateException expected){}
        mask.release();require(original.alpha==1&&task.surface.alpha==.6f);

        task=new FrameworkAccess.Task();mask=new TaskVisibilityGuard(access,task);mask.hide();
        SurfaceControl.failApply=true;
        try{mask.release();throw new AssertionError();}catch(IllegalStateException expected){}
        require(mask.holding());SurfaceControl.failApply=false;mask.release();require(task.surface.alpha==1);

        task=new FrameworkAccess.Task();mask=new TaskVisibilityGuard(access,task);mask.hide();
        task.surface.valid=false;mask.release();require(!mask.holding());
        task=new FrameworkAccess.Task();mask=new TaskVisibilityGuard(access,task);mask.hide();
        SurfaceControl child=new SurfaceControl();
        SurfaceControl.Transaction start=new SurfaceControl.Transaction(),finish=new SurfaceControl.Transaction();
        start.setAlpha(task.surface,1);finish.setAlpha(task.surface,1); // native Shell writes
        mask.inTransaction(start,child);mask.inTransaction(finish,child);
        start.apply();require(task.surface.alpha==0&&child.alpha==0);
        mask.release();require(task.surface.alpha==1&&child.alpha==1);
        finish.apply();require(task.surface.alpha==0&&child.alpha==0); // delayed native finish
        mask.afterTransaction(1);require(task.surface.alpha==1&&child.alpha==1);
        mask.afterTransaction(.3f);require(task.surface.alpha==.3f); // newer live session

        mask=new TaskVisibilityGuard(access,task);mask.hide();
        mask.inTransaction(finish,child);mask.transfer();require(task.surface.alpha==0&&child.alpha==1);
        task.surface.alpha=.4f;finish.apply();mask.afterTransaction(.4f);
        require(task.surface.alpha==.4f&&child.alpha==1); // current fade, never force 1
        mask.release();require(task.surface.alpha==.4f);
        mask.afterTransaction(1);require(task.surface.alpha==1); // acquired session already ended
        TaskVisibilityGuard next=new TaskVisibilityGuard(access,task);next.hide();
        mask.continueWith(next);require(task.surface.alpha==0&&child.alpha==0);
        next.release();require(task.surface.alpha==1&&child.alpha==1);
        task=new FrameworkAccess.Task();mask=new TaskVisibilityGuard(access,task);mask.hide();
        mask.release(0);require(task.surface.alpha==0&&!mask.holding());
        task.pending.apply();require(task.surface.alpha==0); // releasing a parked cover must not flash
        mask=new TaskVisibilityGuard(access,task);mask.hide();mask.release(.4f);
        require(task.surface.alpha==.4f);task.pending.apply();require(task.surface.alpha==.4f);
        System.out.println("PASS: mask lifecycle, two-surface Shell writes, late finish after cancel/transfer, replacement isolation and failed cleanup retry");
    }
}
