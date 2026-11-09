package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Config {
    final SharedPreferences prefs;
    static volatile SharedPreferences hostPreferences;
    Config(Context c) { prefs = hostPreferences!=null?hostPreferences:c.getSharedPreferences("corner", Context.MODE_PRIVATE); }
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    int number(String key, int def, int min, int max) { return Rules.clamp(prefs.getInt(key, def), min, max); }
    static final int HEAT_MIN=36,HEAT_MAX=72;
    int heat() { return number("heat", HEAT_MIN, HEAT_MIN, HEAT_MAX); }
    boolean hideTopHint() { return prefs.getBoolean("hideTopHint", false); }
    int distance() { return number("distance", 42, 24, 100); }
    int selectionDistance() { return number("selectionDistance", 90, 50, 100); }
    int radius() { return number("radius", 180, 120, 260); }
    int icon() { return number("icon", 44, 32, 56); }
    boolean landscapeOff() { return prefs.getBoolean("landscape", true); }
    boolean keyboardOff() { return prefs.getBoolean("keyboard", true); }
    boolean hideRecents() { return prefs.getBoolean("hideRecents", false); }
    boolean doubleClose() { return false; }
    boolean showHeat() { return prefs.getBoolean("showHeat", false); }
    List<String> apps() {
        Set<String> unique = new LinkedHashSet<>();
        for (String item : prefs.getString("apps", "").split(",")) {
            if (Rules.validPackage(item) && unique.size() < 5) unique.add(item);
        }
        return new ArrayList<>(unique);
    }
    List<String> targets() {
        if(!prefs.contains("targets"))return apps();
        Set<String> unique=new LinkedHashSet<>();
        try{org.json.JSONArray array=new org.json.JSONArray(prefs.getString("targets","[]"));
            for(int n=0;n<array.length()&&unique.size()<5;n++){
                String key=array.optString(n,"");if(ShortcutTarget.validKey(key))unique.add(key);
            }
        }catch(org.json.JSONException ignored){}
        return new ArrayList<>(unique);
    }
    java.util.Map<String,String> targetLabels(){
        java.util.Map<String,String> result=new java.util.HashMap<>();
        try{org.json.JSONObject labels=new org.json.JSONObject(prefs.getString("targetLabels","{}"));
            for(String key:targets()){String label=labels.optString(key,"");if(!label.isBlank())result.put(key,label);}
        }catch(org.json.JSONException ignored){}
        return result;
    }
    void targets(List<String> keys){targets(keys,null);}
    void targets(List<String> keys,List<String> names) {
        if(keys==null||keys.size()>5)throw new IllegalArgumentException("shortcut limit");
        if(names!=null&&(names.size()!=keys.size()||names.stream().anyMatch(n->n==null||n.length()>200)))throw new IllegalArgumentException("shortcut names");
        org.json.JSONObject labels=new org.json.JSONObject();java.util.Map<String,String> previous=targetLabels();
        for(int n=0;n<keys.size();n++)try{labels.put(keys.get(n),names==null?previous.getOrDefault(keys.get(n),""):names.get(n));}catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
        Set<String> unique=new LinkedHashSet<>();List<String> packages=new ArrayList<>();
        for(String key:keys){
            if(!ShortcutTarget.validKey(key)||!unique.add(key))throw new IllegalArgumentException("shortcut key");
            if(Rules.validPackage(key))packages.add(key);
        }
        if(!prefs.edit().putString("targets",new org.json.JSONArray(keys).toString())
            .putString("apps",String.join(",",packages)).putString("targetLabels",labels.toString()).commit())throw new IllegalStateException("shortcut save failed");
    }
    void apps(List<String> apps) { targets(apps); }
    Set<String> blocked() { return new LinkedHashSet<>(prefs.getStringSet("blocked", Set.of())); }
}
