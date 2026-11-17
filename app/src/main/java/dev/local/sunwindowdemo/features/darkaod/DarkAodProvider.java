package dev.local.sunwindowdemo.features.darkaod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;

/** Read-only policy bridge for this app and the same user's real system SystemUI UID. */
public final class DarkAodProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        int uid = Binder.getCallingUid();
        if (uid != context.getApplicationInfo().uid && !isSystemUi(context, uid))
            throw new SecurityException("dark AOD reader");
        if (!"system".equals(method)) throw new IllegalArgumentException("method");
        return DarkAodStore.systemSnapshot(context);
    }

    private static boolean isSystemUi(Context context, int uid) {
        if (!UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle()))
            return false;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(DarkAodStore.SYSTEM_UI, 0);
            return info.uid == uid && (info.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
