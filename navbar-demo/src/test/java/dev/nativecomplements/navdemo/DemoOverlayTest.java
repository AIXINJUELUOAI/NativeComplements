package dev.nativecomplements.navdemo;
import android.os.Bundle;
import android.os.SystemClock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=36, manifest=Config.NONE)
public class DemoOverlayTest {
    private Bundle sample(String decision) {
        Bundle sample=new Bundle(); sample.putLong("id",1); sample.putBoolean("valid",true);
        sample.putString("component","test/.Page"); sample.putString("decision",decision);
        sample.putLong("capturedAt",SystemClock.elapsedRealtime()); return sample;
    }
    @Test public void yesPreservesAndNoInvertsDisplayedVerdict() {
        assertEquals("EXTEND",DemoOverlay.expected(sample("EXTEND"),true));
        assertEquals("NATIVE",DemoOverlay.expected(sample("EXTEND"),false));
        assertEquals("NATIVE",DemoOverlay.expected(sample("NATIVE"),true));
        assertEquals("EXTEND",DemoOverlay.expected(sample("NATIVE"),false));
    }
    @Test public void verdictNeverAutomaticallyDisplaysUserPendingLabel() {
        assertEquals("延伸",DemoOverlay.verdictText(sample("EXTEND")));
        assertEquals("悬浮",DemoOverlay.verdictText(sample("NATIVE")));
        assertEquals("等待",DemoOverlay.verdictText(sample("UNKNOWN")));
        assertEquals("等待",DemoOverlay.verdictText(null));
    }
    @Test public void unknownInvalidAndStaleCannotBeLabeled() {
        assertNull(DemoOverlay.expected(sample("UNKNOWN"),true));
        assertNull(DemoOverlay.expected(sample("UNAVAILABLE"),false));
        Bundle stale=sample("EXTEND"); stale.putLong("capturedAt",SystemClock.elapsedRealtime()-3001);
        assertNull(DemoOverlay.expected(stale,true));
        Bundle invalid=sample("EXTEND"); invalid.putBoolean("valid",false);
        assertNull(DemoOverlay.expected(invalid,false));
    }
}
