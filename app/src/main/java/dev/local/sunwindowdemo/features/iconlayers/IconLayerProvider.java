package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.Arrays;
import java.io.*;

/** Only the owner and the three existing system hosts may read immutable icon snapshots. */
public final class IconLayerProvider extends ContentProvider {
    private static final String GENERATION="6:";
    private final java.util.concurrent.ExecutorService generator=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public boolean onCreate() { return true; }
    private boolean checkCaller() {
        int uid = Binder.getCallingUid();
        boolean owner = uid == getContext().getApplicationInfo().uid;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        boolean sameUser = UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle());
        boolean launcher = sameUser && packages != null && Arrays.asList(packages).contains(IconLayerStore.LAUNCHER);
        boolean systemUi = sameUser && packages != null && Arrays.asList(packages).contains(IconLayerStore.SYSTEM_UI);
        if (!owner && !launcher && !systemUi && uid != android.os.Process.SYSTEM_UID) throw new SecurityException("icon layer bridge");
        return launcher;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        boolean launcher = checkCaller();
        if("started".equals(method)){
            if(Binder.getCallingUid()!=android.os.Process.SYSTEM_UID)throw new SecurityException("launch observer");
            if(IconArchive.validPackage(arg))generator.execute(()->generate(arg));
            return Bundle.EMPTY;
        }
        if ("read".equals(method)) {
            // Refresh existing algorithm caches after module upgrades. A newly opted-in
            // app still waits for its first launch; importing/previewing never generates it.
            generator.execute(this::migrateCached);
            Bundle result = IconLayerStore.read(getContext());
            // A large library stays on disk; send only installed matches across Binder.
            var installed = new java.util.HashSet<String>();
            for(var info:getContext().getSystemService(android.content.pm.LauncherApps.class).getActivityList(null,android.os.Process.myUserHandle()))
                if(IconArchive.validPackage(info.getComponentName().getPackageName()))installed.add(info.getComponentName().getPackageName());
            result.putStringArrayList("targets",new java.util.ArrayList<>(installed));
            var packages = IconLibrary.packages(getContext(), result.getString("generation", ""));
            packages.retainAll(installed); result.putStringArrayList("packages", packages);
            for (String key : new String[]{"originals", "restore"}) {
                var selected = result.getStringArrayList(key);
                if (selected != null) selected.retainAll(installed);
            }
            return result;
        }
        if ("report".equals(method) && launcher && extras != null) {
            IconLayerStore.report(getContext(), extras);
            if (extras.containsKey("loadedGeneration")) IconLibrary.acknowledge(getContext(), extras.getString("loadedGeneration", ""));
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("method");
    }
    /** Explicit opt-in only; native resume observer merely queues this work outside WM locks. */
    void migrateCached(){
        for(var entry:IconPolicy.parse(IconLayerStore.prefs(getContext()).getString("policies","{}")).entrySet()){
            IconPolicy p=entry.getValue();
            if(p.color==IconPolicy.ALGORITHM&&!p.generatedVersion.startsWith(GENERATION)
                &&!p.generatedBackground.isEmpty()&&!p.generatedForeground.isEmpty())generate(entry.getKey());
        }
    }
    void generate(String pkg){
        try{
            if(!IconLayerStore.prefs(getContext()).getBoolean("enabled",false))return;
            IconPolicy p=IconPolicy.read(getContext(),pkg);
            if(p.color!=IconPolicy.ALGORITHM)return;
            String version=GENERATION+getContext().getPackageManager().getPackageInfo(pkg,0).lastUpdateTime;
            if(version.equals(p.generatedVersion)&&!p.generatedBackground.isEmpty()&&!p.generatedForeground.isEmpty()
                &&IconOverrides.image(getContext(),p.generatedBackground).isFile()&&IconOverrides.image(getContext(),p.generatedForeground).isFile())return;
            var original=getContext().getPackageManager().getApplicationIcon(pkg);
            // Real adaptive layers already have correct viewport geometry; do not flatten and split them again.
            IconImages.Layers layers=original instanceof android.graphics.drawable.AdaptiveIconDrawable adaptive
                ?new IconImages.Layers(IconImages.render(adaptive.getBackground(),256),IconImages.render(adaptive.getForeground(),256))
                :IconImages.separate(IconImages.render(original,256));
            // Apply the requested reduction to both algorithm paths, including native adaptive layers.
            String bg=IconOverrides.store(getContext(),layers.background()),fg=IconOverrides.store(getContext(),IconImages.algorithmForeground(layers.foreground()));
            synchronized(IconPolicy.class){
                p=IconPolicy.read(getContext(),pkg);if(p.color!=IconPolicy.ALGORITHM)return;
                p.generatedBackground=bg;p.generatedForeground=fg;p.generatedVersion=version;IconPolicy.save(getContext(),pkg,p);
            }
        }catch(Exception error){android.util.Log.w("NcIconAssets","generation failed: "+pkg,error);}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode)) throw new SecurityException("read only");
        var parts = uri.getPathSegments();
        if(parts.size()==2 && parts.get(0).equals("custom") && parts.get(1).matches("[a-f0-9]{64}\\.png")) {
            String id=parts.get(1).substring(0,64);
            boolean referenced=false;
            for(var p:IconPolicy.parse(IconLayerStore.prefs(getContext()).getString("policies","{}")).values())
                if(id.equals(p.background)||id.equals(p.foreground)||id.equals(p.monoImage)||id.equals(p.notificationImage)
                    ||id.equals(p.generatedBackground)||id.equals(p.generatedForeground)){referenced=true;break;}
            if(!referenced)throw new FileNotFoundException("unpublished image");
            return ParcelFileDescriptor.open(IconOverrides.image(getContext(),id),ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (parts.size() != 4 || !parts.get(0).equals("layers") || !IconLibrary.validGeneration(parts.get(1))
            || !IconArchive.validPackage(parts.get(2)) || !(parts.get(3).equals("0.png") || parts.get(3).equals("1.png")))
            throw new FileNotFoundException("icon path");
        try {
            if (!IconLibrary.packages(getContext(), parts.get(1)).contains(parts.get(2))) throw new FileNotFoundException("icon package");
            File file = new File(new File(IconLibrary.generation(getContext(), parts.get(1)), parts.get(2)), parts.get(3));
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
