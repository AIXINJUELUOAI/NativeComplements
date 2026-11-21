package dev.local.sunwindowdemo.core.ui;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SystemPaletteTest {
    @Test public void refreshesOnceWhenResourcesCatchUpAndStopsWhenClosed() {
        var app = RuntimeEnvironment.getApplication();
        Resources base = app.getResources();
        var neutral = new AtomicInteger(0xffeeeeee);
        var changes = new AtomicInteger();
        Resources colors = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public int getColor(int id, Theme theme) {
                return id == android.R.color.system_neutral1_100 ? neutral.get() : base.getColor(id, theme);
            }
        };
        var context = new ContextWrapper(app) {
            @Override public Resources getResources() { return colors; }
        };
        var main = shadowOf(Looper.getMainLooper());
        var observer = new SystemPalette(context, changes::incrementAndGet);
        try {
            observer.recheck();
            main.idle();
            assertEquals(0, changes.get());
            neutral.set(0xffffeeee);
            main.idleFor(Duration.ofMillis(150));
            assertEquals(1, changes.get());
            observer.recheck();
            main.idleFor(Duration.ofSeconds(1));
            assertEquals(1, changes.get());
            observer.recheck();
        } finally {
            observer.close();
        }
        neutral.set(0xffeeeeff);
        main.idleFor(Duration.ofSeconds(1));
        assertEquals(1, changes.get());
    }
}
