package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowBackendConflictTest {
    @Test public void losingHostCannotBecomeReadyOrAssumeSafeEnvironment() throws Exception {
        var constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        Context app=RuntimeEnvironment.getApplication();
        var display=app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        SystemUiHost host=constructor.newInstance(app.createDisplayContext(display));
        ReflectionHelpers.setField(host,"controls",new WindowControls(host));
        ReflectionHelpers.setField(host,"miniControls",new WindowControls(host));
        ReflectionHelpers.setField(host,"backendReady",true);
        var receive=SystemUiHost.class.getDeclaredMethod("receiveFrame",Intent.class);receive.setAccessible(true);
        receive.invoke(host,new Intent().putExtra("state","conflict").putExtra("ownershipConflict",true));
        assertFalse(ReflectionHelpers.getField(host,"backendReady"));
        assertTrue(ReflectionHelpers.getField(host,"systemFocus"));
        assertTrue(ReflectionHelpers.getField(host,"keyboard"));
        assertEquals("",ReflectionHelpers.getField(host,"foreground"));
    }
    @Test public void conflictCannotReplaceFreshCompanionFactsAndStaleFactsCloseAgain() throws Exception {
        var constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        Context app=RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager.class)).setIsInteractive(true);
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        var display=app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        SystemUiHost host=constructor.newInstance(app.createDisplayContext(display));
        ReflectionHelpers.setField(host,"controls",new WindowControls(host));
        ReflectionHelpers.setField(host,"miniControls",new WindowControls(host));
        android.os.Handler main=ReflectionHelpers.getField(host,"main");
        try {
        boolean[] blocked={true};String[] foreground={""};
        HostExtras extras=(HostExtras)java.lang.reflect.Proxy.newProxyInstance(HostExtras.class.getClassLoader(),new Class<?>[]{HostExtras.class},(proxy,method,args)->{
            if("environment".equals(method.getName())){blocked[0]=(Boolean)args[2];foreground[0]=(String)args[9];}
            return method.getReturnType()==boolean.class?false:null;
        });
        ReflectionHelpers.setField(host,"extras",extras);
        Intent facts=new Intent().putExtra("foreground","example.editor").putExtra("fullscreenApp","example.editor")
            .putExtra("systemOverlay",false).putExtra("systemOverlayTitle","").putExtra("imeVisible",true).putExtra("imeTop",1000)
            .putExtra("environmentAt",android.os.SystemClock.elapsedRealtime()).putExtra("environmentUser",0).putExtra("environmentDisplay",0);
        var receive=SystemUiHost.class.getDeclaredMethod("receiveCompanionEnvironment",Intent.class);receive.setAccessible(true);
        receive.invoke(host,facts);assertFalse(blocked[0]);assertEquals("example.editor",foreground[0]);
        var frame=SystemUiHost.class.getDeclaredMethod("receiveFrame",Intent.class);frame.setAccessible(true);
        frame.invoke(host,new Intent().putExtra("state","conflict").putExtra("ownershipConflict",true));
        assertFalse(ReflectionHelpers.getField(host,"backendReady"));assertFalse(host.suggestionWindowReady());
        assertFalse(blocked[0]);assertEquals("example.editor",foreground[0]);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2501));
        Runnable expire=ReflectionHelpers.getField(host,"expireCompanionFacts");expire.run();
        assertTrue(blocked[0]);
        receive.invoke(host,new Intent(facts).putExtra("environmentAt",android.os.SystemClock.elapsedRealtime()).putExtra("systemOverlay",true));
        assertTrue(blocked[0]);
        } finally {main.removeCallbacksAndMessages(null);}
    }
}
