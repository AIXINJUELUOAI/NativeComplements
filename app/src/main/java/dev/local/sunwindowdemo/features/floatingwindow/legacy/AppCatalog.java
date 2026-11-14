package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Process;
import android.os.UserHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import android.icu.text.Transliterator;
import java.util.List;

final class AppCatalog {
    static final class App {
        final String pkg, label, section, sortKey;
        final Drawable icon;
        App(String p, String l, Drawable i) {
            pkg=p; label=l; icon=i; sortKey=AppCatalog.sortKey(l); section=AppCatalog.sectionFromKey(sortKey);
        }
        @Override public String toString() { return label; }
    }
    static Drawable normalized(Context c,Drawable icon){
        // Draw adaptive layers once at local origin; never share their mutable drawing cache
        // between the full-screen fan Canvas and ImageViews.
        if(icon instanceof AdaptiveIconDrawable)return raster(c,icon);
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        int width=Math.max(1,icon.getIntrinsicWidth()),height=Math.max(1,icon.getIntrinsicHeight());
        float scale=192f/Math.max(width,height);int w=Math.round(width*scale),h=Math.round(height*scale);
        Rect before=new Rect(icon.getBounds());icon.setBounds((192-w)/2,(192-h)/2,(192+w)/2,(192+h)/2);
        icon.draw(new Canvas(bitmap));icon.setBounds(before);
        return raster(c,new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE),new InsetDrawable(new BitmapDrawable(c.getResources(),bitmap),.18f)));
    }
    static Drawable icon(Context c,String pkg,Drawable original){
        if(!dev.local.sunwindowdemo.features.iconlayers.IconAssets.ready())return new ColorDrawable(Color.TRANSPARENT);
        Drawable adapted=dev.local.sunwindowdemo.features.iconlayers.IconAssets.colored(c,pkg);
        return normalized(c,adapted!=null?adapted:original);
    }
    private static Drawable raster(Context c,Drawable icon){
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Rect saved=new Rect(icon.getBounds());int alpha=icon.getAlpha();
        try{icon.setBounds(0,0,192,192);icon.setAlpha(255);icon.draw(new Canvas(bitmap));}
        finally{icon.setBounds(saved);icon.setAlpha(alpha);}
        return new BitmapDrawable(c.getResources(),bitmap);
    }
    static List<App> load(Context c) {
        List<App> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        LauncherApps launcher=c.getSystemService(LauncherApps.class);
        for (var activity : launcher.getActivityList(null,android.os.Process.myUserHandle())) {
            String pkg = activity.getComponentName().getPackageName();
            if (pkg.equals(Wire.PKG) || !seen.add(pkg)) continue;
            try { result.add(new App(pkg, activity.getLabel().toString(), icon(c,pkg,activity.getIcon(c.getResources().getDisplayMetrics().densityDpi)))); }
            catch (RuntimeException ignored) { }
        }
        result.sort(Comparator.comparing((App a)->a.section.equals("#")?"~"+a.sortKey:a.sortKey)
                .thenComparing(a->a.pkg));
        return result;
    }

    private static final ThreadLocal<Transliterator> PINYIN=ThreadLocal.withInitial(
            ()->Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper"));
    static String sortKey(String label) {
        String value=label==null?"":label.trim();
        try { return PINYIN.get().transliterate(value).toUpperCase(Locale.ROOT); }
        catch (RuntimeException unavailable) { return value.toUpperCase(Locale.ROOT); }
    }
    private static String sectionFromKey(String key) {
        return !key.isEmpty()&&key.charAt(0)>='A'&&key.charAt(0)<='Z'?key.substring(0,1):"#";
    }
    static String section(String label) { return sectionFromKey(sortKey(label)); }

    static final class Shortcuts {
        final List<ShortcutTarget> items;
        final boolean hostPermission;
        final String message;
        Shortcuts(List<ShortcutTarget> items,boolean hostPermission,String message) {
            this.items=List.copyOf(items);this.hostPermission=hostPermission;this.message=message;
        }
    }

    /** Worker-thread only. LauncherApps reports the permission of the actual SystemUI host. */
    static Shortcuts loadShortcuts(Context c,List<App> apps) { return loadShortcuts(c,apps,null); }

    /**
     * Loads manifest, dynamic and pinned shortcuts for the current user. When onlyKeys is
     * non-null, only the matching shortcuts have their labels and icons hydrated; this keeps
     * the startup fan refresh cheap while the management tab still asks for the full list.
     */
    static Shortcuts loadShortcuts(Context c,List<App> apps,List<String> onlyKeys) {
        HashSet<String> wanted=null;
        if(onlyKeys!=null){
            wanted=new HashSet<>();
            for(String key:onlyKeys)if(key!=null&&key.startsWith("shortcut:"))wanted.add(key);
            if(wanted.isEmpty())return new Shortcuts(List.of(),true,"");
        }
        LauncherApps launcher=c.getSystemService(LauncherApps.class);
        try {
            if(launcher==null||!launcher.hasShortcutHostPermission())
                return new Shortcuts(List.of(),false,"系统界面没有应用快捷方式权限");
            UserHandle user=Process.myUserHandle();
            long serial=ShortcutTarget.serial(c,user);
            if(serial<0)return new Shortcuts(List.of(),true,"暂未取得当前用户标识，请稍后重试");
            var query=new LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC|LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    |LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> available=launcher.getShortcuts(query,user);
            HashMap<String,App> owners=new HashMap<>();
            for(App app:apps)owners.put(app.pkg,app);
            ArrayList<ShortcutTarget> result=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            if(available!=null)for(ShortcutInfo info:available) {
                try {
                    if(!info.isEnabled())continue;
                    String pkg=info.getPackage(),id=info.getId();
                    if(pkg==null||id==null||!Wire.allowedPackage(pkg)||pkg.equals(Wire.PKG))continue;
                    String key=ShortcutTarget.shortcutKey(serial,pkg,id);
                    if(wanted!=null&&!wanted.contains(key))continue;
                    App owner=owners.get(pkg);
                    if(owner==null)continue;
                    CharSequence title=info.getShortLabel();
                    if(title==null||title.length()==0)title=info.getLongLabel();
                    if(title==null||title.length()==0)continue;
                    Drawable icon;
                    try{icon=launcher.getShortcutBadgedIconDrawable(info,c.getResources().getDisplayMetrics().densityDpi);}
                    catch(RuntimeException unavailableIcon){icon=null;}
                    icon=icon==null?owner.icon:normalized(c,icon);
                    ShortcutTarget target=ShortcutTarget.shortcut(pkg,id,user,serial,
                        title.toString(),owner.label,icon,owner.section);
                    if(ShortcutTarget.validKey(target.key())&&seen.add(target.key()))result.add(target);
                } catch(RuntimeException staleShortcut) {}
            }
            result.sort(Comparator.comparing((ShortcutTarget t)->{
                String sort=sortKey(t.appLabel);return sectionFromKey(sort).equals("#")?"~"+sort:sort;
            }).thenComparing(t->sortKey(t.label)).thenComparing(ShortcutTarget::key));
            String message=result.isEmpty()?(wanted==null?"暂无可用的应用快捷方式":"已选的应用快捷方式暂不可用"):"";
            return new Shortcuts(result,true,message);
        } catch(SecurityException denied) {
            return new Shortcuts(List.of(),false,"系统界面没有应用快捷方式权限");
        } catch(RuntimeException failure) {
            return new Shortcuts(List.of(),true,wanted==null?"应用快捷方式读取失败，请重试":"已选的应用快捷方式暂不可用");
        }
    }
}
