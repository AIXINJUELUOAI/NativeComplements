package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.LauncherApps;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import android.content.pm.ShortcutInfo;
import android.icu.text.Transliterator;
import android.os.Process;
import android.os.UserHandle;
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
            try { result.add(new App(pkg, activity.getLabel().toString(), normalized(c,activity.getIcon(c.getResources().getDisplayMetrics().densityDpi)))); }
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
        final boolean hostPermission,cacheable;
        final int revision;
        final String message;
        Shortcuts(List<ShortcutTarget> items,boolean permission,String message) {this(items,permission,message,true);}
        Shortcuts(List<ShortcutTarget> items,boolean permission,String message,boolean cacheable) {
            this(items,permission,message,cacheable,-1);
        }
        private Shortcuts(List<ShortcutTarget> items,boolean permission,String message,boolean cacheable,int revision) {
            this.items=List.copyOf(items);hostPermission=permission;this.message=message;this.cacheable=cacheable;this.revision=revision;
        }
        boolean isCurrent(){return revision==shortcutRevision.get();}
    }
    private static final java.util.concurrent.atomic.AtomicInteger shortcutRevision=new java.util.concurrent.atomic.AtomicInteger();
    private static volatile Shortcuts shortcutCache;
    static Shortcuts cachedShortcuts(){Shortcuts cached=shortcutCache;return cached!=null&&cached.isCurrent()?cached:null;}
    static void invalidateShortcuts(){shortcutRevision.incrementAndGet();}
    /** Worker-thread only. Reuse the process cache until packages or shortcuts change. */
    static synchronized Shortcuts loadShortcuts(Context c) {
        Shortcuts cached=cachedShortcuts();if(cached!=null)return cached;
        int revision=shortcutRevision.get();
        Shortcuts queried=queryShortcuts(c);
        Shortcuts result=new Shortcuts(queried.items,queried.hostPermission,queried.message,queried.cacheable,revision);
        if(result.cacheable&&result.isCurrent())shortcutCache=result;
        return result;
    }
    private static Shortcuts queryShortcuts(Context c) {
        LauncherApps launcher=c.getSystemService(LauncherApps.class);
        try {
            if (launcher==null||!launcher.hasShortcutHostPermission())
                return new Shortcuts(List.of(),false,"当前系统界面没有应用快捷功能的宿主权限",false);
            UserHandle user=Process.myUserHandle();
            long serial=ShortcutTarget.serial(c,user);
            if (serial<0) return new Shortcuts(List.of(),true,"暂未取得当前用户的标识，请稍后重试",false);
            var query=new LauncherApps.ShortcutQuery().setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC|LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            |LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> available=launcher.getShortcuts(query,user);
            HashMap<String,App> owners=new HashMap<>();
            ArrayList<ShortcutTarget> result=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            if(available!=null) for(ShortcutInfo info:available) {
                if(!info.isEnabled()||info.getPackage().equals(Wire.PKG)) continue;
                try {
                    String pkg=info.getPackage(); App owner=owners.get(pkg);
                    if(owner==null) {
                        var pm=c.getPackageManager(); var application=pm.getApplicationInfo(pkg,0);
                        owner=new App(pkg,pm.getApplicationLabel(application).toString(),normalized(c,pm.getApplicationIcon(application)));
                        owners.put(pkg,owner);
                    }
                    CharSequence title=info.getShortLabel();
                    if(title==null||title.length()==0) title=info.getLongLabel();
                    if(title==null||title.length()==0) continue;
                    Drawable icon;
                    try { icon=launcher.getShortcutBadgedIconDrawable(info,c.getResources().getDisplayMetrics().densityDpi); }
                    catch(RuntimeException unavailableIcon) { icon=null; }
                    if(icon==null) icon=owner.icon; else icon=normalized(c,icon);
                    ShortcutTarget target=new ShortcutTarget(pkg,info.getId(),user,serial,title.toString(),owner.label,icon,owner.section);
                    if(ShortcutTarget.validKey(target.key())&&seen.add(target.key())) result.add(target);
                } catch(Exception staleShortcut) { }
            }
            result.sort(Comparator.comparing((ShortcutTarget t)->{
                String sort=sortKey(t.appLabel); return sectionFromKey(sort).equals("#")?"~"+sort:sort;
            }).thenComparing(t->sortKey(t.label)).thenComparing(ShortcutTarget::key));
            return new Shortcuts(result,true,result.isEmpty()?"暂无可用的应用快捷功能":"");
        } catch(SecurityException denied) {
            return new Shortcuts(List.of(),false,"当前系统界面没有应用快捷功能的宿主权限",false);
        } catch(RuntimeException failure) {
            return new Shortcuts(List.of(),true,"应用快捷功能读取失败，请重新打开面板重试",false);
        }
    }
}
