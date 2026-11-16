package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WireStatusTest {
    @Test public void explicitStatusCarriesCorrelationAndExistingLease(){
        final Intent[] sent=new Intent[1];
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public void sendBroadcast(Intent intent,String permission,Bundle options){sent[0]=intent;}
        };
        Wire.requestStatus(context,"enable-request-1");
        assertNotNull(sent[0]);assertEquals(Wire.COMMAND,sent[0].getAction());assertEquals("android",sent[0].getPackage());
        assertEquals("STATUS",sent[0].getStringExtra("op"));assertEquals(Wire.PROTOCOL,sent[0].getIntExtra("protocol",0));
        assertEquals("enable-request-1",sent[0].getStringExtra("requestId"));assertSame(Wire.LEASE,sent[0].getExtras().getBinder("lease"));
        Wire.send(context,"STATUS");assertFalse(sent[0].hasExtra("requestId"));
    }
    @Test public void legacyNoticeIsRemovedWhileManualRestoreStillSendsItsCommand(){
        Context app=RuntimeEnvironment.getApplication();
        android.app.NotificationManager manager=app.getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(new android.app.NotificationChannel("test","旧小窗恢复",android.app.NotificationManager.IMPORTANCE_LOW));
        manager.notify(5,new android.app.Notification.Builder(app,"test").setSmallIcon(android.R.drawable.ic_menu_revert).build());
        assertEquals(1,manager.getActiveNotifications().length);
        Wire.clearLegacyNotification(app);
        Wire.clearLegacyNotification(app);
        assertEquals(0,manager.getActiveNotifications().length);
        assertNull(manager.getNotificationChannel("test"));
        var lifecycle=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup();
        WindowController controller=new WindowController(lifecycle.get());
        try {
            controller.restore();
            var broadcasts=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
            Intent sent=broadcasts.get(broadcasts.size()-1);
            assertEquals("RESTORE",sent.getStringExtra("op"));
            assertEquals(Wire.PROTOCOL,sent.getIntExtra("protocol",0));
            assertEquals("android",sent.getPackage());
            assertEquals(0,manager.getActiveNotifications().length);
        } finally {controller.close();lifecycle.pause().stop().destroy();}
    }
    @Test public void emptyOrUnboundedCorrelationIsRejected(){
        for(String requestId:new String[]{null,"","x".repeat(129)}){
            try{Wire.requestStatus(RuntimeEnvironment.getApplication(),requestId);fail("invalid request id");}
            catch(IllegalArgumentException expected){}
        }
    }
}
