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
    @Test public void emptyOrUnboundedCorrelationIsRejected(){
        for(String requestId:new String[]{null,"","x".repeat(129)}){
            try{Wire.requestStatus(RuntimeEnvironment.getApplication(),requestId);fail("invalid request id");}
            catch(IllegalArgumentException expected){}
        }
    }
}
