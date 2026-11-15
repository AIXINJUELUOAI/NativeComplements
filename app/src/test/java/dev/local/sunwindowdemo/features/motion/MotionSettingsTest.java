package dev.local.sunwindowdemo.features.motion;

import android.content.Context;
import android.os.Bundle;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MotionSettingsTest {
    private final Context context=RuntimeEnvironment.getApplication();
    @Test public void globalRuleResetsOverridesAndAllowsExplicitExceptions(){
        MotionStore.prefs(context).edit().clear().commit();
        MotionStore.mode(context,"example.old",1);
        MotionStore.all(context,2);
        Bundle b=MotionStore.read(context);
        assertEquals(2,b.getInt("default_mode"));assertEquals(0,b.getStringArray("packages").length);
        assertEquals(2,MotionPolicy.resolve(b.getInt("default_mode"),null));
        MotionStore.mode(context,"example.allowed",0);
        b=MotionStore.read(context);assertArrayEquals(new String[]{"example.allowed"},b.getStringArray("packages"));
        assertArrayEquals(new int[]{0},b.getIntArray("modes"));
        MotionStore.mode(context,"example.allowed",2);assertEquals(0,MotionStore.read(context).getStringArray("packages").length);
        MotionStore.all(context,1);assertEquals(1,MotionStore.read(context).getInt("default_mode"));
        MotionStore.all(context,0);assertEquals(0,MotionStore.read(context).getInt("default_mode"));
        MotionPolicy.Targets selected=MotionPolicy.targets(java.util.List.of(
            new MotionPolicy.App(10001,"example.first",false),new MotionPolicy.App(10001,"example.shared",false),
            new MotionPolicy.App(10002,"example.persistent",true),new MotionPolicy.App(10002,"example.sameuid",false),
            new MotionPolicy.App(10003,"com.android.systemui",false),new MotionPolicy.App(9999,"example.system",false)),
            MotionPolicy.FIRST_SCREEN,java.util.Map.of("example.shared",MotionPolicy.ALWAYS));
        assertEquals(java.util.Map.of(10001,MotionPolicy.ALWAYS),selected.rules());
        assertEquals(java.util.Map.of(10001,"example.shared"),selected.packages());
    }
    @Test public void sensorQueryAcceptsNativeByteCountsButWritesStillRequireSuccess(){
        assertTrue(SensorCommand.succeeded("get-uid-state",7));assertTrue(SensorCommand.succeeded("get-uid-state",5));
        assertFalse(SensorCommand.succeeded("get-uid-state",-1));assertFalse(SensorCommand.succeeded("get-uid-state",22));
        assertFalse(SensorCommand.succeeded("set-uid-state",7));assertFalse(SensorCommand.succeeded("reset-uid-state",5));
        assertTrue(SensorCommand.succeeded("set-uid-state",0));assertTrue(SensorCommand.succeeded("reset-uid-state",0));
    }
}
