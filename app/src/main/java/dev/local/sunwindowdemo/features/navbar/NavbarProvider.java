package dev.local.sunwindowdemo.features.navbar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Only the manager and verified SystemUI/Pixel launcher owners may read the one switch. */
public final class NavbarProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://" + NavbarStore.AUTHORITY);
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"system".equals(method)) throw new IllegalArgumentException("system read only");
        int uid = Binder.getCallingUid();
        if (uid != getContext().getApplicationInfo().uid) {
            boolean allowed = false;
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null)
                for (String name : packages)
                    if ("com.android.systemui".equals(name)
                            || dev.local.sunwindowdemo.core.LauncherTargets.isLauncher(name)) allowed = true;
            if (!allowed) throw new SecurityException("immersive navbar reader");
        }
        return NavbarStore.systemSnapshot(getContext());
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
