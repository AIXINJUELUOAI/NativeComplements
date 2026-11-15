package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowControlTest {
    private static WindowControl.Facts facts(String form,boolean closing,boolean full,boolean transition,boolean entry,boolean hidden){
        return new WindowControl.Facts(form,closing,full,transition,entry,hidden);
    }
    @Test public void entryFinalRequestsAndExplicitFormEndpointsShareOneRule(){
        for(String op:new String[]{"RESIZE","END_RESIZE","RELEASE","NORMAL","MINI","FULL","CLOSE"})assertTrue(op,WindowControl.entryRequest(op));
        for(String op:new String[]{"MOVE","DOCK","MUTE","RESTORE_MINI","MINI_SIZE","MINI_DRAG","MINI_DRAG_CANCEL","EDGE_DRAG","EDGE_DOCK","EDGE_BAR_DRAG","EDGE_BAR_DOCK","EDGE_DRAG_CANCEL","",null})assertFalse(op,WindowControl.entryRequest(op));
        assertEquals("normal",WindowControl.form("NORMAL"));assertEquals("mini",WindowControl.form("MINI"));
        assertEquals("full",WindowControl.form("FULL"));assertEquals("closed",WindowControl.form("CLOSE"));
        assertNull(WindowControl.form("RESIZE"));assertNull(WindowControl.form("RELEASE"));assertNull(WindowControl.form(null));
    }
    @Test public void closeIsOnlyFullTransitionExceptionButCloseAndFullStillCancelPendingLaunch(){
        WindowControl.Facts full=facts("normal",false,true,false,false,false);
        assertEquals(WindowControl.Route.GENERAL,WindowControl.route("CLOSE",full));
        for(String op:new String[]{"FULL","NORMAL","MINI","RESIZE","END_RESIZE","RELEASE","MUTE","RESTORE_MINI","MINI_SIZE","MOVE","DOCK","MINI_DRAG","MINI_DRAG_CANCEL","EDGE_DRAG","EDGE_DOCK","EDGE_BAR_DRAG","EDGE_BAR_DOCK","EDGE_DRAG_CANCEL"})assertEquals(op,WindowControl.Route.IGNORE,WindowControl.route(op,full));
        assertTrue(WindowControl.cancelsLaunch("CLOSE"));assertTrue(WindowControl.cancelsLaunch("FULL"));assertFalse(WindowControl.cancelsLaunch("MINI"));
        assertEquals(WindowControl.Route.IGNORE,WindowControl.route("CLOSE",facts("normal",true,true,false,false,false)));
    }
    @Test public void miniOperationsKeepTheirDistinctEntryTransitionAndHiddenGuards(){
        WindowControl.Facts mini=facts("mini",false,false,false,false,false),hidden=facts("mini",false,false,false,false,true);
        assertEquals(WindowControl.Route.MUTE,WindowControl.route("MUTE",mini));assertEquals(WindowControl.Route.MUTE,WindowControl.route("MUTE",hidden));
        assertEquals(WindowControl.Route.MINI_SIZE,WindowControl.route("MINI_SIZE",mini));assertEquals(WindowControl.Route.IGNORE,WindowControl.route("MINI_SIZE",hidden));
        assertEquals(WindowControl.Route.RESTORE_MINI,WindowControl.route("RESTORE_MINI",hidden));assertEquals(WindowControl.Route.IGNORE,WindowControl.route("RESTORE_MINI",mini));
        assertEquals(WindowControl.Route.RESTORE_MINI,WindowControl.route("RESTORE_MINI",facts("mini",false,false,false,true,true)));
        assertEquals(WindowControl.Route.MINI_DRAG,WindowControl.route("MINI_DRAG",mini));assertEquals(WindowControl.Route.MINI_DRAG,WindowControl.route("MINI_DRAG_CANCEL",facts("mini",false,false,true,false,false)));
        for(String op:new String[]{"EDGE_DRAG","EDGE_DOCK","EDGE_BAR_DRAG","EDGE_BAR_DOCK","EDGE_DRAG_CANCEL"}){
            assertEquals(WindowControl.Route.EDGE,WindowControl.route(op,hidden));assertEquals(WindowControl.Route.IGNORE,WindowControl.route(op,mini));
        }
        for(String op:new String[]{"MUTE","MINI_SIZE","MINI_DRAG","MINI_DRAG_CANCEL","EDGE_DRAG"}){
            assertEquals(op,WindowControl.Route.IGNORE,WindowControl.route(op,facts("mini",false,false,false,true,false)));
            assertEquals(op,WindowControl.Route.IGNORE,WindowControl.route(op,facts("normal",false,false,false,false,false)));
        }
        for(String op:new String[]{"CLOSE","FULL","MINI","NORMAL","MOVE","DOCK","RESIZE","END_RESIZE","RELEASE"})assertEquals(op,WindowControl.Route.GENERAL,WindowControl.route(op,mini));
    }
    @Test public void slotChangesReplaceDestinationWithoutSwappingTheOtherWindow(){
        assertEquals(WindowControl.SlotChange.EXPAND_PINNED,WindowControl.slot("normal",false,true));
        assertEquals(WindowControl.SlotChange.MINIMIZE_ACTIVE,WindowControl.slot("mini",true,false));
        assertEquals(WindowControl.SlotChange.KEEP,WindowControl.slot("normal",true,false));
        assertEquals(WindowControl.SlotChange.KEEP,WindowControl.slot("mini",false,true));
        assertEquals(WindowControl.SlotChange.KEEP,WindowControl.slot("mini",false,false));
        assertEquals(WindowControl.Previous.KEEP,WindowControl.previous(false,true,false,"normal",true,false));
        assertEquals(WindowControl.Previous.KEEP,WindowControl.previous(true,false,false,"normal",true,false));
        assertEquals(WindowControl.Previous.KEEP,WindowControl.previous(true,true,true,"normal",true,false));
        assertEquals(WindowControl.Previous.RETAIN_MINI,WindowControl.previous(true,true,false,"mini",false,true));
        assertEquals(WindowControl.Previous.MINIMIZE,WindowControl.previous(true,true,false,"normal",true,false));
        assertEquals(WindowControl.Previous.FINISH,WindowControl.previous(true,true,false,"normal",true,true));
        assertEquals(WindowControl.Previous.FINISH,WindowControl.previous(true,true,false,"normal",false,false));
    }
    @Test public void resizeAndMiniDragPreserveVisualCenterAndCancelRestoresExactBase(){
        Geometry.Placement p=new Geometry.Placement(1000,1600,.75f,100,200);
        Geometry.Placement resized=WindowControl.resize(p,.5f,.75f,false,false);
        assertEquals(475,resized.x()+resized.width()*resized.scale()/2,0);assertEquals(800,resized.y()+resized.height()*resized.scale()/2,0);
        WindowControl.Drag drag=WindowControl.drag(p,.8f,.6f,600,900);assertNotNull(drag);
        assertEquals(600,drag.placement().x()+drag.placement().width()*drag.placement().scale()/2,.001f);
        assertEquals(.8f*MiniMagnet.alphaFactor(.6f),drag.opacity(),0);
        assertSame(p,WindowControl.endDrag(p,drag.placement(),true));
        Geometry.Placement committed=WindowControl.endDrag(p,drag.placement(),false);assertEquals(.75f,committed.scale(),0);
        assertEquals(600,committed.x()+committed.width()*committed.scale()/2,.001f);
        assertNull(WindowControl.drag(p,1,Float.NaN,0,0));assertNull(WindowControl.drag(p,1,0,Float.POSITIVE_INFINITY,0));
        assertThrows(IllegalArgumentException.class,()->WindowControl.resize(p,Float.NaN,.75f,false,false));
    }
    @Test public void dockAndEdgeStateRespectNativeSafeAreaAndCancelDoesNotLoseHiddenPose(){
        Geometry.Placement p=new Geometry.Placement(1000,1600,.28f,800,200);
        WindowControl.Safe safe=new WindowControl.Safe(0,100,1201,2500,20);
        WindowControl.Dock moved=WindowControl.move(p,-100,3000,safe,false,1201);
        assertEquals(0,moved.placement().x(),0);assertEquals(2052,moved.placement().y(),0);assertEquals(0,moved.hiddenSide());
        WindowControl.Dock dock=WindowControl.move(p,700,200,safe,true,1201);assertEquals(901,dock.placement().x(),0);
        assertEquals(-1,WindowControl.move(p,-100,200,safe,true,1201).hiddenSide());
        assertThrows(IllegalArgumentException.class,()->WindowControl.move(p,Float.NaN,0,safe,false,1201));
        Geometry.Placement hidden=WindowControl.hiddenMini(p,-1,0,safe);assertEquals(20,hidden.x(),0);assertEquals(120,hidden.y(),0);
        WindowControl.Edge base=new WindowControl.Edge(new Geometry.Placement(1000,1600,.28f,-300,120),hidden,-1,true,false,0,0);
        WindowControl.Edge docked=WindowControl.edgeBarDock(p,1000,900,safe,1201);assertEquals(1,docked.side());assertEquals(1221,docked.placement().x(),0);assertTrue(docked.hidden());
        WindowControl.Edge cancelled=WindowControl.cancelEdge(docked,base);assertEquals(base.placement(),cancelled.placement());assertEquals(base.mini(),cancelled.mini());assertEquals(-1,cancelled.side());assertFalse(cancelled.barDragging());
        assertEquals(docked.placement(),WindowControl.cancelEdge(docked,null).placement());
        WindowControl.Dock edge=WindowControl.edgeMove(base.placement(),200,0,safe,true,1201);assertEquals(20,edge.placement().x(),0);assertEquals(100,edge.placement().y(),0);assertEquals(0,edge.hiddenSide());
        assertEquals(-1,WindowControl.edgeMove(base.placement(),-200,100,safe,true,1201).hiddenSide());
        assertEquals(Geometry.resizeMini(p,.75f,0,100,1201,2500,20,true),WindowControl.miniTarget(p,p,.75f,safe,true));
    }
}
