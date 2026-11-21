package dev.local.sunwindowdemo.features.background;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static dev.local.sunwindowdemo.features.background.BackgroundPolicy.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class BackgroundPolicyTest {
    private static final String QQ = "com.tencent.mobileqq";
    private static final String PUSH = QQ + ":MSF";
    private static final String DOUYIN = "com.ss.android.ugc.aweme";

    @Test public void wakeLockRulesDefaultOnAndPublishExceptions() {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(BackgroundStore.wakeLockSettings(context).enabled());
        assertEquals(BackgroundStore.DEFAULT_WAKE_LOCK_RULE, BackgroundStore.wakeLockSettings(new Bundle()).rule(QQ));
        assertFalse(BackgroundStore.wakeLockSettings((Bundle) null).enabled());
        var douyin = new BackgroundStore.WakeLockRule(0, 5, 30);
        BackgroundStore.wakeLockRule(context, DOUYIN, douyin);
        BackgroundStore.wakeLockRule(context, QQ, null);
        var snapshot = BackgroundStore.wakeLockSettings(BackgroundStore.read(context));
        assertEquals(douyin, snapshot.rule(DOUYIN));
        assertNull(snapshot.rule(QQ));
        assertFalse(BackgroundStore.settings(context).enabled());
        BackgroundStore.wakeLocksEnabled(context, false);
        assertFalse(BackgroundStore.wakeLockSettings(context).enabled());
        BackgroundStore.wakeLocksEnabled(context, true);
        assertNull(BackgroundStore.wakeLockSettings(context).rule(QQ));
        BackgroundStore.wakeLockRule(context, QQ, BackgroundStore.DEFAULT_WAKE_LOCK_RULE);
        assertEquals(BackgroundStore.DEFAULT_WAKE_LOCK_RULE, BackgroundStore.wakeLockSettings(context).rule(QQ));
    }



    @Test public void lowMemoryTrimIsAChildProcessOptIn() {
        Context context = RuntimeEnvironment.getApplication();
        String child = DOUYIN + ":miniappX";
        String push = DOUYIN + ":pushserver";
        BackgroundStore.keepActive(context, DOUYIN, true);
        BackgroundStore.process(context, DOUYIN, child, Action.RECLAIM);
        BackgroundStore.process(context, DOUYIN, push, Action.ALLOW);
        BackgroundStore.process(context, DOUYIN, DOUYIN, Action.RECLAIM);
        AppRule rule = BackgroundStore.settings(context).rule(DOUYIN);
        assertEquals(Action.RECLAIM, action(rule, DOUYIN, child, DOUYIN));
        assertEquals(Action.ALLOW, action(rule, DOUYIN, DOUYIN, DOUYIN));
        assertEquals(Action.ALLOW, action(rule, DOUYIN, push, DOUYIN));
        assertEquals(Action.FREEZE, action(rule, DOUYIN, DOUYIN + ":other", DOUYIN));
        assertThrows(IllegalArgumentException.class, () -> BackgroundStore.batch(context, DOUYIN, Action.RECLAIM));
    }

    @Test public void lowMemoryRoundDebouncesStopsAndRearms() {
        long gib = 1024 * 1024;
        long low = gib / 2;
        BackgroundMemoryPressure pressure = new BackgroundMemoryPressure();
        assertFalse(pressure.shouldTrim(0, low));
        assertFalse(pressure.shouldTrim(1000, gib));
        assertFalse(pressure.shouldTrim(2000, low));
        assertFalse(pressure.shouldTrim(4999, low));
        assertTrue(pressure.shouldTrim(5000, low));
        pressure.trimmed(low);
        assertTrue(pressure.shouldTrim(6000, 2 * gib));
        pressure.trimmed(2 * gib);
        assertFalse(pressure.shouldTrim(7000, 3 * gib));
        assertFalse(pressure.shouldTrim(8000, low));
        assertTrue(pressure.shouldTrim(11000, low));
        pressure.trimmed(low);
        assertFalse(pressure.shouldTrim(12000, low));
        assertFalse(pressure.shouldTrim(16000, low));
        assertFalse(pressure.shouldTrim(17000, gib));
        assertFalse(pressure.shouldTrim(18000, low));
        assertTrue(pressure.shouldTrim(21000, low));
        pressure.trimmed(low);
        assertFalse(pressure.shouldTrim(22000, -1));
        assertFalse(pressure.shouldTrim(23000, low + 1));
    }

    @Test public void lowMemoryRoundStopsAfterFourTrimsEvenIfMemoryImproves() {
        BackgroundMemoryPressure pressure = new BackgroundMemoryPressure();
        assertFalse(pressure.shouldTrim(0, 512_000));
        for (int i = 0; i < 4; i++) {
            long available = 512_000 + i * 10_000;
            assertTrue(pressure.shouldTrim(3000 + i * 1000, available));
            pressure.trimmed(available);
        }
        assertFalse(pressure.shouldTrim(7000, 552_000));
        assertFalse(pressure.shouldTrim(10000, 582_000));
    }

    @Test public void bulkAppliesToFutureProcessesAndSingleOverridesStayEditable() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(BackgroundStore.settings(context).enabled());
        AppRule initial = BackgroundStore.settings(context).rule(QQ);
        assertEquals(Mode.STRICT, initial.mode());
        assertEquals(Action.FREEZE, action(initial, QQ, PUSH, QQ));
        BackgroundStore.mode(context, QQ, Mode.DEFAULT);

        BackgroundStore.batch(context, QQ, Action.FREEZE);
        AppRule frozen = BackgroundStore.settings(context).rule(QQ);
        assertEquals(Action.FREEZE, action(frozen, QQ, QQ, QQ));
        assertEquals(Action.FREEZE, action(frozen, QQ, PUSH, QQ));
        assertEquals(Action.FREEZE, action(frozen, QQ, QQ + ":future", QQ));

        BackgroundStore.process(context, QQ, PUSH, Action.ALLOW);
        AppRule override = BackgroundStore.settings(context).rule(QQ);
        assertEquals(Action.ALLOW, action(override, QQ, PUSH, QQ));
        assertEquals(Action.FREEZE, action(override, QQ, QQ, QQ));

        BackgroundStore.batch(context, QQ, null);
        assertEquals(Action.ALLOW, action(BackgroundStore.settings(context).rule(QQ), QQ, PUSH, QQ));
        BackgroundStore.mode(context, QQ, Mode.STRICT);
        assertEquals(Action.FREEZE, action(BackgroundStore.settings(context).rule(QQ), QQ, PUSH, QQ));
    }

    @Test public void oldPreferencesKeepSavedModesAndProcessRules() {
        Bundle old = new Bundle();
        old.putBoolean("enabled", true);
        old.putBoolean("playback", false);
        old.putBoolean("gps", false);
        old.putBoolean("chain", true);
        old.putInt("freeze_delay", 60);
        old.putString("freezer", "KILL19");
        old.putString("rules", "{\"legacy.app\":{\"mode\":\"SYSTEM\",\"priority\":\"NORMAL\",\"processes\":{\"legacy.app:one\":\"FREEZE\"}},"
            + "\"override.only\":{\"processes\":{\"override.only:push\":\"ALLOW\"}}}");
        Settings upgraded = BackgroundStore.settings(old);
        assertTrue(upgraded.enabled());
        assertEquals(Action.FREEZE, action(upgraded.rule("legacy.app"), "legacy.app", "legacy.app:one", "legacy.app"));
        assertNull(action(upgraded.rule("legacy.app"), "legacy.app", "legacy.app:two", "legacy.app"));
        assertEquals(Mode.STRICT, upgraded.rule("override.only").mode());
        assertEquals(Action.ALLOW, action(upgraded.rule("override.only"), "override.only", "override.only:push", "override.only"));
        assertEquals(Mode.STRICT, upgraded.rule("new.app").mode());
        assertEquals(5000, BackgroundProcessAccess.FREEZE_DELAY_MS);

        Context context = RuntimeEnvironment.getApplication();
        BackgroundStore.recordProcess(context, "legacy.app", "legacy.app:live");
        assertTrue(BackgroundStore.observedProcesses(context, "legacy.app").contains("legacy.app:live"));
    }

    @Test public void processScopeRespectsExplicitActions() {
        ApplicationInfo app = new ApplicationInfo();
        app.uid = 10234;
        app.packageName = QQ;
        assertTrue(BackgroundStore.eligible(app));
        app.flags = ApplicationInfo.FLAG_SYSTEM;
        assertFalse(BackgroundStore.eligible(app));
        app.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        assertFalse(BackgroundStore.eligible(app));
        app.flags = 0;
        app.uid += 100000;
        assertFalse(BackgroundStore.eligible(app));

        AppRule custom = new AppRule(Mode.DEFAULT, null, Map.of(QQ, Action.TRIM, PUSH, Action.TRIM), true, false);
        assertEquals(Action.ALLOW, action(custom, QQ, QQ, QQ));
        assertEquals(Action.TRIM, action(custom, QQ, PUSH, QQ));
    }

    @Test public void keepActiveAllowsMainByDefaultAndPreservesExplicitProcessActions() {
        Context context = RuntimeEnvironment.getApplication();
        String pkg = "user.example.app", push = pkg + ":push";
        assertFalse(BackgroundStore.settings(context).rule(pkg).keepActive());
        BackgroundStore.keepActive(context, pkg, true);
        AppRule selected = BackgroundStore.settings(context).rule(pkg);
        assertTrue(selected.keepActive());
        assertEquals(Action.ALLOW, action(selected, pkg, pkg, pkg));
        assertEquals(Action.FREEZE, action(selected, pkg, push, pkg));
        BackgroundStore.batch(context, pkg, Action.FREEZE);
        selected = BackgroundStore.settings(context).rule(pkg);
        assertEquals(Action.FREEZE, action(selected, pkg, pkg, pkg));
        BackgroundStore.process(context, pkg, push, Action.ALLOW);
        selected = BackgroundStore.settings(context).rule(pkg);
        assertEquals(Action.ALLOW, action(selected, pkg, push, pkg));
    }

    @Test public void observerKeepsResidentSwapAndMissingValuesSeparate() {
        var memory = BackgroundObserver.parseStatus(List.of("Uid:\t10225\t10225\t10225\t10225",
            "VmRSS:\t272192 kB", "VmSwap:\t851908 kB"));
        assertEquals(10225, memory.uid());
        assertEquals(272192, memory.ramKiB());
        assertEquals(851908, memory.swapKiB());
        assertEquals(-1, BackgroundObserver.parseStatus(List.of("VmRSS:\t0 kB")).swapKiB());
    }

    @Test public void douyinPlaybackOverrideOnlyAppliesInStrictAndKeepsProcessOverrides() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(BackgroundStore.settings(context).rule(DOUYIN).playbackProtected());
        assertTrue(BackgroundStore.settings(context).rule(QQ).playbackProtected());
        BackgroundStore.batch(context, DOUYIN, Action.FREEZE);
        assertFalse(BackgroundStore.settings(context).rule(DOUYIN).playbackProtected());
        BackgroundStore.process(context, DOUYIN, DOUYIN + ":push", Action.ALLOW);
        BackgroundStore.protectPlayback(context, DOUYIN, true);
        AppRule rule = BackgroundStore.settings(context).rule(DOUYIN);
        assertTrue(rule.playbackProtected());
        assertEquals(Action.ALLOW, action(rule, DOUYIN, DOUYIN + ":push", DOUYIN));
        assertEquals(Action.FREEZE, action(rule, DOUYIN, DOUYIN, DOUYIN));
        BackgroundStore.protectPlayback(context, DOUYIN, false);
        BackgroundStore.mode(context, DOUYIN, Mode.DEFAULT);
        assertTrue(BackgroundStore.settings(context).rule(DOUYIN).playbackProtected());
        BackgroundStore.mode(context, DOUYIN, Mode.SYSTEM);
        assertTrue(BackgroundStore.settings(context).rule(DOUYIN).playbackProtected());
        BackgroundStore.mode(context, DOUYIN, Mode.STRICT);
        assertFalse(BackgroundStore.settings(context).rule(DOUYIN).playbackProtected());
    }

    @Test public void signalJournalDistinguishesPidReuseAndExistingStops() throws Exception {
        String tail = " " + String.join(" ", Collections.nCopies(18, "0")) + " 123456 0";
        var paused = BackgroundFreezerAccess.parseStat("42 (name with a ) parenthesis) T" + tail);
        assertEquals(123456L, paused.start());
        assertTrue(paused.stopped());
        assertFalse(BackgroundFreezerAccess.parseStat("42 (name) S" + tail).stopped());
    }

    @Test public void strictTimingIncludesNonCachedProcessesWithoutOverridingProtection() {
        assertTrue(BackgroundProcessAccess.usesStrictTiming(Mode.STRICT, Action.FREEZE, false, 700));
        assertFalse(BackgroundProcessAccess.usesStrictTiming(Mode.DEFAULT, Action.FREEZE, false, 700));
        assertFalse(BackgroundProcessAccess.usesStrictTiming(Mode.STRICT, Action.ALLOW, false, 700));
        assertFalse(BackgroundProcessAccess.usesStrictTiming(Mode.STRICT, Action.TRIM, false, 700));
        assertFalse(BackgroundProcessAccess.usesStrictTiming(Mode.STRICT, Action.FREEZE, true, 700));
        assertFalse(BackgroundProcessAccess.usesStrictTiming(Mode.STRICT, Action.FREEZE, false, -1));
    }

    @Test public void staleMixerTracksRequireKnownOwnersAndIgnoreHistoricalEvents() {
        String live = """
             ## BEGIN thread dump
            Output thread 0x123, name AudioOut, tid 7, type 0 (MIXER):
              1 Tracks of which 0 are active
                Type     Id Active Client(pid/uid) Session Port Id S   PortMuted   Server FrmCnt  FrmRdy
                         68     no   10757/  10225     177      49 A         false 00000000   2658       0 f

             ## BEGIN setParameters dump
                Type     Id Active Client(pid/uid) Session Port Id S   PortMuted   Server FrmCnt  FrmRdy
                         68    yes   10757/  10225     177      49 A         false 00000017   2658       0 f
            """;
        var player = new BackgroundPlaybackAccess.Owner(10225, 10757, 177);
        var tracks = BackgroundPlaybackAccess.parseTracks(live);
        var frames = BackgroundPlaybackAccess.inactiveFrames(Set.of(player), tracks);
        assertEquals(1, frames.size());
        assertTrue(frames.containsValue("00000000"));
        assertTrue(BackgroundPlaybackAccess.inactiveFrames(Set.of(new BackgroundPlaybackAccess.Owner(10225, 10757, 57)), tracks).isEmpty());
        assertTrue(BackgroundPlaybackAccess.inactiveFrames(Set.of(player),
            BackgroundPlaybackAccess.parseTracks(live.replace("68     no", "68    yes"))).isEmpty());
        assertTrue(BackgroundPlaybackAccess.inactiveFrames(Set.of(player),
            BackgroundPlaybackAccess.parseTracks(live.replace("(MIXER)", "(OFFLOAD)"))).isEmpty());
    }
}
