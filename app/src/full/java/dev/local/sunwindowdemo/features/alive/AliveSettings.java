package dev.local.sunwindowdemo.features.alive;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Draft edits never mutate the applied photo or preferences consumed by a real Engine. */
public final class AliveSettings {
    public static final String APPLIED="alive_wallpaper",DRAFT="alive_wallpaper_draft";
    private AliveSettings() {}
    public static final class Snapshot {
        public final int aod,lock,home;
        public final String photo;
        public Snapshot(int aod,int lock,int home,String photo){
            if(aod<0||aod>5||lock<0||lock>3||home<6||home>9)throw new IllegalArgumentException("Unsupported Alive effect");
            if(photo==null||!photo.matches("alive-(?:photo|image-[a-f0-9-]+)\\.jpg"))throw new IllegalArgumentException("Invalid Alive photo");
            this.aod=aod;this.lock=lock;this.home=home;this.photo=photo;
        }
        public File photoFile(Context context){return new File(context.getNoBackupFilesDir(),photo);}
        @Override public boolean equals(Object other){return other instanceof Snapshot s&&aod==s.aod&&lock==s.lock&&home==s.home&&photo.equals(s.photo);}
        @Override public int hashCode(){return java.util.Objects.hash(aod,lock,home,photo);}
    }
    private static Snapshot read(SharedPreferences prefs){
        // Old installations keep their selected 1..5 style; new editors start with Sail.
        int aod=prefs.getInt("aod",prefs.contains("style")?prefs.getInt("style",1):0);
        int lock=prefs.getInt("lock",0),home=prefs.getInt("home",6);
        String photo=prefs.getString("photo","alive-photo.jpg");
        try{return new Snapshot(aod,lock,home,photo);}
        catch(IllegalArgumentException malformed){return new Snapshot(0,0,6,"alive-photo.jpg");}
    }
    public static synchronized Snapshot readApplied(Context context){return read(context.getSharedPreferences(APPLIED,0));}
    public static synchronized Snapshot readDraft(Context context){
        SharedPreferences prefs=context.getSharedPreferences(DRAFT,0);
        return prefs.contains("aod")?read(prefs):readApplied(context);
    }
    private static SharedPreferences.Editor edit(SharedPreferences prefs,Snapshot value){
        return prefs.edit().putInt("aod",value.aod).putInt("lock",value.lock).putInt("home",value.home).putString("photo",value.photo);
    }
    public static synchronized Snapshot beginEdit(Context context){
        Snapshot value=readApplied(context);saveDraft(context,value);return value;
    }
    public static synchronized void saveDraft(Context context,Snapshot value){
        if(!edit(context.getSharedPreferences(DRAFT,0),value).commit())throw new IllegalStateException("无法保存壁纸草稿");
    }
    public static synchronized Snapshot importDraftPhoto(Context context,Uri source)throws IOException{
        Snapshot previous=readDraft(context);
        String name="alive-image-"+UUID.randomUUID()+".jpg";
        File destination=new File(context.getNoBackupFilesDir(),name);
        AliveAssets.importPhoto(context,source,destination);
        Snapshot next=new Snapshot(previous.aod,previous.lock,previous.home,name);
        saveDraft(context,next);return next;
    }
    public static synchronized Snapshot applyDraft(Context context)throws IOException{
        Snapshot value=readDraft(context);
        if(!value.photoFile(context).isFile())throw new IOException("请先选择壁纸照片");
        // Publish the whole configuration in one preferences transaction; photo files are immutable.
        if(!edit(context.getSharedPreferences(APPLIED,0),value).putLong("applied_revision",System.nanoTime()).commit())
            throw new IOException("配置未能持久保存；当前进程可能已采用草稿，请重试确认保存");
        return value;
    }
}
