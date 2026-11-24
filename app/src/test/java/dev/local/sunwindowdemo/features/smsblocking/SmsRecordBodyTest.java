package dev.local.sunwindowdemo.features.smsblocking;

import android.content.Context;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class SmsRecordBodyTest {
    private final Context context = RuntimeEnvironment.getApplication();
    @After public void clear() { SmsBlockingStore.clearRecords(context); }

    // Ordinary SMS-DELIVER, UCS2 user data, sender 12; no platform mocking of createFromPdu.
    static byte[] pdu(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_16BE);
        byte[] header = {0, 4, 2, (byte) 0x81, 0x21, 0, 8, 0x62, 0x11, 0x52, 0, 0, 0, 0, (byte) body.length};
        byte[] result = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, result, header.length, body.length);
        return result;
    }

    @Test public void nativePdusJoinInOrderAndUnknownOrPortMessagesStayUnknown() {
        assertEquals("短信正文", SmsBlockingModule.assembled(new byte[][]{pdu("短信"), pdu("正文")}, -1, "3gpp"));
        assertNull(SmsBlockingModule.assembled(new byte[][]{pdu("短信")}, 9200, "3gpp"));
        assertNull(SmsBlockingModule.assembled(new byte[][]{pdu("短信")}, -1, null));
        assertNull(SmsBlockingModule.assembled(new byte[][]{pdu("短信")}, -1, "unknown"));
        assertNull(SmsBlockingModule.assembled(new byte[][]{pdu("短信"), new byte[0]}, -1, "3gpp"));
        assertNull(SmsBlockingModule.assembled(new byte[][]{pdu("短信"), null}, -1, "3gpp"));
        assertNull(SmsBlockingModule.assembled(new byte[0][], -1, "3gpp"));
        byte[][] longMessage = new byte[13][];
        Arrays.fill(longMessage, pdu("文".repeat(60)));
        assertEquals(701, SmsBlockingModule.assembled(longMessage, -1, "3gpp").length());
    }

    @Test public void recordsBoundTextWithoutSplittingEmojiPreserveOldRowsAndClearEverything() throws Exception {
        SmsBlockingStore.preferences(context).edit().putString("records", "[{\"sender\":\"old\",\"time\":1}]").commit();
        Bundle old = SmsBlockingStore.readRecords(context).get(0);
        assertEquals("", old.getString("body")); assertFalse(old.getBoolean("body_truncated"));
        Bundle entry = new Bundle();
        entry.putString("body", "文".repeat(699) + "😀" + "尾");
        assertTrue(SmsBlockingStore.recordBlocked(context, entry));
        Bundle saved = SmsBlockingStore.readRecords(context).get(0);
        assertEquals("文".repeat(699), saved.getString("body")); assertTrue(saved.getBoolean("body_truncated"));
        assertFalse(SmsBlockingStore.readConfig(context).containsKey("body"));
        entry.putString("body", "字".repeat(700));
        SmsBlockingStore.recordBlocked(context, entry);
        saved = SmsBlockingStore.readRecords(context).get(0);
        assertEquals(700, saved.getString("body").length()); assertFalse(saved.getBoolean("body_truncated"));
        for (int i = 0; i < 201; i++) SmsBlockingStore.recordBlocked(context, entry);
        assertEquals(200, SmsBlockingStore.readRecords(context).size());
        SmsBlockingStore.clearRecords(context);
        assertTrue(SmsBlockingStore.readRecords(context).isEmpty());
        assertFalse(SmsBlockingStore.preferences(context).contains("records"));
    }
}
