package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.util.List;

/** A package entry or one real LauncherApps shortcut. Persist key(), not a launch Intent. */
final class ShortcutTarget {
    final String pkg, shortcutId, label, appLabel, section;
    final UserHandle user;
    final long serialNumber;
    final Drawable icon;

    ShortcutTarget(String pkg, String shortcutId, UserHandle user, long serial,
            String label, String appLabel, Drawable icon) {
        this(pkg,shortcutId,user,serial,label,appLabel,icon,AppCatalog.section(appLabel));
    }
    ShortcutTarget(String pkg, String shortcutId, UserHandle user, long serial,
            String label, String appLabel, Drawable icon, String section) {
        this.pkg=pkg; this.shortcutId=shortcutId; this.user=user; serialNumber=serial;
        this.label=label; this.appLabel=appLabel; this.icon=icon; this.section=section;
    }
    boolean isShortcut() { return shortcutId!=null; }
    String key() {
        return !isShortcut()?pkg:"shortcut:v1:"+serialNumber+":"+Uri.encode(pkg)+":"+Uri.encode(shortcutId);
    }
    static long serial(Context c, UserHandle user) {
        try { return c.getSystemService(UserManager.class).getSerialNumberForUser(user); }
        catch (RuntimeException failure) { return -1; }
    }
    static ShortcutTarget app(Context c, AppCatalog.App app) {
        UserHandle user=Process.myUserHandle();
        return new ShortcutTarget(app.pkg,null,user,serial(c,user),app.label,app.label,app.icon,app.section);
    }
    static boolean validKey(String key) {
        if (key==null || key.length()>8192) return false;
        if (!key.startsWith("shortcut:")) return Rules.validPackage(key);
        String[] parts=key.split(":",5);
        if (parts.length!=5 || !parts[0].equals("shortcut") || !parts[1].equals("v1")) return false;
        try {
            long serial=Long.parseLong(parts[2]);
            if (serial<0 || !Long.toString(serial).equals(parts[2])) return false;
            String pkg=Uri.decode(parts[3]), id=Uri.decode(parts[4]);
            return Rules.validPackage(pkg) && !id.isEmpty() && id.length()<=2048
                    && Uri.encode(pkg).equals(parts[3]) && Uri.encode(id).equals(parts[4]);
        } catch (RuntimeException failure) { return false; }
    }
    /** No ShortcutService query on the UI thread; the catalog later hydrates title/icon by key. */
    static ShortcutTarget fromKey(Context c, String key, List<AppCatalog.App> apps) {
        if (!validKey(key)) return null;
        boolean shortcut=key.startsWith("shortcut:");
        String[] parts=shortcut?key.split(":",5):null;
        String pkg=shortcut?Uri.decode(parts[3]):key;
        String id=shortcut?Uri.decode(parts[4]):null;
        UserHandle user=Process.myUserHandle();
        long serial=serial(c,user);
        if (shortcut) try {
            serial=Long.parseLong(parts[2]);
            user=c.getSystemService(UserManager.class).getUserForSerialNumber(serial);
            if (user==null) return null;
        } catch (RuntimeException failure) { return null; }
        AppCatalog.App owner=null;
        for (AppCatalog.App app:apps) if (app.pkg.equals(pkg)) { owner=app; break; }
        String appLabel=owner==null?pkg:owner.label;
        Drawable icon=owner==null?c.getDrawable(android.R.drawable.sym_def_app_icon):owner.icon;
        return new ShortcutTarget(pkg,id,user,serial,shortcut?"快捷功能":appLabel,appLabel,icon,owner==null?"#":owner.section);
    }
}
