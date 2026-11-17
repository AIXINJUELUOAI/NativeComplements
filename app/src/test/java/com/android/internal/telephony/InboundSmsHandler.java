package com.android.internal.telephony;

import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Message;

public class InboundSmsHandler {
    public final Context mContext;
    public final ContentResolver mResolver;
    public static final Uri sRawUriPermanentDelete = Uri.parse("content://sms/raw/permanentDelete");
    public InboundSmsHandler(Context context) { mContext = context; mResolver = context.getContentResolver(); }
    private boolean processMessagePart(InboundSmsTracker tracker) { return false; }
    public static class SmsBroadcastReceiver { }
    private boolean filterSms(byte[][] pdus, int port, InboundSmsTracker tracker, SmsBroadcastReceiver receiver, boolean unlocked, boolean block) { return false; }
    private void deleteFromRawTable(String where, String[] args, int type) { }
    public static class DeliveringState { public boolean processMessage(Message message) { return false; } }
}
