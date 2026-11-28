package dev.local.sunwindowdemo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class DesktopDoubleTapEligibilityTest {
    public static final class Launcher {
        public boolean normal=true, dragging;
        public Object floating;
        public boolean inState(Object state){return normal;}
        public Launcher dragController(){return this;}
        public boolean isDragging(){return dragging;}
        public static Object topOpen(Launcher launcher){return launcher.floating;}
    }
    public static final class Workspace extends FrameLayout {
        public boolean switching, overlay, moving, handling;
        public int windowVisibility=View.VISIBLE;
        Workspace(Context context){super(context);}
        @Override public boolean hasWindowFocus(){return false;}
        @Override public int getWindowVisibility(){return windowVisibility;}
        public boolean moving(){return moving;}
        public boolean handling(){return handling;}
        public int currentPage(){return 0;}
        public View pageAt(int page){return getChildAt(page);}
    }
    public static final class Cell extends View {
        public int countX=2, countY=2;
        public boolean occupied;
        Cell(Context context){super(context);}
        public void pointToCell(int x,int y,int[] result){result[0]=x/300;result[1]=y/300;}
        public boolean occupied(int x,int y){return occupied;}
        public void cellRect(int x,int y,int w,int h,Rect rect){rect.set(x*300,y*300,(x+w)*300,(y+h)*300);}
    }
    public static final class Listener {
        public Launcher launcher=new Launcher();
        public Workspace workspace;
    }
    private DesktopDoubleTap.Targets targets()throws Exception{
        return new DesktopDoubleTap.Targets(null,Listener.class.getField("launcher"),Listener.class.getField("workspace"),
            Launcher.class.getMethod("inState",Object.class),new Object(),Launcher.class.getMethod("topOpen",Launcher.class),
            Workspace.class.getField("switching"),Workspace.class.getField("overlay"),Workspace.class.getMethod("moving"),
            Workspace.class.getMethod("handling"),Workspace.class.getMethod("currentPage"),Workspace.class.getMethod("pageAt",int.class),
            Launcher.class.getMethod("dragController"),Launcher.class.getMethod("isDragging"),Cell.class,
            Cell.class.getMethod("pointToCell",int.class,int.class,int[].class),Cell.class.getMethod("occupied",int.class,int.class),
            Cell.class.getMethod("cellRect",int.class,int.class,int.class,int.class,Rect.class),
            Cell.class.getField("countX"),Cell.class.getField("countY"));
    }
    @Test public void deliveredBlankTouchesWorkWithoutWindowFocusButMenusAndDragsStillBlock()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();
            var listener=new Listener();
            var workspace=new Workspace(activity);listener.workspace=workspace;
            var page=new Cell(activity);workspace.addView(page,new FrameLayout.LayoutParams(600,600));
            activity.setContentView(workspace);controller.visible();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            workspace.layout(0,0,600,600);page.layout(0,0,600,600);
            int[] origin=new int[2];page.getLocationOnScreen(origin);
            MotionEvent event=MotionEvent.obtain(100,100,MotionEvent.ACTION_DOWN,origin[0]+100,origin[1]+100,0);
            try{
                var targets=targets();
                assertFalse(workspace.hasWindowFocus());
                assertTrue("workspace attached", workspace.isAttachedToWindow());
                assertNotNull("workspace display", workspace.getDisplay());
                assertEquals("window visible", View.VISIBLE, workspace.getWindowVisibility());
                assertEquals("default display", 0, workspace.getDisplay().getDisplayId());
                Rect visible=new Rect();
                assertTrue("page visible", page.getGlobalVisibleRect(visible));
                assertTrue("event inside visible page: " + visible + " raw=" + event.getRawX() + "," + event.getRawY(),
                    visible.contains((int)event.getRawX(), (int)event.getRawY()));
                assertSame(page,DesktopDoubleTap.eligible(targets,listener,workspace,event));
                listener.launcher.floating=new Object();
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                listener.launcher.floating=null;listener.launcher.dragging=true;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                listener.launcher.dragging=false;workspace.overlay=true;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                workspace.overlay=false;workspace.moving=true;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                workspace.moving=false;page.occupied=true;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                page.occupied=false;listener.launcher.normal=false;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
                listener.launcher.normal=true;
                assertSame(page,DesktopDoubleTap.eligible(targets,listener,workspace,event));
                workspace.windowVisibility=View.GONE;
                assertNull(DesktopDoubleTap.eligible(targets,listener,workspace,event));
            }finally{event.recycle();}
        }
    }
}
