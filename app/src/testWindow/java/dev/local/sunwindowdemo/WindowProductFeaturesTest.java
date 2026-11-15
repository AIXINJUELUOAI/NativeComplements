package dev.local.sunwindowdemo;

import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs against the window source set, never the full companion implementation. */
public class WindowProductFeaturesTest {
    @Test public void standaloneHooksAndHostDoNotRequireAnyOptionalFeatureRuntime() {
        List<String> logs=new ArrayList<>();
        ProductFeatures.installServer(null,null,logs::add);
        ProductFeatures.installSystemUi(null,null,logs::add);
        ProductFeatures.installNotifications(null,null,logs::add);
        ProductFeatures.connectSystemUi(null,logs::add);
        ProductFeatures.installLauncher(null,null,logs::add);
        ProductFeatures.connectLauncher(null);
        assertNull(ProductFeatures.createInput(null,null,null,null,null,logs::add));
        HostExtras extras=ProductFeatures.hostExtras(null);
        extras.startCompanions();extras.startBarrage();
        extras.notification(null,null);extras.notificationRemoved(null);
        assertFalse(extras.rotationSuggestion(()->fail("rotation click"),()->fail("rotation restore")));
        extras.publishRotationSettings();
        assertFalse(extras.blocksEdgeTouch(10,10));
        extras.environment("app",true,"system",true,true,true,true,0);
        extras.edgeEnvironment(true,true);extras.protectedRects(new int[]{0,0,100,100});
        extras.closeBarrage();extras.close();extras.close();
        assertTrue(logs.isEmpty());
    }
}
