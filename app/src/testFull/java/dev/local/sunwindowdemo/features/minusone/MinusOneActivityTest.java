package dev.local.sunwindowdemo.features.minusone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import java.time.LocalTime;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MinusOneActivityTest {
    @Test public void widgetHostCreatesTheFeedView() {
        try (var controller = Robolectric.buildActivity(MinusOneActivity.class).create()) {
            var activity = controller.get();
            AppWidgetHost host = ReflectionHelpers.getField(activity, "host");
            AppWidgetHostView view = ReflectionHelpers.callInstanceMethod(host, "onCreateView",
                    from(Context.class, activity), from(int.class, 1),
                    from(AppWidgetProviderInfo.class, new AppWidgetProviderInfo()));
            assertNotEquals(AppWidgetHostView.class, view.getClass());
            assertEquals("FeedWidgetView", view.getClass().getSimpleName());
        }
    }

    @Test public void greetingChangesAtSixTwelveAndEighteen() {
        assertEquals("晚上好", MinusOneActivity.greetingForHour(0));
        assertEquals("晚上好", MinusOneActivity.greetingForHour(5));
        assertEquals("上午好", MinusOneActivity.greetingForHour(6));
        assertEquals("上午好", MinusOneActivity.greetingForHour(11));
        assertEquals("下午好", MinusOneActivity.greetingForHour(12));
        assertEquals("下午好", MinusOneActivity.greetingForHour(17));
        assertEquals("晚上好", MinusOneActivity.greetingForHour(18));
    }

    @Test public void headerShowsTheCurrentGreeting() {
        try (var controller = Robolectric.buildActivity(MinusOneActivity.class).create()) {
            TextView greeting = ReflectionHelpers.getField(controller.get(), "greeting");
            assertEquals(MinusOneActivity.greetingForHour(LocalTime.now().getHour()),
                    greeting.getText().toString());
        }
    }
}
