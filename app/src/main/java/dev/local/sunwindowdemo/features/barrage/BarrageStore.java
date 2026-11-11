package dev.local.sunwindowdemo.features.barrage;

import android.content.*;
import android.os.Bundle;
import dev.local.sunwindowdemo.Rules;
import java.util.*;

public final class BarrageStore {
    public static final String AUTHORITY="dev.nativecomplements.module.barrage";
    public static final String CHANGED="dev.nativecomplements.module.BARRAGE_CHANGED";
    public static SharedPreferences prefs(Context c){return c.createDeviceProtectedStorageContext().getSharedPreferences("barrage",0);}
    private static void migrate(Context c){
        SharedPreferences p=prefs(c);if(p.contains("migrated"))return;
        SharedPreferences old=c.createDeviceProtectedStorageContext().getSharedPreferences("game_mode",0);
        p.edit().putBoolean("enabled",old.getBoolean("enabled",false)&&old.getBoolean("barrage",false))
            .putStringSet("apps",new HashSet<>(old.getStringSet("games",Set.of())))
            .putBoolean("migrated",true).commit();
    }
    public static Bundle read(Context c){
        migrate(c);SharedPreferences p=prefs(c);Bundle out=new Bundle();
        out.putBoolean("enabled",p.getBoolean("enabled",false));
        out.putStringArrayList("apps",new ArrayList<>(p.getStringSet("apps",Set.of())));return out;
    }
    public static void enabled(Context c,boolean enabled){migrate(c);prefs(c).edit().putBoolean("enabled",enabled).apply();changed(c);}
    public static void select(Context c,String pkg,boolean selected){
        if(!Rules.validPackage(pkg))throw new IllegalArgumentException("barrage package");
        migrate(c);Set<String> apps=new HashSet<>(prefs(c).getStringSet("apps",Set.of()));
        if(selected)apps.add(pkg);else apps.remove(pkg);
        if(apps.size()>512)throw new IllegalArgumentException("barrage app limit");
        prefs(c).edit().putStringSet("apps",apps).apply();changed(c);
    }
    private static void changed(Context c){c.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui"),null,
        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
}
