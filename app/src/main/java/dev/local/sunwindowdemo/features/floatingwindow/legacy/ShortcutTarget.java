package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.util.List;

/** An application entry. Old third-party shortcut keys are intentionally discarded. */
final class ShortcutTarget {
    final String pkg,label,section;
    final Drawable icon;
    private ShortcutTarget(AppCatalog.App app){pkg=app.pkg;label=app.label;section=app.section;icon=app.icon;}
    String key(){return pkg;}
    static ShortcutTarget app(Context c,AppCatalog.App app){return new ShortcutTarget(app);}
    static boolean validKey(String key){return Rules.validPackage(key);}
    static ShortcutTarget fromKey(Context c,String key,List<AppCatalog.App> apps){
        if(!validKey(key))return null;
        for(AppCatalog.App app:apps)if(app.pkg.equals(key))return new ShortcutTarget(app);
        return null;
    }
}
