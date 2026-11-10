package dev.local.sunwindowdemo.features.motion;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.*;

public final class MotionStore {
    public static final Uri URI=Uri.parse("content://dev.local.sunwindowdemo.motion/config");
    public static SharedPreferences prefs(Context c){return c.createDeviceProtectedStorageContext().getSharedPreferences("motion_access",0);}
    public static Bundle read(Context c){
        SharedPreferences p=prefs(c);Bundle b=new Bundle();b.putBoolean("enabled",p.getBoolean("enabled",false));
        b.putInt("default_mode",MotionPolicy.resolve(p.getInt("default_mode",0),null));
        ArrayList<String> packages=new ArrayList<>();ArrayList<Integer> modes=new ArrayList<>();
        try{JSONObject o=new JSONObject(p.getString("rules","{}"));Iterator<String> keys=o.keys();
            while(keys.hasNext()&&packages.size()<300){String key=keys.next();int mode=o.optInt(key,0);
                if(mode>=0&&mode<=2){packages.add(key);modes.add(mode);}}
        }catch(Exception ignored){}
        b.putStringArray("packages",packages.toArray(new String[0]));b.putIntArray("modes",modes.stream().mapToInt(Integer::intValue).toArray());
        b.putString("backend_version",p.getString("backend_version",""));b.putString("backend_error",p.getString("backend_error",""));
        b.putLong("backend_seen",p.getLong("backend_seen",0));b.putLong("updated_at",p.getLong("updated_at",0));
        return b;
    }
    public static void enabled(Context c,boolean value){save(c,prefs(c).edit().putBoolean("enabled",value));}
    public static synchronized void mode(Context c,String pkg,int mode){
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||mode<0||mode>2)throw new IllegalArgumentException("motion rule");
        try{JSONObject o=new JSONObject(prefs(c).getString("rules","{}"));
            if(mode==prefs(c).getInt("default_mode",0))o.remove(pkg);else {if(!o.has(pkg)&&o.length()>=300)throw new IllegalArgumentException("最多 300 条单独规则");o.put(pkg,mode);}
            save(c,prefs(c).edit().putString("rules",o.toString()));
        }catch(org.json.JSONException failure){throw new IllegalStateException(failure);}
    }
    public static synchronized void all(Context c,int mode){
        if(mode<0||mode>2)throw new IllegalArgumentException("motion default");
        save(c,prefs(c).edit().putInt("default_mode",mode).putString("rules","{}"));
    }
    private static void save(Context c,SharedPreferences.Editor e){e.putLong("updated_at",System.currentTimeMillis()).apply();c.getContentResolver().notifyChange(URI,null);}
    public static void backend(Context c,String version,String error){
        prefs(c).edit().putString("backend_version",version).putString("backend_error",error)
            .putLong("backend_seen",android.os.SystemClock.elapsedRealtime()).apply();
    }
}
