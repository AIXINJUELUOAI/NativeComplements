package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

final class AppCatalog {
    static final class App {
        final String pkg, label;
        final Drawable icon;
        App(String p, String l, Drawable i) { pkg=p; label=l; icon=i; }
        @Override public String toString() { return label; }
    }
    static List<App> load(Context c) {
        List<App> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            try { result.add(new App(pkg, ri.loadLabel(c.getPackageManager()).toString(), ri.loadIcon(c.getPackageManager()))); }
            catch (RuntimeException ignored) { }
        }
        result.sort(Comparator.comparing(a -> a.label, java.text.Collator.getInstance()));
        return result;
    }
}
