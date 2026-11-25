package dev.local.sunwindowdemo;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class BarrageHeadsUpTest {
    public static final class Entry { public StatusBarNotification mSbn; }
    public static final class Decision {
        public final String reason;public final boolean shouldInterrupt;
        public Decision(String reason,boolean interrupt){this.reason=reason;shouldInterrupt=interrupt;}
    }
    private BarrageHeadsUp.Targets targets()throws Exception{
        return new BarrageHeadsUp.Targets(null,null,Entry.class.getField("mSbn"),Decision.class.getField("shouldInterrupt"),
            Decision.class.getConstructor(String.class,boolean.class),List.of());
    }
    private Entry entry(){
        Entry e=new Entry();e.mSbn=new StatusBarNotification("example.chat","example.chat",1,null,12345,0,0,new Notification(),Process.myUserHandle(),100);return e;
    }
    @Test public void acceptedHeadsUpGetsANewDecisionWithoutMutatingTheSharedNativeVerdict()throws Exception{
        Entry e=entry();Decision nativeDecision=new Decision("native",true);
        Decision replacement=(Decision)BarrageHeadsUp.replace(targets(),e,nativeDecision,sbn->{assertSame(e.mSbn,sbn);return true;});
        assertNotSame(nativeDecision,replacement);assertFalse(replacement.shouldInterrupt);assertEquals("barrage",replacement.reason);
        assertTrue(nativeDecision.shouldInterrupt);assertEquals("native",nativeDecision.reason);
        assertSame(nativeDecision,BarrageHeadsUp.replace(targets(),entry(),nativeDecision,sbn->false));
    }
    @Test public void nativeDeniedAndFullScreenIntentDecisionsAreNeverOfferedToBarrage()throws Exception{
        AtomicInteger offered=new AtomicInteger();Entry e=entry();Decision no=new Decision("native-denied",false);
        assertSame(no,BarrageHeadsUp.replace(targets(),e,no,sbn->{offered.incrementAndGet();return true;}));
        e.mSbn.getNotification().fullScreenIntent=PendingIntent.getActivity(RuntimeEnvironment.getApplication(),1,new Intent("example.FSI"),PendingIntent.FLAG_IMMUTABLE);
        Decision yes=new Decision("native",true);
        assertSame(yes,BarrageHeadsUp.replace(targets(),e,yes,sbn->{offered.incrementAndGet();return true;}));
        e.mSbn=null;
        assertSame(yes,BarrageHeadsUp.replace(targets(),e,yes,sbn->{offered.incrementAndGet();return true;}));
        assertEquals(0,offered.get());assertNull(BarrageHeadsUp.replace(targets(),e,null,sbn->true));
    }
}
