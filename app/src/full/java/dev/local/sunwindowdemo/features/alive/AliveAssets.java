package dev.local.sunwindowdemo.features.alive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local user-selected resources only. All public methods perform I/O and belong on a worker thread. */
public final class AliveAssets {
    public static final String SOURCE_SHA256="99361d0275ef98f00206c9f53202efadf5686cc82011497a58c2d37a0e47bb71";
    public static final long SOURCE_BYTES=643_140_270L;
    private static final String LIBRARY="lib/arm64-v8a/libalive_clean.so";
    private static final String LIBRARY_SHA256="43ff8838e2140f9564ac29b5cc5e73eb5dfca9696aedfc5cf4b324b899eaf027";
    private static final Map<String,Long> REQUIRED=requiredFiles();
    private static final long MAX_ENTRY=16L*1024*1024,MAX_TOTAL=29_539_884L,MAX_PHOTO=32L*1024*1024;
    private static final int MAX_FILES=252;
    private static final Map<String,String> PREVIEWS=Map.ofEntries(
        Map.entry("alive_photo_wallpaper_aod_set_sail.png","9086061a04cf42a47f8a2a1d5a8e2f129e3cd2ce5a5dcb85b3d7b7d529af320d"),
        Map.entry("alive_photo_wallpaper_aod_leave_light.png","91404e2176554dfee7d46c9bf50e6181529b53af981f3fa97a2973b53b6ab7c7"),
        Map.entry("alive_photo_wallpaper_aod_moon_and_star.png","bff098299c46fb7d5889bb245e8995d0d85553984a09d9e3aa3f07afeb749b57"),
        Map.entry("alive_photo_wallpaper_aod_light_start.png","7d8e218d3683d82b47b9b8b714d3286632b77bf9313ff2f79a174159d6f2e660"),
        Map.entry("alive_photo_wallpaper_aod_mountain.png","936a5254acdbae743d0e1e11c8b0adfc505359b8e424a71b94848bc7393c9a1c"),
        Map.entry("alive_photo_wallpaper_aod_galaxy.png","a33d67d661ab67a7821e04afa391cf72922cc70be44b8182aa7c22a980facab2"),
        Map.entry("alive_photo_wallpaper_lockscreen_source.png","80d131c70da0153c480cc0569d7db94fb6b723dcacab45078f3c6e22f19cc87f"),
        Map.entry("alive_photo_wallpaper_lockscreen_straight_line.png","45df3f0f30cd93a912279502dabf783c7f30811647dcf7556ac36c9a65912420"),
        Map.entry("alive_photo_wallpaper_lockscreen_curve.png","1bf7f043243c3508679dc900e9ff9fd859b8ee00cd9af9e15ff2387a80a8e256"),
        Map.entry("alive_photo_wallpaper_lockscreen_frosted_glass.png","3bbdb5d383f5ac9ec289a3deb21cc67ae64c638b4e93084b7ecfabc12002eb1e"));
    private static File checked;
    private AliveAssets() {}

    /** Core renderer resources are user-private DE data, available before credential unlock. */
    public static Context runtime(Context context){
        return context.isDeviceProtectedStorage()?context:context.createDeviceProtectedStorageContext();
    }
    private static Context credential(Context context)throws IOException{
        UserManager users=context.getSystemService(UserManager.class);
        if(users==null||!users.isUserUnlocked())throw new IOException("解锁后才能访问编辑器资源");
        return AliveSettings.credential(context);
    }

    /** Copies only a fully validated legacy generation; never replaces or deletes either published source. */
    public static synchronized boolean migrate(Context context)throws IOException{
        Context device=runtime(context);
        if(ready(device))return true;
        UserManager users=context.getSystemService(UserManager.class);
        if(users==null||!users.isUserUnlocked())return false;
        requireArm64();
        File published=generation(device);
        if(published.exists())throw new IOException("设备加密区资源损坏；不会覆盖已发布的原生库");
        File source=new File(root(credential(context)),SOURCE_SHA256);
        if(!source.exists())return false;
        if(!validate(source,false))throw new IOException("旧版 Alive 资源完整性校验失败");
        File stage=new File(root(device),"import-"+UUID.randomUUID());
        try{
            if(!stage.mkdir())throw new IOException("无法建立迁移暂存目录");
            for(var item:REQUIRED.entrySet())copyResource(child(source,item.getKey()),child(stage,item.getKey()),item.getValue());
            File manifest=child(source,"manifest.json");
            copyResource(manifest,child(stage,"manifest.json"),manifest.length());
            if(!validate(stage,false))throw new IOException("迁移资源完整性校验失败");
            syncDirectories(stage);
            if(published.exists()||!stage.renameTo(published))throw new IOException("无法发布迁移资源；未更改现有资源");
            syncDirectory(published.getParentFile());checked=null;
            if(!ready(device))throw new IOException("设备加密区资源校验失败");
            return true;
        }finally{removeStage(stage);}
    }
    private static void copyResource(File source,File target,long size)throws IOException{
        if(!regularReadOnly(source)||size<=0||source.length()!=size)throw new IOException("迁移源资源无效");
        File parent=target.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建迁移目录");
        try(InputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(target)){
            if(!target.setReadOnly())throw new IOException("无法将迁移资源设为只读");
            if(copy(input,output,size,null)!=size)throw new IOException("迁移资源长度不匹配");
            output.getFD().sync();
        }
    }

