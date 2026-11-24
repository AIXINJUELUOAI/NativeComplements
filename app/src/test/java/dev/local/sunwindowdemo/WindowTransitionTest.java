package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows={WindowTransitionTest.SurfaceShadow.class,WindowTransitionTest.TransactionShadow.class})
public class WindowTransitionTest {
    public static final class Animator { public boolean hasLeash(){return false;} }
    public static final class Controller { public boolean inTransition(Node node){return true;} }
    public static class Node {
        final Node parent;
        public final SurfaceControl mSurfaceControl=ReflectionHelpers.callConstructor(SurfaceControl.class);
        public final Animator mSurfaceAnimator=new Animator();
        public final Controller mTransitionController=new Controller();
        final SurfaceControl.Transaction pending=new SurfaceControl.Transaction();
        Node(Node parent){this.parent=parent;}
        public boolean isDescendantOf(Node node){return parent!=null&&(parent==node||parent.isDescendantOf(node));}
        public Rect getBounds(){return new Rect(20,30,1220,2163);}
        public int getRelativeDisplayRotation(){return 0;}
        public SurfaceControl.Transaction getPendingTransaction(){return pending;}
        public Configuration getConfiguration(){Configuration c=new Configuration();c.densityDpi=160;return c;}
    }
    public static final class Target {
        public final Node mContainer;
        Target(Node node){mContainer=node;}
    }
    public static final class Change {
        SurfaceControl leash,snapshot;int flags,startRotation,endRotation;
        Change(Node node){leash=node.mSurfaceControl;}
        public SurfaceControl getLeash(){return leash;}
        public SurfaceControl getSnapshot(){return snapshot;}
        public int getFlags(){return flags;}
        public void setFlags(int value){flags=value;}
        public int getStartRotation(){return startRotation;}
        public int getEndRotation(){return endRotation;}
    }
    public static final class Info {
        final List<Change> changes=new ArrayList<>();
        public List<Change> getChanges(){return changes;}
    }
    public static final class Transition {
        public final List<Target> mTargets=new ArrayList<>();
        public final SurfaceControl.Transaction mStartTransaction=new SurfaceControl.Transaction();
        public boolean isCollecting(){return false;}
    }
    private FrameworkAccess access;
    private WindowEngine engine;
    private WindowEngine.Session session;
    private Node parent,task,activity;
    private Transition transition;
    private Info info;
    private SurfaceControl.Transaction finish;
    private final List<String> logs=new ArrayList<>();
    private static void set(Object object,String name,Object value)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    @Before public void setup()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        for(String name:new String[]{"methods","fields"})set(access,name,new java.util.concurrent.ConcurrentHashMap<>());
        set(access,"wc",Node.class);set(access,"noAnimationFlag",262144);
        engine=new WindowEngine(access,logs::add);
        set(engine,"lock",new Object());set(engine,"handler",new Handler(Looper.getMainLooper()));
        parent=new Node(null);task=new Node(parent);activity=new Node(task);
        session=new WindowEngine.Session();session.task=task;session.parent=parent;session.defaultScale=.75f;
        session.placement=new Geometry.Placement(600,1000,.75f,100,200);
        set(engine,"sessions",List.of(session));set(engine,"active",session);
        transition=new Transition();info=new Info();finish=new SurfaceControl.Transaction();
    }
    private Change add(Node node){Change c=new Change(node);transition.mTargets.add(new Target(node));info.changes.add(c);return c;}
    private void built(){engine.transitionBuilt(transition,info,finish);assertFalse(logs.toString(),logs.stream().anyMatch(s->s.startsWith("LINKED_TX_FAILED")));}
    private static SurfaceShadow surface(SurfaceControl s){return Shadow.extract(s);}
    private static TransactionShadow tx(SurfaceControl.Transaction t){return Shadow.extract(t);}
    private void assertPose(SurfaceControl.Transaction t){
        t.apply();SurfaceShadow state=surface(task.mSurfaceControl);
        assertEquals(.75f,state.scale,0);assertEquals(80,state.x,0);assertEquals(170,state.y,0);
        assertEquals(new Rect(0,0,600,1000),state.crop);assertEquals(1,state.alpha,0);
    }
    @Test public void mixedTaskAndActivityTargetsStayOutOfShellWithoutTouchingForeignOrDisplayChanges(){
        Change owned=add(task),child=add(activity),foreign=add(new Node(parent)),display=add(parent);
        transition.mStartTransaction.setScale(task.mSurfaceControl,1,1).setPosition(task.mSurfaceControl,0,0);
        finish.setScale(task.mSurfaceControl,1,1).setPosition(task.mSurfaceControl,0,0);
        built();
        assertEquals(List.of(foreign,display),info.changes);assertEquals(4,transition.mTargets.size());
        assertEquals(262144,owned.flags);assertEquals(262144,child.flags);assertEquals(0,foreign.flags);assertEquals(0,display.flags);
        // Shell setup writes only changes it receives; NO_ANIMATION alone would not protect these.
        for(Change c:info.changes)transition.mStartTransaction.setScale(c.leash,1,1).setPosition(c.leash,0,0);
        assertPose(transition.mStartTransaction);assertPose(finish);
    }
    @Test public void activityOnlyTransitionKeepsLocalChildCoordinatesAndNativeCompletionTargets(){
        add(activity);transition.mStartTransaction.setPosition(activity.mSurfaceControl,5,7);
        built();assertTrue(info.changes.isEmpty());assertEquals(1,transition.mTargets.size());
        assertPose(transition.mStartTransaction);assertEquals(5,surface(activity.mSurfaceControl).x,0);
        assertEquals(7,surface(activity.mSurfaceControl).y,0);assertEquals(1,surface(activity.mSurfaceControl).scale,0);
        assertPose(finish);
    }
    @Test public void snapshotOrRotationOrFixedRotationLeashPreservesWholeFamilyForNativeCleanup(){
        Change owned=add(task),child=add(activity);child.snapshot=ReflectionHelpers.callConstructor(SurfaceControl.class);
        built();assertEquals(List.of(owned,child),info.changes);
        child.snapshot=null;child.endRotation=1;built();assertEquals(List.of(owned,child),info.changes);
        child.endRotation=0;child.leash=ReflectionHelpers.callConstructor(SurfaceControl.class);
        built();assertEquals(List.of(owned,child),info.changes);
    }
    @Test public void fullscreenRestorationAndSecurityPriorityKeepNativeChanges(){
        Change owned=add(task);session.fullTransition=true;built();assertEquals(List.of(owned),info.changes);
        session.fullTransition=false;session.restoring=true;session.restoreForeground=true;
        built();assertEquals(List.of(owned),info.changes);
        session.restoring=false;session.prioritySuspended=true;built();assertEquals(List.of(owned),info.changes);
        session.prioritySuspended=false;session.screenSuspended=true;built();assertEquals(List.of(owned),info.changes);
    }
    @Test public void parkedFinishTransfersOldActivityLeashToNewMaskInsteadOfRevealingIt()throws Exception{
        session.parked=true;add(activity);built();
        TaskVisibilityGuard next=new TaskVisibilityGuard(access,task);session.startMask=next;session.parked=false;next.hide();
        finish.apply();tx(finish).commit();
        assertEquals(0,surface(activity.mSurfaceControl).alpha,0);assertEquals(0,surface(task.mSurfaceControl).alpha,0);
        next.release();assertEquals(1,surface(activity.mSurfaceControl).alpha,0);
    }
    @Implements(SurfaceControl.class)
    public static class SurfaceShadow {
        float scale=1,x,y,alpha=1;Rect crop;
        @Implementation protected boolean isValid(){return true;}
    }
    @Implements(SurfaceControl.Transaction.class)
    public static class TransactionShadow {
        @RealObject SurfaceControl.Transaction real;
        final List<Runnable> writes=new ArrayList<>(),listeners=new ArrayList<>();
        @Implementation protected void __constructor__(){}
        @Implementation protected SurfaceControl.Transaction setScale(SurfaceControl s,float x,float y){writes.add(()->surface(s).scale=x);return real;}
        @Implementation protected SurfaceControl.Transaction setPosition(SurfaceControl s,float x,float y){writes.add(()->{surface(s).x=x;surface(s).y=y;});return real;}
        @Implementation protected SurfaceControl.Transaction setCrop(SurfaceControl s,Rect crop){Rect copy=new Rect(crop);writes.add(()->surface(s).crop=copy);return real;}
        @Implementation protected SurfaceControl.Transaction setCornerRadius(SurfaceControl s,float radius){return real;}
        @Implementation protected SurfaceControl.Transaction setAlpha(SurfaceControl s,float alpha){writes.add(()->surface(s).alpha=alpha);return real;}
        @Implementation protected SurfaceControl.Transaction addTransactionCommittedListener(Executor executor,SurfaceControl.TransactionCommittedListener listener){listeners.add(listener::onTransactionCommitted);return real;}
        @Implementation protected void apply(){List<Runnable> pending=new ArrayList<>(writes);writes.clear();pending.forEach(Runnable::run);}
        @Implementation protected void close(){}
        void commit(){List<Runnable> pending=new ArrayList<>(listeners);listeners.clear();pending.forEach(Runnable::run);}
    }
}
