package com.android.internal.telephony;

public class InboundSmsTracker {
    public String getDisplayAddress() { return "sender"; }
    public byte[] getPdu() { return null; }
    public String getFormat() { return "3gpp"; }
    public int getSubId() { return 1; }
    public int getMessageCount() { return 1; }
    public String getDeleteWhere() { return "_id=?"; }
    public String[] getDeleteWhereArgs() { return new String[]{"42"}; }
}
