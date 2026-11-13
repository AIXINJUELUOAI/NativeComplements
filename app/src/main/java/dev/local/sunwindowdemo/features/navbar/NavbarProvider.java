package dev.local.sunwindowdemo.features.navbar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Arrays;

public final class NavbarProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String packageName, Bundle extras) {
        int uid = Binder.getCallingUid();
        if ("system".equals(method) || "status".equals(method)) {
            try {
                if (uid != 1000 && uid != getContext().getApplicationInfo().uid
                        && uid != getContext().getPackageManager().getApplicationInfo("com.android.systemui", 0).uid)
                    throw new SecurityException("navbar system reader");
            } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
            if ("status".equals(method)) {
                var edit = getContext().createDeviceProtectedStorageContext().getSharedPreferences("navbar_status", 0).edit();
                if (extras != null && extras.containsKey("host")) edit.putString("host", extras.getString("host", ""));
                if (extras != null && extras.containsKey("runtime")) edit.putString("runtime", extras.getString("runtime", ""));
                edit.apply();
                return Bundle.EMPTY;
            }
            return NavbarStore.systemSnapshot(getContext());
        }
        if (!"read".equals(method) || packageName == null) throw new IllegalArgumentException("read package");
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null || !Arrays.asList(packages).contains(packageName)) throw new SecurityException("navbar reader");
        return NavbarStore.read(getContext(), packageName);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
