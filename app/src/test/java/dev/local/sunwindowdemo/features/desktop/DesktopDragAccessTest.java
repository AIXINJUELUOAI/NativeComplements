package dev.local.sunwindowdemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopDragAccessTest {
    public static class Drag {}
    public static class LauncherDrag extends Drag {}
    public static class NativeLauncher {final Drag drag=new Drag();public Drag getDragController(){return drag;}}
    public static class LineageLauncher {final LauncherDrag drag=new LauncherDrag();public LauncherDrag getDragController(){return drag;}}
    public static class UnknownLauncher {public Object getDragController(){return new Object();}}
    public static class StaticLauncher {public static Drag getDragController(){return new Drag();}}
    @Test public void nativeAndCovariantReturnsResolveWithoutWeakeningReceiverOrDragType()throws Exception{
        NativeLauncher nativeLauncher=new NativeLauncher();LineageLauncher lineage=new LineageLauncher();
        assertSame(nativeLauncher.drag,DesktopDoubleTap.dragController(NativeLauncher.class,Drag.class).invoke(nativeLauncher));
        assertSame(lineage.drag,DesktopDoubleTap.dragController(LineageLauncher.class,Drag.class).invoke(lineage));
        assertThrows(NoSuchMethodException.class,()->DesktopDoubleTap.dragController(UnknownLauncher.class,Drag.class));
        assertThrows(NoSuchMethodException.class,()->DesktopDoubleTap.dragController(StaticLauncher.class,Drag.class));
    }
}
