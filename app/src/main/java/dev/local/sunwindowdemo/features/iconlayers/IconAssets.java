package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Each host publishes one immutable bitmap snapshot. Public rendering access never uses Binder or disk. */
public final class IconAssets {
    public record Entry(Bitmap background,Bitmap foreground,Bitmap mono,Bitmap notification,boolean colorChanged,boolean monoChanged,boolean nativeColor) {}
    record Snapshot(Map<String,Entry> entries,String generation,int failures,int demo,Set<String> algorithms) {}
    private static volatile Snapshot snapshot=new Snapshot(Map.of(),"",0,0,Set.of());
    private static final AtomicInteger epoch=new AtomicInteger();
    private static volatile Handler worker;
    private static volatile boolean ready;
    private static Context host;
    private static dev.local.sunwindowdemo.core.HostSnapshot settings;
    private static final List<Runnable> listeners=new ArrayList<>();
    private IconAssets() {}

    /** Call once from host initialization, outside WM locks. All I/O happens on the dedicated worker. */
    public static synchronized void connect(Context context) {
        if(host!=null)return;host=context;
        HandlerThread thread=new HandlerThread("NcIconAssets");thread.start();worker=new Handler(thread.getLooper());
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reload();}};
        context.registerReceiver(receiver,new IntentFilter(IconLayerStore.REFRESH),IconLayerStore.CONTROL,null,Context.RECEIVER_EXPORTED);
        IntentFilter lifecycle=new IntentFilter(Intent.ACTION_BOOT_COMPLETED);lifecycle.addAction(Intent.ACTION_USER_UNLOCKED);
        context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reload();}},lifecycle,Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages=new IntentFilter();packages.addAction(Intent.ACTION_PACKAGE_ADDED);packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);packages.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reload();}},packages,Context.RECEIVER_NOT_EXPORTED);
        reload();
    }
    public static synchronized void onReload(Runnable listener){listeners.add(listener);}
    public static synchronized void removeOnReload(Runnable listener){listeners.remove(listener);}
    public static boolean ready(){return ready;}
    /** The resume hook reads memory and queues work only; no Binder or decoding under WM locks. */
    public static void started(String pkg){
        Handler queue=worker;
        if(queue==null||!snapshot.algorithms.contains(pkg))return;
        queue.post(()->{
            if(!snapshot.algorithms.contains(pkg))return;
            if(settings!=null&&!settings.reachable())return;
            try{host.getContentResolver().call(IconLayerStore.URI,"started",pkg,null);}
            catch(Exception failure){android.util.Log.w("NcIconAssets","launch generation failed: "+pkg,failure);}
        });
    }
    private static void reload(){int request=epoch.incrementAndGet();worker.post(()->reload(request,0));}
    private static void reload(int request,int attempt){
        if(request!=epoch.get())return;
        try{
            if(settings==null)settings=dev.local.sunwindowdemo.core.HostSnapshot.open(host,"icons");
            var read=settings.read(()->{
                Bundle result=host.getContentResolver().call(IconLayerStore.URI,"read",null,null);
                if(result!=null&&result.getBoolean("enabled")&&(result.getStringArrayList("targets")==null||result.getStringArrayList("targets").isEmpty()))
                    throw new IllegalStateException("icon targets not ready");
                return result;
            });
            if(read.report())android.util.Log.w("NcIconAssets",read.summary());
            Bundle config=read.config();
            if(config==null)throw new IllegalStateException("icon config not ready");
            if((read.live()||!ready)&&load(host,config,()->request!=epoch.get())){
                android.util.Log.i("NcIconAssets","loaded host="+host.getPackageName()+" entries="+snapshot.entries.size()+" failures="+snapshot.failures);
                List<Runnable> callbacks;synchronized(IconAssets.class){callbacks=List.copyOf(listeners);}
                for(Runnable callback:callbacks)try{callback.run();}catch(RuntimeException ignored){}
            }
            if(!read.live()&&request==epoch.get())worker.postDelayed(()->reload(request,0),settings.retryDelay(3000));
        }catch(Exception failure){
            android.util.Log.w("NcIconAssets","reload failed attempt="+attempt,failure);
            // Boot providers and LauncherApps can become usable after the first request.
            // Keep the last snapshot; bounded retries are superseded by a newer refresh.
            if(request==epoch.get())worker.postDelayed(()->reload(request,Math.min(attempt+1,6)),Math.min(60_000,500L<<Math.min(attempt,7)));
        }
    }

    static boolean load(Context c,Bundle config,BooleanSupplier stale) {
        String generation=config.getString("generation","");int demo=config.getInt("mode");
        Map<String,Entry> entries=new HashMap<>();Set<String> algorithms=new HashSet<>();int failed=0;
        if(config.getBoolean("enabled")){
            Map<String,IconPolicy> policies=IconPolicy.parse(config.getString("policies","{}"));
            Set<String> originals=new HashSet<>(config.getStringArrayList("originals")==null?List.of():config.getStringArrayList("originals"));
            Set<String> libraryPackages=new HashSet<>(config.getStringArrayList("packages")==null?List.of():config.getStringArrayList("packages"));
            List<String> targets=config.getStringArrayList("targets");if(targets==null)targets=List.of();
            // Launcher pixels must not shrink with the number of installed system components.
            // Keep native originals out of the snapshot and preserve a fixed layer resolution.
            int side=256;long bytes=0;
            for(String pkg:targets){
                if(stale.getAsBoolean())return false;
                IconPolicy p=policies.getOrDefault(pkg,new IconPolicy());if(originals.contains(pkg))p.color=IconPolicy.ORIGINAL;
                if(p.color==IconPolicy.ALGORITHM)algorithms.add(pkg);
                if(!config.getBoolean("notifications"))p.notification=IconPolicy.UNCHANGED;
                try{
                    Entry entry=resolve(c,pkg,p,libraryPackages.contains(pkg)?generation:"",side,false);
                    if(entry!=null){
                        long size=0;for(Bitmap b:new Bitmap[]{entry.background,entry.foreground,entry.mono,entry.notification})if(b!=null)size+=b.getAllocationByteCount();
                        // ponytail: exceptionally large matching libraries retain originals past this bound, never publish blurred replacements.
                        if(bytes+size>96L*1024*1024){failed++;continue;}
                        entries.put(pkg,entry);bytes+=size;
                    }
                }
                catch(Exception failure){failed++;android.util.Log.w("NcIconAssets","asset failed: "+pkg);}
            }
        }
        if(stale.getAsBoolean())return false;
        snapshot=new Snapshot(Map.copyOf(entries),generation,failed,demo>=0&&demo<=2?demo:0,Set.copyOf(algorithms));ready=true;return true;
    }
    static Snapshot snapshot(){return snapshot;}

    public static Drawable colored(Context c,String pkg){
        Entry entry=snapshot.entries.get(pkg);
        return entry==null||!entry.colorChanged||entry.nativeColor?null:new ImportedIcon(c.getResources(),entry.background,entry.foreground,entry.mono);
    }
    static Drawable desktop(Context c,String pkg,Drawable original){
        Snapshot current=snapshot;Entry entry=current.entries.get(pkg);
        if(entry!=null&&(entry.colorChanged||entry.monoChanged)){
            if(entry.nativeColor){
                // Keep live clock/calendar subclasses and existing Launcher-loaded artwork intact.
                if(original!=null&&original.getClass()==AdaptiveIconDrawable.class&&entry.monoChanged){
                    AdaptiveIconDrawable nativeIcon=(AdaptiveIconDrawable)original;
                    return new AdaptiveIconDrawable(nativeIcon.getBackground(),nativeIcon.getForeground(),entry.mono==null?nativeIcon.getMonochrome():drawable(c,entry.mono));
                }
            }else return new ImportedIcon(c.getResources(),entry.background,entry.foreground,entry.mono);
        }
        return IconLayerStore.TARGET.equals(pkg)&&current.demo!=0?new LayeredTestIcon(c.getResources(),current.demo):null;
    }
    public static Drawable notification(Context c,String pkg){
        Entry entry=snapshot.entries.get(pkg);
        return entry==null||entry.notification==null?null:drawable(c,entry.notification);
    }
    private static BitmapDrawable drawable(Context c,Bitmap bitmap){
        BitmapDrawable result=new BitmapDrawable(c.getResources(),bitmap);result.setGravity(android.view.Gravity.FILL);result.setFilterBitmap(true);return result;
    }

    /** Worker/UI preview entry, never call this in a draw callback or under a window lock. */
    static Entry resolve(Context c,String pkg,IconPolicy policy,String generation,int side,boolean local) throws Exception {
        ApplicationInfo info=c.getPackageManager().getApplicationInfo(pkg,0);
        Drawable original=c.getPackageManager().getApplicationIcon(info);
        if(policy.color==IconPolicy.ORIGINAL&&policy.monochrome==IconPolicy.UNCHANGED&&policy.notification==IconPolicy.UNCHANGED)return null;
        boolean packed=policy.color==IconPolicy.AUTO&&!generation.isEmpty();
        boolean generated=policy.color==IconPolicy.ALGORITHM&&!policy.generatedBackground.isEmpty()&&!policy.generatedForeground.isEmpty();
        if(!packed&&!generated&&policy.color!=IconPolicy.MANUAL&&policy.monochrome!=IconPolicy.UPLOAD&&policy.notification==IconPolicy.UNCHANGED)return null;
        Bitmap bg,fg,nativeMono=null;boolean colorChanged=false,nativeColor=true;
        if(original instanceof AdaptiveIconDrawable adaptive){
            bg=IconImages.render(adaptive.getBackground(),side);fg=IconImages.render(adaptive.getForeground(),side);
            if(adaptive.getMonochrome()!=null)nativeMono=IconImages.render(adaptive.getMonochrome(),side);
        }else{bg=IconImages.solid(Color.TRANSPARENT,1);fg=IconImages.render(original,side);}
        if(policy.color==IconPolicy.MANUAL){
            Bitmap customBg=custom(c,policy.background,side,local),customFg=custom(c,policy.foreground,side,local);
            if(customBg!=null&&customFg!=null){bg=customBg;fg=customFg;nativeColor=false;colorChanged=true;}
        }else if(packed){
            try{Bitmap pairBg=library(c,generation,pkg,0,side,local),pairFg=library(c,generation,pkg,1,side,local);bg=pairBg;fg=pairFg;nativeColor=false;colorChanged=true;}
            catch(FileNotFoundException absent){} // Missing assets retain the application original; no automatic algorithm fallback.
        }else if(generated){
            Bitmap customBg=custom(c,policy.generatedBackground,side,local),customFg=custom(c,policy.generatedForeground,side,local);
            if(customBg!=null&&customFg!=null){bg=customBg;fg=customFg;nativeColor=false;colorChanged=true;}
        }
        Bitmap mono=nativeMono;boolean monoChanged=false;
        if(policy.monochrome==IconPolicy.UPLOAD){Bitmap custom=custom(c,policy.monoImage,side,local);if(custom!=null){mono=IconImages.monochrome(custom,false);monoChanged=mono!=null;}}
        else if(policy.monochrome==IconPolicy.FOLLOW&&!nativeColor){Bitmap mask=IconImages.monochrome(fg,false);mono=mask==null?nativeMono:mask;monoChanged=mono!=null;}
        Bitmap notice=null;
        if(policy.notification==IconPolicy.UPLOAD){Bitmap custom=custom(c,policy.notificationImage,side,local);if(custom!=null)notice=IconImages.monochrome(custom,true);}
        else if(policy.notification==IconPolicy.FOLLOW){notice=IconImages.monochrome(nativeColor&&nativeMono!=null?nativeMono:fg,true);}
        // A legacy flat Drawable has no independent themed slot. Leaving its color untouched also preserves native wrapping.
        if(policy.color==IconPolicy.ORIGINAL&&!(original instanceof AdaptiveIconDrawable))monoChanged=false;
        return colorChanged||monoChanged||notice!=null?new Entry(IconImages.compactSolid(bg),fg,mono,notice,colorChanged,monoChanged,nativeColor):null;
    }
    private static Bitmap custom(Context c,String id,int side,boolean local) throws Exception{
        if(IconPolicy.asset(id).isEmpty())return null;
        Uri uri=local?Uri.fromFile(IconOverrides.image(c,id)):IconLayerStore.URI.buildUpon().path("custom/"+id+".png").build();
        try{return decode(c,uri,side);}catch(FileNotFoundException absent){return null;}
    }
    private static Bitmap library(Context c,String generation,String pkg,int layer,int side,boolean local)throws Exception{
        Uri uri=local?Uri.fromFile(new File(new File(IconLibrary.generation(c,generation),pkg),layer+".png"))
            :IconLayerStore.URI.buildUpon().path("layers/"+generation+"/"+pkg+"/"+layer+".png").build();
        return decode(c,uri,side);
    }
    private static Bitmap decode(Context c,Uri uri,int side)throws Exception{
        byte[] data=imageBytes(c,uri);
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>2048||o.outHeight>2048)throw new IOException("image bounds");
        o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/(o.inSampleSize*2)>=side)o.inSampleSize*=2;
        o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        {
            Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,o);if(b==null)throw new IOException("image decode");
            int max=Math.max(b.getWidth(),b.getHeight());
            if(max<=side)return b;
            Bitmap scaled=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*(float)side/max)),Math.max(1,Math.round(b.getHeight()*(float)side/max)),true);
            if(scaled!=b)b.recycle();return scaled;
        }
    }

    /** Content-addressed asset URIs are immutable. This private host cache also survives a stopped manager. */
    static byte[] imageBytes(Context c,Uri uri)throws Exception{
        boolean remote=IconLayerStore.URI.getAuthority().equals(uri.getAuthority());
        File cache=null;
        if(remote){
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name=new StringBuilder();
            for(byte value:digest)name.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
            cache=new File(new File(dev.local.sunwindowdemo.core.HostSnapshot.directory(c),"icon_assets"),name.toString());
        }
        byte[] data;
        try(InputStream input=c.getContentResolver().openInputStream(uri)){
            if(input==null)throw new FileNotFoundException(uri.toString());
            data=input.readNBytes(8*1024*1024+1);
            if(data.length>8*1024*1024)throw new IOException("icon asset too large");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw new IOException("image bounds");
        }catch(IOException|IllegalArgumentException unavailable){
            if(cache==null||!cache.isFile()||cache.length()>8*1024*1024)throw unavailable;
            return new android.util.AtomicFile(cache).readFully();
        }
        if(cache!=null){
            var atomic=new android.util.AtomicFile(cache);FileOutputStream output=null;
            try{
                output=atomic.startWrite();output.write(data);atomic.finishWrite(output);
                File[] files=cache.getParentFile().listFiles(File::isFile);
                if(files!=null){
                    Arrays.sort(files,Comparator.comparingLong(File::lastModified));
                    long total=0;for(File file:files)total+=file.length();
                    for(File file:files){if(total<=64L*1024*1024)break;if(!file.equals(cache)){long bytes=file.length();if(file.delete())total-=bytes;}}
                }
            }catch(IOException|RuntimeException failure){if(output!=null)atomic.failWrite(output);android.util.Log.w("NcIconAssets","asset cache unavailable",failure);}
        }
        return data;
    }
}
