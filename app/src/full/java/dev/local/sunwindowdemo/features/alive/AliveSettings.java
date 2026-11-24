package dev.local.sunwindowdemo.features.alive;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Drafts remain credential encrypted; only a complete applied snapshot is published for Direct Boot. */
public final class AliveSettings {
    public static final String APPLIED="alive_wallpaper",DRAFT="alive_wallpaper_draft";
    private record ManualColor(Snapshot snapshot,long deadline) {}
    private static final AtomicReference<ManualColor> manualColor=new AtomicReference<>();
    private AliveSettings() {}
    public static Context credential(Context context){
        Context source=context.isDeviceProtectedStorage()?context.getApplicationContext():context;
        if(source==null||source.isDeviceProtectedStorage())throw new IllegalStateException("Credential context unavailable");
        return source;
    }
    public static SharedPreferences runtimePreferences(Context context){return AliveAssets.runtime(context).getSharedPreferences(APPLIED,0);}
    public static final class Snapshot {
        public final int aod,lock,home;
        public final String photo;
        public final AliveCrop crop;
        public Snapshot(int aod,int lock,int home,String photo){
            this(aod,lock,home,photo,AliveCrop.CENTER);
        }
        public Snapshot(int aod,int lock,int home,String photo,AliveCrop crop){
            if(aod<0||aod>5||lock<0||lock>3||home<6||home>9)throw new IllegalArgumentException("Unsupported Alive effect");
            if(photo==null||!photo.matches("alive-(?:photo|image-[a-f0-9-]+)\\.jpg"))throw new IllegalArgumentException("Invalid Alive photo");
            this.aod=aod;this.lock=lock;this.home=home;this.photo=photo;this.crop=java.util.Objects.requireNonNull(crop);
        }
        public File photoFile(Context context){return new File(context.getNoBackupFilesDir(),photo);}
        public Snapshot withCrop(AliveCrop value){return new Snapshot(aod,lock,home,photo,value);}
        @Override public boolean equals(Object other){return other instanceof Snapshot s&&aod==s.aod&&lock==s.lock&&home==s.home&&photo.equals(s.photo)&&crop.equals(s.crop);}
        @Override public int hashCode(){return java.util.Objects.hash(aod,lock,home,photo,crop);}
    }
    private static Snapshot read(SharedPreferences prefs){
        int aod=prefs.getInt("aod",prefs.contains("style")?prefs.getInt("style",1):0);
        int lock=prefs.getInt("lock",0),home=prefs.getInt("home",6);
        String photo=prefs.getString("photo","alive-photo.jpg");
        AliveCrop crop=new AliveCrop(prefs.getFloat("crop_x",.5f),prefs.getFloat("crop_y",.5f),prefs.getFloat("crop_zoom",1f));
        try{return new Snapshot(aod,lock,home,photo,crop);}
        catch(IllegalArgumentException malformed){return new Snapshot(0,0,6,"alive-photo.jpg");}
    }
    private static AtomicFile appliedFile(Context context){return new AtomicFile(new File(AliveAssets.runtime(context).getNoBackupFilesDir(),"alive-applied.json"));}
    private static boolean hasApplied(Context context){
        File file=appliedFile(context).getBaseFile();return file.exists()||new File(file.getPath()+".bak").exists();
    }
    public static synchronized Snapshot readApplied(Context context){
        AtomicFile file=appliedFile(context);
        if(hasApplied(context))try{
            if(file.getBaseFile().length()>4096)throw new IOException("Applied snapshot too large");
            JSONObject value=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
            AliveCrop crop=new AliveCrop((float)value.optDouble("crop_x",.5),
                    (float)value.optDouble("crop_y",.5),(float)value.optDouble("crop_zoom",1));
            return new Snapshot(value.getInt("aod"),value.getInt("lock"),value.getInt("home"),value.getString("photo"),crop);
        }catch(Exception invalid){throw new IllegalStateException("Alive 已应用快照损坏；不会回退采用旧配置",invalid);}
        if(!context.getSystemService(UserManager.class).isUserUnlocked())throw new IllegalStateException("Alive 尚未准备首次解锁前的壁纸数据");
        return read(credential(context).getSharedPreferences(APPLIED,0));
    }
    public static synchronized Snapshot readDraft(Context context){
        SharedPreferences prefs=credential(context).getSharedPreferences(DRAFT,0);
        return prefs.contains("aod")?read(prefs):readApplied(context);
    }
    private static SharedPreferences.Editor edit(SharedPreferences prefs,Snapshot value){
        return prefs.edit().putInt("aod",value.aod).putInt("lock",value.lock).putInt("home",value.home).putString("photo",value.photo)
                .putFloat("crop_x",value.crop.x()).putFloat("crop_y",value.crop.y()).putFloat("crop_zoom",value.crop.zoom());
    }
    public static synchronized Snapshot beginEdit(Context context){
        Snapshot value=readApplied(context);saveDraft(context,value);return value;
    }
    public static synchronized void saveDraft(Context context,Snapshot value){
        if(!edit(credential(context).getSharedPreferences(DRAFT,0),value).commit())throw new IllegalStateException("无法保存壁纸草稿");
    }
    public static synchronized Snapshot importDraftPhoto(Context context,Uri source)throws IOException{
        context=credential(context);Snapshot previous=readDraft(context);
        String name="alive-image-"+UUID.randomUUID()+".jpg";
        File destination=new File(context.getNoBackupFilesDir(),name);
        AliveAssets.importPhoto(context,source,destination);
        Snapshot next=new Snapshot(previous.aod,previous.lock,previous.home,name);
        saveDraft(context,next);return next;
    }
    /** Worker only. Never reads credential storage before the current user's first unlock. */
    public static synchronized boolean prepareRuntime(Context context)throws IOException{
        if(!context.getSystemService(UserManager.class).isUserUnlocked())return hasApplied(context)&&AliveAssets.ready(context);
        Context source=credential(context);
        if(!AliveAssets.migrate(source))return false;
        if(hasApplied(context))return readApplied(context).photoFile(AliveAssets.runtime(context)).isFile();
        Snapshot old=read(source.getSharedPreferences(APPLIED,0));
        if(!old.photoFile(source).isFile())return false;
        publish(source,old);return true;
    }
    public static synchronized Snapshot applyDraft(Context context)throws IOException{
        Context source=credential(context);Snapshot value=readDraft(source);
        if(!AliveAssets.migrate(source))throw new IOException("请先导入兼容的 Alive 资源");
        publish(source,value,true);return value;
    }
    private static void publish(Context source,Snapshot value)throws IOException{
        publish(source,value,false);
    }
    private static void publish(Context source,Snapshot value,boolean manual)throws IOException{
        File photo=value.photoFile(source),target=value.photoFile(AliveAssets.runtime(source));
        if(!photo.isFile()||photo.length()<1||photo.length()>32L*1024*1024)throw new IOException("请先选择有效壁纸照片");
        // Photos have immutable names. Never replace a file referenced by an earlier committed snapshot.
        if(target.exists()){
            if(!java.util.Arrays.equals(hash(photo),hash(target)))throw new IOException("同名壁纸照片发生变化；请重新选择照片");
        }else{
            AtomicFile copy=new AtomicFile(target);FileOutputStream output=null;
            try{output=copy.startWrite();Files.copy(photo.toPath(),output);output.getFD().sync();copy.finishWrite(output);}
            catch(IOException failure){if(output!=null)copy.failWrite(output);throw failure;}
        }
        if(!target.isFile()||target.length()!=photo.length()||!java.util.Arrays.equals(hash(photo),hash(target)))
            throw new IOException("设备加密区照片发布未完成；旧快照保留");
        AtomicFile file=appliedFile(source);FileOutputStream output=null;
        try{
            byte[] bytes=new JSONObject().put("aod",value.aod).put("lock",value.lock).put("home",value.home).put("photo",value.photo)
                    .put("crop_x",value.crop.x()).put("crop_y",value.crop.y()).put("crop_zoom",value.crop.zoom())
                    .toString().getBytes(StandardCharsets.UTF_8);
            output=file.startWrite();output.write(bytes);output.getFD().sync();file.finishWrite(output);output=null;
            if(!java.util.Arrays.equals(bytes,file.readFully()))throw new IOException("已应用快照持久化未完成");
        }catch(Exception failure){if(output!=null)file.failWrite(output);throw new IOException("无法发布开机壁纸快照；旧配置保留",failure);}
        if(manual)manualColor.set(new ManualColor(value,SystemClock.uptimeMillis()+5000));
        runtimePreferences(source).edit().putLong("applied_revision",System.nanoTime()).apply();
    }
    static boolean consumeManualColor(Snapshot rendered){
        ManualColor pending=manualColor.get();
        return pending!=null&&pending.snapshot.equals(rendered)&&SystemClock.uptimeMillis()<pending.deadline
                &&manualColor.compareAndSet(pending,null);
    }
    private static byte[] hash(File file)throws IOException{
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[32*1024];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}
            return hash.digest();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
