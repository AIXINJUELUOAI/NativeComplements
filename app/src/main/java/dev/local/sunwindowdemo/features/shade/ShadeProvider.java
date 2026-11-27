package dev.local.sunwindowdemo.features.shade;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;

/** Read-only projections for SystemUI, the home launcher, and the system display policy. */
public final class ShadeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (ShadeStore.CALL_REFRESH_READ.equals(method)) {
            if (uid != Process.SYSTEM_UID
                    || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(Process.SYSTEM_UID)))
                throw new SecurityException("floating call refresh reader");
            Bundle result = new Bundle();
            result.putBoolean("enabled", ShadeStore.floatingCallRefresh(getContext()));
            return result;
        }
        if ("drawer".equals(method)) {
            if (uid != getContext().getApplicationInfo().uid
                    && !dev.local.sunwindowdemo.core.LauncherTargets.isLauncherUid(getContext(), uid))
                throw new SecurityException("drawer reader");
            Bundle result = new Bundle();
            result.putBoolean("drawerMonet", ShadeStore.drawerMonet(getContext()));
            return result;
        }
        try {
            if (uid != getContext().getApplicationInfo().uid
                    && uid != getContext().getPackageManager().getApplicationInfo(ShadeStore.SYSTEM_UI, 0).uid)
                throw new SecurityException("shade reader");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new SecurityException(e);
        }
        if (!"read".equals(method)) throw new IllegalArgumentException("method");
        return ShadeStore.read(getContext());
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
