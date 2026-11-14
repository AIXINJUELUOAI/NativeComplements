package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.util.List;

/** An installed application, one LauncherApps shortcut, or a quick-function action. */
final class ShortcutTarget {
    private static final String SHORTCUT_PREFIX = "shortcut:v1:";
    final String pkg,label,section,action,shortcutId,appLabel;
    final Drawable icon;
    final boolean quick;
    final UserHandle user;
    final long serial;

    private ShortcutTarget(AppCatalog.App app,UserHandle user,long serial){
        pkg=app.pkg;label=app.label;section=app.section;icon=app.icon;
        quick=false;action=null;shortcutId=null;appLabel=app.label;this.user=user;this.serial=serial;
    }
    private ShortcutTarget(Context c,QuickActionCatalog.Action value){
        pkg="";label=value.label();section="#";icon=QuickActionCatalog.icon(c,value);
        quick=true;action=value.id();shortcutId=null;appLabel="";user=null;serial=-1;
    }
    private ShortcutTarget(String pkg,String shortcutId,UserHandle user,long serial,
            String label,String appLabel,Drawable icon,String section){
        this.pkg=pkg;this.shortcutId=shortcutId;this.user=user;this.serial=serial;
        this.label=label;this.appLabel=appLabel;this.icon=icon;this.section=section;
        quick=false;action=null;
    }

    boolean isShortcut(){return shortcutId!=null;}
    String key(){
        if(quick)return QuickActionCatalog.key(action);
        if(shortcutId==null)return pkg;
        return shortcutKey(serial,pkg,shortcutId);
    }
    static String shortcutKey(long serial,String pkg,String shortcutId){
        return SHORTCUT_PREFIX+serial+":"+Uri.encode(pkg)+":"+Uri.encode(shortcutId);
    }
    static long serial(Context c,UserHandle user){
        try{return c.getSystemService(UserManager.class).getSerialNumberForUser(user);}
        catch(RuntimeException failure){return -1;}
    }
    static ShortcutTarget app(Context c,AppCatalog.App app){
        UserHandle user=Process.myUserHandle();
        return new ShortcutTarget(app,user,serial(c,user));
    }
    static ShortcutTarget shortcut(String pkg,String shortcutId,UserHandle user,long serial,
            String label,String appLabel,Drawable icon,String section){
        return new ShortcutTarget(pkg,shortcutId,user,serial,label,appLabel,icon,section);
    }
    static boolean validKey(String key){
        if(key==null||key.length()>8192)return false;
        if(!key.startsWith("shortcut:"))return Rules.validPackage(key)||QuickActionCatalog.validKey(key);
        String[] parts=key.split(":",5);
        if(parts.length!=5||!parts[0].equals("shortcut")||!parts[1].equals("v1"))return false;
        try{
            long serial=Long.parseLong(parts[2]);
            if(serial<0||!Long.toString(serial).equals(parts[2]))return false;
            String pkg=Uri.decode(parts[3]),id=Uri.decode(parts[4]);
            return Rules.validPackage(pkg)&&!id.isEmpty()&&id.length()<=2048
                &&Uri.encode(pkg).equals(parts[3])&&Uri.encode(id).equals(parts[4]);
        }catch(RuntimeException failure){return false;}
    }
    static ShortcutTarget fromKey(Context c,String key,List<AppCatalog.App> apps){
        return fromKey(c,key,apps,null);
    }
    /** The saved label is only a fallback until AppCatalog resolves the live shortcut metadata. */
    static ShortcutTarget fromKey(Context c,String key,List<AppCatalog.App> apps,String savedLabel){
        QuickActionCatalog.Action action=QuickActionCatalog.fromKey(key);
        if(action!=null)return new ShortcutTarget(c,action);
        if(!validKey(key))return null;
        boolean shortcut=key.startsWith("shortcut:");
        String pkg=key,id=null;UserHandle user=Process.myUserHandle();long serial=serial(c,user);
        if(shortcut){
            String[] parts=key.split(":",5);
            pkg=Uri.decode(parts[3]);id=Uri.decode(parts[4]);
            try{
                serial=Long.parseLong(parts[2]);
                UserManager users=c.getSystemService(UserManager.class);
                user=users==null?null:users.getUserForSerialNumber(serial);
            }catch(RuntimeException failure){return null;}
            if(user==null||!Process.myUserHandle().equals(user))return null;
        }
        AppCatalog.App owner=null;
        for(AppCatalog.App app:apps)if(app.pkg.equals(pkg)){owner=app;break;}
        if(owner==null)return null;
        if(shortcut){
            String label=savedLabel==null||savedLabel.isBlank()?"快捷方式":savedLabel;
            return new ShortcutTarget(pkg,id,user,serial,label,owner.label,owner.icon,owner.section);
        }
        return new ShortcutTarget(owner,user,serial);
    }
}
