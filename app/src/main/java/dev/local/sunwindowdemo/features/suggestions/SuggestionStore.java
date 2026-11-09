package dev.local.sunwindowdemo.features.suggestions;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

public final class SuggestionStore {
    public static final String AUTHORITY="dev.local.sunwindowdemo.suggestions";
    public static final String CHANGED="dev.local.sunwindowdemo.SUGGESTIONS_CHANGED";
    public static final String SMS="dev.local.sunwindowdemo.SUGGESTION_SMS";
    public static final String AWAKE="dev.local.sunwindowdemo.SUGGESTION_AWAKE";
    public static SharedPreferences preferences(Context c){return c.createDeviceProtectedStorageContext().getSharedPreferences("suggestions",0);}
    public static Bundle read(Context c){
        SharedPreferences p=preferences(c);Bundle b=new Bundle();
        for(String key:new String[]{"enabled","torch","bluetooth","otp","links","maps","awake","hide_sms","window"})
            b.putBoolean(key,p.getBoolean(key,!key.equals("enabled")&&!key.equals("hide_sms")&&!key.equals("window")));
        b.putInt("delay",Math.max(50,Math.min(500,p.getInt("delay",100))));
        b.putBoolean("right",p.getBoolean("right",false));
        b.putString("bluetooth_app",p.getString("bluetooth_app",""));return b;
    }
    public static void setBoolean(Context c,String key,boolean value){
        if(!java.util.Set.of("enabled","torch","bluetooth","otp","links","maps","awake","hide_sms","window","right").contains(key))throw new IllegalArgumentException("suggestion setting");
        preferences(c).edit().putBoolean(key,value).apply();changed(c);
    }
    public static void setDelay(Context c,int value){preferences(c).edit().putInt("delay",Math.max(50,Math.min(500,value))).apply();changed(c);}
    public static void setApp(Context c,String pkg){
        if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||c.getPackageManager().getLaunchIntentForPackage(pkg)==null)throw new IllegalArgumentException("launchable application");
        preferences(c).edit().putString("bluetooth_app",pkg).apply();changed(c);
    }
    private static void changed(Context c){c.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui"),null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
}