    /** Imports exactly the pinned official APK; foreign DEX is never extracted or executed. */
    public static synchronized void prepare(Context context,Uri source)throws IOException{
        context=credential(context);
        requireArm64();
        boolean coreReady=ready(context);
        if(coreReady&&previewsReady(context))return;
        File root=root(runtime(context)),published=generation(context);
        if(!coreReady&&published.exists())throw new IOException("已发布资源损坏；不会覆盖已加载的原生库，请先清除本功能数据并重启应用");
        File apk=File.createTempFile("alive-source-",".apk",context.getCacheDir());
        File stage=new File(root,"import-"+UUID.randomUUID());
        try{
            MessageDigest digest=digest();
            try(InputStream input=selected(context,source);FileOutputStream output=new FileOutputStream(apk)){
                long size=copy(input,output,SOURCE_BYTES,digest);output.getFD().sync();
                if(size!=SOURCE_BYTES||!hex(digest.digest()).equals(SOURCE_SHA256))
                    throw new IOException("仅支持官方 OpenAlive 0.4.41-preview APK，文件大小或 SHA-256 不匹配");
            }
            importPreviews(context,apk);
            if(coreReady){
                runtime(context).getSharedPreferences(AliveSettings.APPLIED,Context.MODE_PRIVATE).edit().putLong("resources_revision",System.nanoTime()).apply();
                return;
            }
            if(!stage.mkdir())throw new IOException("无法建立资源暂存目录");
            JSONArray manifest=new JSONArray();Set<String> names=new HashSet<>();long total=0;
            try(ZipFile archive=new ZipFile(apk)){
                Enumeration<? extends ZipEntry> entries=archive.entries();int scanned=0;
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();
                    if(++scanned>50_000)throw new IOException("APK 条目过多");
                    String path=entry.getName();
                    if(entry.isDirectory()||!allowed(path))continue;
                    if(!names.add(path)||names.size()>MAX_FILES)throw new IOException("资源条目重复或过多");
                    long size=entry.getSize();
                    if(size!=REQUIRED.get(path)||size<=0||size>MAX_ENTRY||entry.getCompressedSize()<=0
                            ||size>entry.getCompressedSize()*512L||total+size>MAX_TOTAL)
                        throw new IOException("资源条目超过安全大小限制");
                    File target=child(stage,path);File parent=target.getParentFile();
                    if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建资源目录");
                    MessageDigest hash=digest();
                    // Open the descriptor first, then make its path read-only before writing code bytes.
                    try(FileOutputStream output=new FileOutputStream(target);InputStream input=archive.getInputStream(entry)){
                        if(!target.setReadOnly())throw new IOException("无法将资源设为只读");
                        if(copy(input,output,size,hash)!=size)throw new IOException("资源长度不匹配");
                        output.getFD().sync();
                    }
                    String actualHash=hex(hash.digest());
                    if(path.equals(LIBRARY)&&!actualHash.equals(LIBRARY_SHA256))throw new IOException("原生库 SHA-256 不匹配");
                    total+=size;
                    manifest.put(new JSONObject().put("path",path).put("size",size).put("sha256",actualHash));
                }
            }
            required(names);
            try(FileOutputStream output=new FileOutputStream(new File(stage,"manifest.json"))){
                output.write(new JSONObject().put("source",SOURCE_SHA256).put("files",manifest).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if(!new File(stage,"manifest.json").setReadOnly())throw new IOException("无法封存资源清单");
            if(!validate(stage,false))throw new IOException("暂存资源完整性校验失败");
            syncDirectories(stage);
            // No active generation is replaced: a loaded native library keeps the same immutable path.
            if(published.exists()||!stage.renameTo(published))throw new IOException("无法发布资源；未更改现有资源");
            syncDirectory(root);checked=null;
            if(!ready(context))throw new IOException("资源完整性校验失败");
            runtime(context).getSharedPreferences(AliveSettings.APPLIED,Context.MODE_PRIVATE).edit()
                .putLong("resources_revision",System.nanoTime()).apply();
        }catch(JSONException invalid){throw new IOException("资源清单无效",invalid);}
        finally{apk.delete();removeStage(stage);}
    }

    /** Resource/ABI readiness only; actual JNI linking and renderer capability checks remain with the runtime. */
    public static synchronized boolean ready(Context context){
        if(!arm64())return false;
        try{return validate(generation(context),true);}catch(IOException|RuntimeException failure){return false;}
    }
    private static boolean validate(File dir,boolean cached){
        try{
            if(cached&&dir.equals(checked)){
                for(var entry:REQUIRED.entrySet()){
                    File file=child(dir,entry.getKey());
                    if(!regularReadOnly(file)||file.length()!=entry.getValue())return false;
                }
                return true;
            }
            File manifest=child(dir,"manifest.json");
            if(!regularReadOnly(manifest)||manifest.length()>1024*1024)return false;
            JSONObject value=new JSONObject(new String(readBounded(manifest,1024*1024),java.nio.charset.StandardCharsets.UTF_8));
            if(!SOURCE_SHA256.equals(value.getString("source")))return false;
            JSONArray files=value.getJSONArray("files");
            if(files.length()>MAX_FILES)return false;
            Set<String> names=new HashSet<>();long total=0;
            for(int i=0;i<files.length();i++){
                JSONObject entry=files.getJSONObject(i);String path=entry.getString("path");
                if(!allowed(path)||!names.add(path))return false;
                File file=child(dir,path);long size=entry.getLong("size");
                if(size!=REQUIRED.get(path)||size<=0||size>MAX_ENTRY||(total+=size)>MAX_TOTAL||!regularReadOnly(file)||file.length()!=size)return false;
                MessageDigest hash=digest();
                try(InputStream input=new FileInputStream(file)){copy(input,OutputStream.nullOutputStream(),size,hash);}
                String actualHash=hex(hash.digest());
                if(!actualHash.equals(entry.getString("sha256"))||path.equals(LIBRARY)&&!actualHash.equals(LIBRARY_SHA256))return false;
            }
            required(names);if(cached)checked=dir;return true;
        }catch(IOException|JSONException|RuntimeException failure){return false;}
    }

    public static synchronized File library(Context context)throws IOException{
        File dir=generation(context);
        if(!dir.equals(checked)&&!ready(context))throw new IOException("尚未导入兼容的 Alive 资源");
        File file=child(dir,LIBRARY);
        if(!regularReadOnly(file))throw new IOException("原生库必须是私有只读普通文件");
        return file;
    }

    /** Renderer paths are relative to APK assets/, not arbitrary filesystem paths. */
    public static synchronized InputStream open(Context context,String path)throws IOException{
        if(path!=null&&path.startsWith("editor/")){
            String name=path.substring(7),expected=PREVIEWS.get(name);
            if(expected==null)throw new IOException("缩略资源未授权");
            File file=new File(new File(root(credential(context)),"previews-v1"),name);
            if(!validPreview(file,expected))throw new IOException("请重新选择固定 OpenAlive APK 补充样式缩略资源");
            return new FileInputStream(file);
        }
        if(path==null||!allowed("assets/"+path))throw new IOException("资源路径未授权");
        File dir=generation(context);
        if(!dir.equals(checked)&&!ready(context))throw new IOException("资源尚未就绪");
        File file=child(dir,"assets/"+path);
        if(!regularReadOnly(file)||file.length()!=REQUIRED.get("assets/"+path))throw new IOException("资源不完整");
        return new FileInputStream(file);
    }

    /** Optional editor assets are independent of the immutable, possibly already loaded native generation. */
    public static synchronized boolean previewsReady(Context context)throws IOException{
        File dir=new File(root(credential(context)),"previews-v1");
        for(var item:PREVIEWS.entrySet())try{if(!validPreview(new File(dir,item.getKey()),item.getValue()))return false;}
        catch(IOException unavailable){return false;}
        return true;
    }
    private static boolean validPreview(File file,String expected)throws IOException{
        if(!file.exists()||!regularReadOnly(file)||file.length()<=0||file.length()>160_000)return false;
        MessageDigest hash=digest();try(InputStream input=new FileInputStream(file)){copy(input,OutputStream.nullOutputStream(),160_000,hash);}
        return expected.equals(hex(hash.digest()));
    }
    private static void importPreviews(Context context,File apk)throws IOException{
        File dir=new File(root(credential(context)),"previews-v1");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建缩略资源目录");
        try(ZipFile archive=new ZipFile(apk)){
            for(var item:PREVIEWS.entrySet()){
                File file=new File(dir,item.getKey());
                if(validPreview(file,item.getValue()))continue;
                ZipEntry entry=archive.getEntry("assets/editor/"+item.getKey());
                if(entry==null||entry.getSize()<=0||entry.getSize()>160_000)throw new IOException("缺少固定样式缩略资源");
                AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
                try{
                    output=atomic.startWrite();MessageDigest hash=digest();
                    try(InputStream input=archive.getInputStream(entry)){if(copy(input,output,160_000,hash)!=entry.getSize())throw new IOException("缩略资源长度错误");}
                    if(!item.getValue().equals(hex(hash.digest())))throw new IOException("缩略资源校验错误");
                    atomic.finishWrite(output);output=null;
                    if(!file.setReadOnly())throw new IOException("无法封存缩略资源");
                }finally{if(output!=null)atomic.failWrite(output);}
            }
        }
    }

    /** A missing photo stays missing; callers must ask the user to select one. */
    public static File photo(Context context){return new File(context.getNoBackupFilesDir(),"alive-photo.jpg");}

    public static synchronized void importPhoto(Context context,Uri source)throws IOException{
        importPhoto(context,source,photo(context));
        context.getSharedPreferences("alive_wallpaper",Context.MODE_PRIVATE).edit().putLong("photo_revision",System.nanoTime()).apply();
    }
    static synchronized void importPhoto(Context context,Uri source,File destination)throws IOException{
        if(!destination.getParentFile().getCanonicalFile().equals(context.getNoBackupFilesDir().getCanonicalFile()))throw new IOException("Invalid photo destination");
        File input=File.createTempFile("alive-photo-",".img",context.getCacheDir());
        Bitmap bitmap=null;
        try{
            try(InputStream from=selected(context,source);FileOutputStream to=new FileOutputStream(input)){
                copy(from,to,MAX_PHOTO,null);
            }
            bitmap=AlivePhoto.decodeImport(ImageDecoder.createSource(input));
            AtomicFile atomic=new AtomicFile(destination);FileOutputStream output=null;
            try{
                output=atomic.startWrite();
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))throw new IOException("无法保存照片");
                atomic.finishWrite(output);output=null;
            }finally{if(output!=null)atomic.failWrite(output);}
        }catch(RuntimeException failure){throw new IOException("照片导入失败",failure);}
        finally{if(bitmap!=null)bitmap.recycle();input.delete();}
    }

    private static boolean allowed(String path){return path!=null&&REQUIRED.containsKey(path);}
    private static void required(Set<String> names)throws IOException{
        if(!names.equals(REQUIRED.keySet()))throw new IOException("固定资源条目缺失或不匹配");
    }
    private static Map<String,Long> requiredFiles(){
        Map<String,Long> files=new LinkedHashMap<>();
        files.put(LIBRARY,467592L);
        files.put("assets/shader/photo/aod/lensPhoto/lens_decorator.png",232308L);
        files.put("assets/shader/photo/lock/groundGlass/gray.png",15630578L);
        files.put("assets/shader/photo/lock/groundGlass/mask.png",304416L);
        // Exact names and byte sizes from the full-SHA-verified official APK, not a wildcard extractor.
        mask(files,"leave_light",15764,4705,9600,new int[]{21474,23504,23004,23167,23434,23432,23163,23301,23146,22295,22426,21659,21161,21146,19343,19027,17410,16172,15963,14068,12466,12130,12455,11460,11485,10574,10200,9967,9153,9453,9127,9148,8402,8663,8757,8296,8803,8230,8684,8786,8934,8368,8884,9457,8977,9322,8903,8567,8520,7950,7395,7001,5789,5801,5816,4986,4313,4372,4372,4372,4372});
        mask(files,"moon",13923,4705,8539,new int[]{11756,13066,15054,16012,15203,13273,14130,15358,16343,16319,15171,14978,14484,14386,14384,14395,14122,13112,12314,11522,10787,9815,7592,6265,5851,5297,4954,4629,4478,4296,4187,4178});
        mask(files,"door",12474,7520,10656,new int[]{16046,15573,14682,13641,12131,9757,7677,5831,5071,5243,4103,4334,4456,4608,5084,5085,5112,5386,5475,5462,5711,5722,5795,5625,5775,5700,5744,5821,5804,5900,5870,5771,5769,5893,6035,5954,5919,5851,5672,5535,5495});
        mask(files,"mountain",68208,1548,13217,new int[]{130493,130817,130696,131954,132643,133002,134215,138724,148507,155350,165030,183449,200960,211666,219763,231127,236130,248562,260667,263837,260261,250125,237690,221513,193931,155716,107046,75096,61857,52038,42489,34277,27080,19943,14859,12496,8839,6639,5023,5023,5023,5023,5023});
        mask(files,"galaxy",61144,1531,16911,new int[]{93702,97405,99349,101269,103084,106096,107835,111509,112961,114941,117955,120088,122112,124035,125210,125767,128097,130316,133691,137551,141993,144151,145784,145408,142702,138817,131694,121612,100907,76478,57558,65770,73133,81564,90976,100939,109952,126397,141116,148698,151664,164263,156133,139132,127249,108764,97232,86876,89640,36676,57124,10490,10311,10311,10311,10311});
        return Map.copyOf(files);
    }
    private static void mask(Map<String,Long> files,String name,int start,int end,int manifest,int[] sizes){
        String prefix="assets/masks/"+name+"/";
        files.put(prefix+"start.rgbz",(long)start);files.put(prefix+"end.rgbz",(long)end);
        files.put(prefix+"manifest.json",(long)manifest);
        for(int i=0;i<sizes.length;i++)files.put(prefix+String.format(Locale.ROOT,"%03d.rgbz",i),(long)sizes[i]);
    }
    private static boolean arm64(){return Process.is64Bit()&&Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");}
    private static void requireArm64()throws IOException{if(!arm64())throw new IOException("Alive 原生资源仅支持 arm64 进程");}
    private static File root(Context context)throws IOException{
        File file=new File(context.getNoBackupFilesDir(),"alive-resources");
        if(!file.isDirectory()&&!file.mkdirs())throw new IOException("无法创建私有资源目录");
        return file;
    }
    private static File generation(Context context)throws IOException{return new File(root(runtime(context)),SOURCE_SHA256);}
    private static void syncDirectories(File directory)throws IOException{
        File[] children=directory.listFiles();
        if(children==null)throw new IOException("无法读取暂存资源目录");
        for(File file:children)if(file.isDirectory())syncDirectories(file);
        syncDirectory(directory);
    }
    private static void syncDirectory(File directory)throws IOException{
        FileDescriptor descriptor=null;
        try{descriptor=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);Os.fsync(descriptor);}
        catch(ErrnoException failure){throw new IOException("无法同步资源目录",failure);}
        finally{if(descriptor!=null)try{Os.close(descriptor);}catch(ErrnoException ignored){}}
    }
    private static File child(File root,String path)throws IOException{
        File file=new File(root,path);String base=root.getCanonicalPath()+File.separator;
        if(!file.getCanonicalPath().startsWith(base))throw new IOException("无效资源路径");
        return file;
    }
    private static boolean regularReadOnly(File file)throws IOException{
        try{StructStat stat=Os.lstat(file.getPath());return OsConstants.S_ISREG(stat.st_mode)
                &&stat.st_uid==Process.myUid()&&(stat.st_mode&0222)==0;}
        catch(ErrnoException error){throw new IOException("无法读取资源权限",error);}
    }
    private static InputStream selected(Context context,Uri source)throws IOException{
        if(source==null||!"content".equals(source.getScheme()))throw new IOException("请通过系统文件选择器选择本地文件");
        InputStream input=context.getContentResolver().openInputStream(source);
        if(input==null)throw new IOException("无法读取所选文件");return input;
    }
    private static long copy(InputStream input,OutputStream output,long limit,MessageDigest digest)throws IOException{
        byte[] buffer=new byte[64*1024];long size=0;int count;
        while((count=input.read(buffer))!=-1){
            if((size+=count)>limit)throw new IOException("文件超过安全大小限制");
            output.write(buffer,0,count);if(digest!=null)digest.update(buffer,0,count);
        }
        return size;
    }
    private static byte[] readBounded(File file,int limit)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream input=new FileInputStream(file)){copy(input,output,limit,null);}return output.toByteArray();
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static void removeStage(File file){
        // Only this invocation's freshly created staging directory; never traverse the published generation.
        if(!file.exists())return;File[] children=file.listFiles();
        if(children!=null)for(File child:children)removeStage(child);file.delete();
    }
}
