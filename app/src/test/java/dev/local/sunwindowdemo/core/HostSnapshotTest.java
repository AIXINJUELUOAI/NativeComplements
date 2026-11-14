package dev.local.sunwindowdemo.core;

import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class HostSnapshotTest {
    private File dir;

    @Before public void setUp() throws Exception { dir = Files.createTempDirectory("hostsnapshot").toFile(); }

    private static Bundle config(boolean enabled) {
        Bundle b = new Bundle();
        b.putBoolean("enabled", enabled);
        b.putInt("mode", 2);
        b.putLong("updated_at", 42L);
        b.putString("templates", "x");
        b.putStringArray("packages", new String[]{"a", null});
        b.putIntArray("modes", new int[]{1, 2});
        b.putLongArray("times", new long[]{3L});
        b.putStringArrayList("targets", new ArrayList<>(List.of("p", "q")));
        b.putString("backend_error", "transient");
        return b;
    }

    @Test public void liveReadIsReturnedAndRemembered() {
        HostSnapshot s = new HostSnapshot(dir, "navbar");
        HostSnapshot.Result r = s.read(() -> config(true));
        assertTrue(r.live());
        assertFalse(r.report());
        assertTrue(r.config().getBoolean("enabled"));
        assertTrue(s.reachable());
    }

    @Test public void failureFallsBackToLastSnapshotIncludingEnabled() {
        HostSnapshot s = new HostSnapshot(dir, "navbar");
        s.read(() -> config(true));
        HostSnapshot.Result r = s.read(() -> { throw new IllegalArgumentException("Unknown authority"); });
        assertFalse(r.live());
        assertTrue(r.report());
        assertTrue(r.config().getBoolean("enabled"));
        assertFalse(s.reachable());
        // Same failure again: no new log line.
        assertFalse(s.read(() -> { throw new IllegalArgumentException("Unknown authority"); }).report());
        // Recovery is reported once.
        HostSnapshot.Result back = s.read(() -> config(false));
        assertTrue(back.live() && back.report());
        assertFalse(back.config().getBoolean("enabled"));
    }

    @Test public void userDisableIsNotHeldOpenByCache() {
        HostSnapshot s = new HostSnapshot(dir, "motion");
        s.read(() -> config(true));
        s.read(() -> config(false));
        HostSnapshot.Result r = s.read(() -> null);
        assertFalse(r.live());
        assertFalse(r.config().getBoolean("enabled"));
    }

    @Test public void snapshotSurvivesHostRestartWithoutTransientKeys() {
        new HostSnapshot(dir, "callblocking", "backend_error").read(() -> config(true));
        HostSnapshot restarted = new HostSnapshot(dir, "callblocking", "backend_error");
        HostSnapshot.Result r = restarted.read(() -> { throw new IllegalArgumentException("stopped"); });
        Bundle b = r.config();
        assertNotNull(b);
        assertTrue(b.getBoolean("enabled"));
        assertEquals(2, b.getInt("mode"));
        assertEquals(42L, b.getLong("updated_at"));
        assertEquals("x", b.getString("templates"));
        assertArrayEquals(new String[]{"a", null}, b.getStringArray("packages"));
        assertArrayEquals(new int[]{1, 2}, b.getIntArray("modes"));
        assertArrayEquals(new long[]{3L}, b.getLongArray("times"));
        assertEquals(List.of("p", "q"), b.getStringArrayList("targets"));
        assertFalse(b.containsKey("backend_error"));
    }

    @Test public void noSnapshotMeansNullConfig() {
        HostSnapshot s = new HostSnapshot(dir, "shade");
        assertNull(s.read(() -> { throw new IllegalStateException("x"); }).config());
    }

    @Test public void corruptOrForeignFileIsIgnored() throws Exception {
        Files.writeString(new File(dir, "shade.json").toPath(), "{not json");
        assertNull(new HostSnapshot(dir, "shade").read(() -> null).config());
        Files.writeString(new File(dir, "edgetouch.json").toPath(), "{\"format\":1,\"feature\":\"shade\",\"values\":{}}");
        assertNull(new HostSnapshot(dir, "edgetouch").read(() -> null).config());
    }

    @Test public void retryBacksOffToSixtySeconds() {
        HostSnapshot s = new HostSnapshot(dir, "barrage");
        assertEquals(3000, s.retryDelay(3000));
        for (int i = 0; i < 10; i++) s.read(() -> null);
        assertEquals(60_000, s.retryDelay(3000));
        s.read(() -> config(true));
        assertEquals(3000, s.retryDelay(3000));
    }

    @Test(expected = IllegalArgumentException.class) public void featureNameIsRestricted() {
        new HostSnapshot(dir, "../x");
    }

    @Test public void readersAreOrderedAndANewerDisableSurvivesRestart() throws Exception {
        HostSnapshot s = new HostSnapshot(dir, "shade");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var laterEntered = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> s.read(() -> { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); return config(true); }));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = workers.submit(() -> s.read(() -> { laterEntered.countDown(); return config(false); }));
            assertFalse(laterEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();first.get(5, java.util.concurrent.TimeUnit.SECONDS);second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(s.cached().getBoolean("enabled"));
            assertFalse(new HostSnapshot(dir,"shade").cached().getBoolean("enabled"));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void callerArrayMutationsDoNotChangeTheSnapshot() {
        HostSnapshot s = new HostSnapshot(dir,"icons");Bundle value = config(true);
        var result = s.read(() -> value);
        value.getIntArray("modes")[0]=99;
        result.config().getIntArray("modes")[1]=99;
        assertArrayEquals(new int[]{1,2},s.cached().getIntArray("modes"));
    }
}
