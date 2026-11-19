package dev.local.sunwindowdemo.features.gamemode;

import android.content.*;
import android.os.Bundle;
import dev.local.sunwindowdemo.Rules;
import java.util.*;

/** Game-mode configuration. The selected games also drive barrage display. */
public final class GameModeStore {
    public static final String AUTHORITY="dev.nativecomplements.module.gamemode";
    public static final String CHANGED="dev.nativecomplements.module.GAMEMODE_CHANGED";
    public static final String CORNER_REGIONS="dev.nativecomplements.module.GAME_CORNER_REGIONS";
    public static final int APP_LIMIT=512;
    public static final int QUICK_LIMIT=12;
    public static final String SIDE_LEFT="left",SIDE_RIGHT="right";
    public static SharedPreferences prefs(Context c){return c.createDeviceProtectedStorageContext().getSharedPreferences("game_mode",0);}

    public static Bundle read(Context c){
        SharedPreferences p=prefs(c);Bundle out=new Bundle();
        out.putBoolean("enabled",p.getBoolean("enabled",false));
        out.putBoolean("panel",p.getBoolean("panel",true));
        out.putString("side",side(p.getString("side",SIDE_LEFT)));
        out.putBoolean("brightness",p.getBoolean("brightness",false));
        out.putBoolean("corner_rejection",p.getBoolean("corner_rejection",false));
        out.putBoolean("corner_show_regions",p.getBoolean("corner_show_regions",false));
        Object radius=p.getAll().get("corner_radius_percent"); // Preserve the first test build's integer value.
        out.putFloat("corner_radius_percent",GameCornerGeometry.clamp(radius instanceof Number?((Number)radius).floatValue():GameCornerGeometry.DEFAULT_PERCENT));
        out.putStringArrayList("apps",new ArrayList<>(games(p)));
        out.putStringArrayList("quick_apps",new ArrayList<>(quickApps(p)));
        out.putStringArrayList("quick",new ArrayList<>(quick(p)));
        return out;
    }
    public static void enabled(Context c,boolean enabled){prefs(c).edit().putBoolean("enabled",enabled).apply();changed(c);}
    public static void panel(Context c,boolean panel){prefs(c).edit().putBoolean("panel",panel).apply();changed(c);}
    public static void side(Context c,String side){prefs(c).edit().putString("side",side(side)).apply();changed(c);}
    public static void cornerRejection(Context c,boolean enabled){prefs(c).edit().putBoolean("corner_rejection",enabled).apply();changed(c);}
    public static void showCornerRegions(Context c,boolean enabled){prefs(c).edit().putBoolean("corner_show_regions",enabled).apply();changed(c);}
    public static void cornerRadius(Context c,float percent){prefs(c).edit().putFloat("corner_radius_percent",GameCornerGeometry.clamp(percent)).apply();changed(c);}
    public static void brightness(Context c,boolean brightness){prefs(c).edit().putBoolean("brightness",brightness).apply();changed(c);}
    public static void select(Context c,String pkg,boolean selected){
        if(!Rules.validPackage(pkg))throw new IllegalArgumentException("game package");
        Set<String> apps=new HashSet<>(games(prefs(c)));
        if(selected)apps.add(pkg);else apps.remove(pkg);
        if(apps.size()>APP_LIMIT)throw new IllegalArgumentException("game app limit");
        prefs(c).edit().putStringSet("apps",apps).apply();changed(c);
    }
    public static void quick(Context c,String id,boolean selected){
        if(id==null||id.length()>32||!id.matches("[a-z0-9]+"))throw new IllegalArgumentException("quick action");
        List<String> next=new ArrayList<>(quick(prefs(c)));
        next.remove(id);if(selected)next.add(id);
        if(next.size()>QUICK_LIMIT)throw new IllegalArgumentException("quick action limit");
        prefs(c).edit().putString("quick",String.join(",",next)).apply();changed(c);
    }

    private static Set<String> games(SharedPreferences p){
        return p.getStringSet("apps",p.getStringSet("games",Set.of()));
    }
    /** Quick apps are deliberately empty on upgrade: games are not shortcuts. */
    public static List<String> quickApps(SharedPreferences p){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        String raw=p.getString("quick_apps","");
        if(raw!=null)for(String pkg:raw.split(","))if(Rules.validPackage(pkg)&&out.size()<APP_LIMIT)out.add(pkg);
        return new ArrayList<>(out);
    }
    public static void quickApp(Context c,String pkg,boolean selected){
        if(!Rules.validPackage(pkg))throw new IllegalArgumentException("quick app package");
        List<String> next=quickApps(prefs(c));
        if(selected){if(!next.contains(pkg))next.add(pkg);}else next.remove(pkg);
        if(next.size()>APP_LIMIT)throw new IllegalArgumentException("quick app limit");
        prefs(c).edit().putString("quick_apps",String.join(",",next)).apply();changed(c);
    }
    public static void moveQuickApp(Context c,String pkg,int offset){move(c,"quick_apps",quickApps(prefs(c)),pkg,offset);}
    public static void moveQuick(Context c,String id,int offset){move(c,"quick",quick(prefs(c)),id,offset);}
    private static void move(Context c,String key,List<String> items,String id,int offset){
        int from=items.indexOf(id),to=from+offset;
        if(from<0||(offset!=-1&&offset!=1)||to<0||to>=items.size())return;
        Collections.swap(items,from,to);
        prefs(c).edit().putString(key,String.join(",",items)).apply();changed(c);
    }

    public static String side(String value){return SIDE_RIGHT.equals(value)?SIDE_RIGHT:SIDE_LEFT;}
    public static List<String> quick(SharedPreferences p){
        String raw=p.getString("quick","screenshot,record,mute,dnd,rotation,current");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(raw!=null)for(String part:raw.split(","))if(part.matches("[a-z0-9]+")&&part.length()<=32)out.add(part);
        return new ArrayList<>(out);
    }

    private static void changed(Context c){
        c.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui"),null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        dev.local.sunwindowdemo.features.barrage.BarrageStore.gamesChanged(c);
    }
}
