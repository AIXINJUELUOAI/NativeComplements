package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class NotificationWindowTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private PendingIntent activity(){return PendingIntent.getActivity(context,10,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);}
    @Test public void onlyTheOriginalActivityContentLaunchIsEligible(){
        PendingIntent content=activity();Notification n=new Notification();n.contentIntent=content;
        assertTrue(NotificationWindow.contentLaunch(n,content));
        PendingIntent action=PendingIntent.getActivity(context,11,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        assertFalse(NotificationWindow.contentLaunch(n,action));
        PendingIntent broadcast=PendingIntent.getBroadcast(context,12,new Intent("example.REPLY"),PendingIntent.FLAG_IMMUTABLE);
        n.contentIntent=broadcast;assertFalse(NotificationWindow.contentLaunch(n,broadcast));
    }
    @Test public void summaryCallAlarmAndFullscreenNotificationsKeepNativeHandling(){
        PendingIntent content=activity();Notification n=new Notification();n.contentIntent=content;
        n.flags=Notification.FLAG_GROUP_SUMMARY;assertFalse(NotificationWindow.contentLaunch(n,content));n.flags=0;
        n.category=Notification.CATEGORY_CALL;assertFalse(NotificationWindow.contentLaunch(n,content));
        n.category=Notification.CATEGORY_ALARM;assertFalse(NotificationWindow.contentLaunch(n,content));n.category=null;
        n.fullScreenIntent=content;assertFalse(NotificationWindow.contentLaunch(n,content));
    }
    @Test public void notificationPreferencePersistsAndCanBeDisabled()throws Exception{
        WindowPreferenceStore store=new WindowPreferenceStore(context);
        assertTrue(store.read().getNotificationWindow());store.getClass().getMethod("boolean",String.class,boolean.class).invoke(store,"notificationWindow",false);
        assertFalse(store.read().getNotificationWindow());
    }
    @Test public void externalGestureToolsReceiveAnExplicitConversionShortcut(){
        var controller=Robolectric.buildActivity(WindowShortcutActivity.class,new Intent(Intent.ACTION_CREATE_SHORTCUT)).create();
        var shadow=Shadows.shadowOf(controller.get());assertEquals(Activity.RESULT_OK,shadow.getResultCode());
        Intent result=shadow.getResultIntent();Intent run=result.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT,Intent.class);
        assertNotNull(run);assertEquals(WindowShortcutActivity.ACTION,run.getAction());
        assertEquals(WindowShortcutActivity.class.getName(),run.getComponent().getClassName());
        assertTrue(WindowShortcutActivity.isCurrent(Wire.PKG,WindowShortcutActivity.ID));
        assertFalse(WindowShortcutActivity.isCurrent(Wire.PKG,"other"));
    }
}
