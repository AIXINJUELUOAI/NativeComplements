package dev.local.sunwindowdemo.features.barrage;

import android.content.*;
import android.os.Bundle;
import dev.local.sunwindowdemo.Rules;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BarrageStore {
    public static final String AUTHORITY="dev.nativecomplements.module.barrage";
    public static final String CHANGED="dev.nativecomplements.module.BARRAGE_CHANGED";
    public static final int HISTORY_LIMIT=100;
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
    public static int historyCount(Context c){return history(c).length();}

    /** Newest first. Bad or obsolete rows are ignored rather than breaking the page. */
    public static ArrayList<Bundle> readHistory(Context c){
        JSONArray array=history(c);ArrayList<Bundle> result=new ArrayList<>();
        for(int i=0;i<array.length();i++){
            JSONObject item=array.optJSONObject(i);if(item==null)continue;
            String pkg=item.optString("package","");if(!Rules.validPackage(pkg))continue;
            Bundle entry=new Bundle();
            entry.putString("package",pkg);
            entry.putLong("time",item.optLong("time",0));
            entry.putString("app",item.optString("app",""));
            entry.putString("source",item.optString("source",""));
            entry.putString("text",item.optString("text",""));
            result.add(entry);
        }
        return result;
    }

    public static boolean addHistory(Context c,Bundle entry){
        String pkg=entry.getString("package","");if(!Rules.validPackage(pkg))throw new IllegalArgumentException("barrage history package");
        long time=entry.getLong("time",0);if(time<=0)time=System.currentTimeMillis();
        String app=trim(entry.getString("app",""),80),source=trim(entry.getString("source",""),80),text=trim(entry.getString("text",""),240);
        if(source.isEmpty()&&text.isEmpty())throw new IllegalArgumentException("barrage history content");
        try{
            JSONObject row=new JSONObject().put("package",pkg).put("time",time).put("app",app).put("source",source).put("text",text);
            JSONArray old=history(c),next=new JSONArray().put(row);
            for(int i=0;i<old.length()&&next.length()<HISTORY_LIMIT;i++){
                JSONObject item=old.optJSONObject(i);if(item!=null)next.put(item);
            }
            return prefs(c).edit().putString("history",next.toString()).commit();
        }catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
    }

    public static void clearHistory(Context c){prefs(c).edit().remove("history").apply();}

    private static JSONArray history(Context c){
        try{return new JSONArray(prefs(c).getString("history","[]"));}catch(Exception ignored){return new JSONArray();}
    }
    private static String trim(String value,int max){
        String text=value==null?"":value.replace('\n',' ').replace('\r',' ').trim();
        return text.length()>max?text.substring(0,max):text;
    }

    private static void changed(Context c){c.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui"),null,
        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
}
