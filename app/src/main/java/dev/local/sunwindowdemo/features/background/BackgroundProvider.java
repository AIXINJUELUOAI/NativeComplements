package dev.local.sunwindowdemo.features.background;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

public final class BackgroundProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = requireContext();
        int uid = Binder.getCallingUid();
        if ("process".equals(method)) {
            if (uid != Process.SYSTEM_UID) throw new SecurityException("background process reporter");
            if (extras != null) BackgroundStore.recordProcess(context, arg, extras.getString("name"));
            return Bundle.EMPTY;
        }
        if (uid != Process.SYSTEM_UID && uid != context.getApplicationInfo().uid) {
            throw new SecurityException("background settings reader");
        }
        if (!"read".equals(method)) throw new IllegalArgumentException("method");
        return BackgroundStore.read(context);
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
}
