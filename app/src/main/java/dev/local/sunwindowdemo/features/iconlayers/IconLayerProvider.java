package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.Arrays;
import java.io.*;

/** Only our UI and the scoped Pixel Launcher may access diagnostic configuration. */
public final class IconLayerProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private boolean checkCaller() {
        int uid = Binder.getCallingUid();
        boolean owner = uid == getContext().getApplicationInfo().uid;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        boolean launcher = packages != null && Arrays.asList(packages).contains(IconLayerStore.LAUNCHER);
        if (!owner && !launcher) throw new SecurityException("icon layer bridge");
        return launcher;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        boolean launcher = checkCaller();
        if ("read".equals(method)) {
            Bundle result = IconLayerStore.read(getContext());
            // A large library stays on disk; send only installed matches across Binder.
            var installed = new java.util.HashSet<String>();
            for (var info : getContext().getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
                installed.add(info.activityInfo.packageName);
            var packages = IconLibrary.packages(getContext(), result.getString("generation", ""));
            packages.retainAll(installed); result.putStringArrayList("packages", packages);
            for (String key : new String[]{"originals", "restore"}) {
                var selected = result.getStringArrayList(key);
                if (selected != null) selected.retainAll(installed);
            }
            return result;
        }
        if ("report".equals(method) && launcher && extras != null) {
            IconLayerStore.report(getContext(), extras);
            if (extras.containsKey("loadedGeneration")) IconLibrary.acknowledge(getContext(), extras.getString("loadedGeneration", ""));
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("method");
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode)) throw new SecurityException("read only");
        var parts = uri.getPathSegments();
        if (parts.size() != 4 || !parts.get(0).equals("layers") || !IconLibrary.validGeneration(parts.get(1))
            || !IconArchive.validPackage(parts.get(2)) || !(parts.get(3).equals("0.png") || parts.get(3).equals("1.png")))
            throw new FileNotFoundException("icon path");
        try {
            if (!IconLibrary.packages(getContext(), parts.get(1)).contains(parts.get(2))) throw new FileNotFoundException("icon package");
            File file = new File(new File(IconLibrary.generation(getContext(), parts.get(1)), parts.get(2)), parts.get(3));
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
