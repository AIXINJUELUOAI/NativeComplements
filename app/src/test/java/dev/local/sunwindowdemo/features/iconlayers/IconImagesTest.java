package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconImagesTest {
    private Context c;
    private static final String PKG="com.example.uncovered";
    @Before public void setup(){
        c=RuntimeEnvironment.getApplication();IconLayerStore.prefs(c).edit().clear().commit();
        PackageInfo pkg=new PackageInfo();pkg.packageName=PKG;pkg.applicationInfo=new ApplicationInfo();
        pkg.applicationInfo.packageName=PKG;pkg.applicationInfo.uid=android.os.Process.myUid();
        Shadows.shadowOf(c.getPackageManager()).installPackage(pkg);
    }
    @After public void cleanup(){IconArchive.delete(IconOverrides.root(c));IconArchive.delete(IconLibrary.root(c));}

    @Test public void uniformBackgroundSeparatesWithoutDroppingTheSubject(){
        Bitmap source=IconImages.solid(Color.WHITE,96);
        Paint paint=new Paint();paint.setColor(Color.RED);new Canvas(source).drawRect(30,20,66,76,paint);
        IconImages.Layers result=IconImages.separate(source);
        assertEquals(0,Color.alpha(result.foreground().getPixel(0,0)));
        assertEquals(Color.RED,result.foreground().getPixel(48,48));
        assertTrue(IconImages.contentBounds(result.foreground()).height()<=65);
        assertEquals(255,Color.alpha(result.background().getPixel(0,0)));
    }
    @Test public void notificationUsesAContentNormalizedWhiteMask(){
        Bitmap foreground=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Paint paint=new Paint();paint.setColor(Color.BLUE);new Canvas(foreground).drawRect(80,60,112,132,paint);
        Bitmap mono=IconImages.monochrome(foreground,false),notice=IconImages.monochrome(foreground,true);
        assertEquals(192,mono.getWidth());assertEquals(64,notice.getWidth());
        assertEquals(Color.WHITE,mono.getPixel(96,96));
        assertTrue(IconImages.contentBounds(notice).height()>=50);
        assertTrue(IconImages.contentBounds(notice).width()<IconImages.contentBounds(notice).height());
        assertEquals(Color.BLUE,foreground.getPixel(96,96)); // Pixels never mutate while another host uses them.
    }
    @Test public void aSolidFieldDoesNotBecomeAMeaninglessMonochromeBlock(){
        assertNull(IconImages.monochrome(IconImages.solid(Color.RED,64),false));
    }
    @Test public void defaultRetainsNativeColorsMonochromeAndNotificationWithoutAnImportedLibrary()throws Exception{
        var nativeIcon=new AdaptiveIconDrawable(new ColorDrawable(Color.MAGENTA),new ColorDrawable(Color.GREEN));
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,nativeIcon);
        var result=IconAssets.resolve(c,PKG,new IconPolicy(),"",96,true);
        assertNull(result);
        assertEquals(IconPolicy.UNCHANGED,new IconPolicy().notification);
        assertEquals(IconPolicy.UNCHANGED,IconPolicy.parse("{\"com.example.uncovered\":{}}").get(PKG).notification);
    }
    @Test public void manualImagesAndIndependentModesSurviveAnEmptyLibrary()throws Exception{
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new AdaptiveIconDrawable(new ColorDrawable(Color.BLACK),new ColorDrawable(Color.WHITE)));
        IconPolicy p=new IconPolicy();p.color=IconPolicy.MANUAL;p.monochrome=IconPolicy.UNCHANGED;p.notification=IconPolicy.UPLOAD;
        p.background=upload(IconImages.solid(Color.YELLOW,32));
        Bitmap foreground=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);Paint ink=new Paint();ink.setColor(Color.RED);new Canvas(foreground).drawRect(12,12,20,20,ink);
        p.foreground=upload(foreground);p.notificationImage=p.foreground;
        IconPolicy.save(c,PKG,p);
        var result=IconAssets.resolve(c,PKG,IconPolicy.read(c,PKG),"",64,true);
        assertEquals(Color.YELLOW,result.background().getPixel(0,0));assertFalse(result.monoChanged());assertNotNull(result.notification());
        IconLibrary.remove(c);
        IconPolicy restored=IconPolicy.read(c,PKG);
        assertEquals(p.background,restored.background);assertEquals(p.foreground,restored.foreground);
        assertEquals(IconPolicy.UPLOAD,restored.notification);
    }
    @Test public void customProviderReadsOnlyPublishedImageIds()throws Exception{
        String id=upload(IconImages.solid(Color.BLUE,16));
        IconLayerProvider provider=Robolectric.buildContentProvider(IconLayerProvider.class).create().get();
        Uri uri=Uri.parse("content://dev.nativecomplements.module.iconlayers/custom/"+id+".png");
        assertThrows(FileNotFoundException.class,()->provider.openFile(uri,"r"));
        IconPolicy p=new IconPolicy();p.background=id;IconPolicy.save(c,PKG,p);
        try(var file=provider.openFile(uri,"r")){assertTrue(file.getStatSize()>0);}
        assertThrows(SecurityException.class,()->provider.openFile(uri,"w"));
        assertThrows(FileNotFoundException.class,()->provider.openFile(Uri.parse("content://dev.nativecomplements.module.iconlayers/custom/../../outside.png"),"r"));
    }
    @Test public void customImagesAreContentAddressedAndFailedImportsPreservePolicies()throws Exception{
        String first=upload(IconImages.solid(Color.BLUE,16));assertEquals(first,upload(IconImages.solid(Color.BLUE,16)));
        IconPolicy p=new IconPolicy();p.color=IconPolicy.MANUAL;p.background=first;IconPolicy.save(c,PKG,p);
        File corrupt=new File(c.getCacheDir(),"bad.png");Files.write(corrupt.toPath(),new byte[]{1,2,3});
        assertThrows(IOException.class,()->IconOverrides.importImage(c,Uri.fromFile(corrupt)));
        assertEquals(first,IconPolicy.read(c,PKG).background);
    }
    @Test public void publicationPrunesOldHashesButKeepsEveryPolicyFieldAndTemporaryFiles()throws Exception{
        IconPolicy p=new IconPolicy();p.background=upload(IconImages.solid(Color.RED,16));
        p.foreground=upload(IconImages.solid(Color.GREEN,16));p.monoImage=upload(IconImages.solid(Color.BLUE,16));
        p.notificationImage=upload(IconImages.solid(Color.WHITE,16));p.generatedBackground=upload(IconImages.solid(Color.BLACK,16));
        p.generatedForeground=upload(IconImages.solid(Color.YELLOW,16));IconPolicy.save(c,PKG,p);
        IconPolicy shared=new IconPolicy();shared.background=p.background;IconPolicy.save(c,"com.example.shared",shared);
        String old=p.background,orphan=upload(IconImages.solid(Color.CYAN,16));
        File pending=File.createTempFile("image-",".png",IconOverrides.root(c));
        p.background=upload(IconImages.solid(Color.MAGENTA,16));IconPolicy.save(c,PKG,p);
        for(String id:List.of(old,p.background,p.foreground,p.monoImage,p.notificationImage,p.generatedBackground,p.generatedForeground))
            assertTrue("Retain inactive fields and cross-package references",IconOverrides.image(c,id).isFile());
        assertFalse(IconOverrides.image(c,orphan).exists());assertTrue(pending.isFile());
        shared.background="";IconPolicy.save(c,"com.example.shared",shared);
        assertFalse("Delete only after the last reference is removed",IconOverrides.image(c,old).exists());
    }
    @Test public void failedPolicyCommitDoesNotPrunePublishedPixels()throws Exception{
        IconPolicy p=new IconPolicy();p.background=upload(IconImages.solid(Color.BLUE,16));IconPolicy.save(c,PKG,p);
        String old=p.background;p.background=upload(IconImages.solid(Color.RED,16));
        var prefs=IconLayerStore.prefs(c);
        android.content.SharedPreferences failing=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.class},(proxy,method,args)->{
                if(!method.getName().equals("edit"))return method.invoke(prefs,args);
                var editor=prefs.edit();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(ep,em,ea)->{
                    if(em.getName().equals("commit"))return false;
                    Object result=em.invoke(editor,ea);return result==editor?ep:result;
                });
            });
        Context broken=new android.content.ContextWrapper(c){
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return failing;}
        };
        assertThrows(IllegalStateException.class,()->IconPolicy.save(broken,PKG,p));
        assertEquals(old,IconPolicy.read(c,PKG).background);assertTrue(IconOverrides.image(c,old).isFile());
    }
    @Test public void concurrentPublicationCannotPruneAnImportBeforeItIsPublished()throws Exception{
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        var started=new java.util.concurrent.CountDownLatch(1);
        try{
            String id;
            java.util.concurrent.Future<?> other;
            synchronized(IconPolicy.class){
                id=upload(IconImages.solid(Color.CYAN,16));
                other=worker.submit(()->{started.countDown();IconPolicy.save(c,"com.example.concurrent",new IconPolicy());});
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,()->other.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
                assertTrue(IconOverrides.image(c,id).isFile());
                IconPolicy p=new IconPolicy();p.background=id;IconPolicy.save(c,PKG,p);
            }
            other.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(IconOverrides.image(c,id).isFile());assertEquals(id,IconPolicy.read(c,PKG).background);
        }finally{worker.shutdownNow();}
    }
    @Test public void monochromeCopiesStayIndependent(){
        var icon=new ImportedIcon(c.getResources(),IconImages.solid(Color.RED,32),IconImages.solid(Color.BLUE,32),IconImages.solid(Color.WHITE,32));
        var copy=(ImportedIcon)icon.getConstantState().newDrawable();
        assertNotSame(icon.getMonochrome(),copy.getMonochrome());copy.getMonochrome().setAlpha(1);
        assertEquals(255,icon.getMonochrome().getAlpha());
    }
    @Test public void noLibrarySnapshotLoadsAndCancelledReloadKeepsPreviousPixels()throws Exception{
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new AdaptiveIconDrawable(new ColorDrawable(Color.MAGENTA),new ColorDrawable(Color.GREEN)));
        android.os.Bundle config=new android.os.Bundle();config.putBoolean("enabled",true);config.putStringArrayList("targets",new ArrayList<>(List.of(PKG)));
        IconPolicy p=new IconPolicy();p.color=IconPolicy.MANUAL;
        p.background=upload(IconImages.solid(Color.MAGENTA,32));p.foreground=upload(IconImages.solid(Color.GREEN,32));IconPolicy.save(c,PKG,p);
        config.putString("policies",IconLayerStore.prefs(c).getString("policies","{}"));
        Robolectric.buildContentProvider(IconLayerProvider.class).create();
        assertTrue(IconAssets.load(c,config,()->false));
        IconAssets.Entry first=IconAssets.snapshot().entries().get(PKG);assertNotNull(first);
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new ColorDrawable(Color.RED)));
        assertFalse(IconAssets.load(c,config,()->true));
        IconAssets.Entry after=IconAssets.snapshot().entries().get(PKG);
        assertEquals(Color.MAGENTA,after.background().getPixel(0,0));assertSame(first,after);
        assertNotNull(IconAssets.colored(c,PKG));
        config.putBoolean("enabled",false);IconAssets.load(c,config,()->false);assertNull(IconAssets.colored(c,PKG));
    }
    @Test public void everyChannelCanOptOutWithoutChangingOtherApplications()throws Exception{
        IconPolicy p=new IconPolicy();p.color=IconPolicy.ORIGINAL;p.monochrome=IconPolicy.UNCHANGED;p.notification=IconPolicy.UNCHANGED;
        IconPolicy.save(c,PKG,p);
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.GREEN)));
        var result=IconAssets.resolve(c,PKG,IconPolicy.read(c,PKG),"",64,true);
        assertNull(result);assertEquals(IconPolicy.AUTO,IconPolicy.read(c,"com.example.another").color);
    }
    @Test public void algorithmRequiresOptInAndLaunchThenReusesItsCache()throws Exception{
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new BitmapDrawable(c.getResources(),IconImages.solid(Color.BLUE,96)));
        IconLayerProvider provider=Robolectric.buildContentProvider(IconLayerProvider.class).create().get();
        IconLayerStore.enabled(c,true);
        provider.generate(PKG);assertEquals("",IconPolicy.read(c,PKG).generatedVersion);
        IconPolicy p=new IconPolicy();p.color=IconPolicy.ALGORITHM;IconPolicy.save(c,PKG,p);
        assertNull("Selecting algorithm or opening preview must not calculate pixels",IconAssets.resolve(c,PKG,p,"",256,true));
        provider.generate(PKG);p=IconPolicy.read(c,PKG);assertFalse(p.generatedVersion.isEmpty());
        int oldWidth=IconImages.contentBounds(IconImages.separate(IconImages.solid(Color.BLUE,256)).foreground()).width();
        int newWidth=IconImages.contentBounds(IconAssets.resolve(c,PKG,p,"",256,true).foreground()).width();
        assertEquals("Generated subjects are 30 percent larger than the 0.6.4 result",oldWidth*.845f,newWidth,2f);
        String previousForeground=p.generatedForeground;
        p.generatedVersion="3:"+c.getPackageManager().getPackageInfo(PKG,0).lastUpdateTime;IconPolicy.save(c,PKG,p);
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new BitmapDrawable(c.getResources(),IconImages.solid(Color.RED,96)));
        provider.generate(PKG);p=IconPolicy.read(c,PKG);
        assertNotEquals("Old algorithm pixels must be regenerated even when the app has not updated",previousForeground,p.generatedForeground);
        assertEquals(Color.RED,IconAssets.resolve(c,PKG,p,"",256,true).foreground().getPixel(128,128));
        File foreground=IconOverrides.image(c,p.generatedForeground);assertTrue(foreground.isFile());
        assertTrue(foreground.setLastModified(123456000));
        assertNotNull(IconAssets.resolve(c,PKG,p,"",256,true));
        provider.generate(PKG);assertEquals("A resume does not regenerate valid cache files",123456000,foreground.lastModified());
        p.color=IconPolicy.ORIGINAL;IconPolicy.save(c,PKG,p);
        assertNull(IconAssets.resolve(c,PKG,p,"",256,true));
    }
    @Test public void importedLayersKeepTargetResolutionAndNativeMonochromeSurvives()throws Exception{
        var nativeIcon=new AdaptiveIconDrawable(new ColorDrawable(Color.MAGENTA),new ColorDrawable(Color.GREEN),new ColorDrawable(Color.WHITE));
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,nativeIcon);
        IconPolicy p=new IconPolicy();p.color=IconPolicy.MANUAL;p.monochrome=IconPolicy.UNCHANGED;
        p.background=upload(IconImages.solid(Color.BLUE,384));
        Bitmap foreground=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888);Paint paint=new Paint();paint.setColor(Color.RED);new Canvas(foreground).drawRect(140,100,244,284,paint);
        p.foreground=upload(foreground);
        var result=IconAssets.resolve(c,PKG,p,"",256,true);
        assertEquals(256,result.foreground().getWidth());assertFalse(result.monoChanged());
        assertEquals(Color.WHITE,result.mono().getPixel(128,128));assertNull(result.notification());
    }
    @Test public void algorithmNativeLayersAlsoShrinkWithoutNormalizingTheirPadding()throws Exception{
        Bitmap foreground=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        Paint paint=new Paint();paint.setColor(Color.RED);new Canvas(foreground).drawRect(32,64,160,192,paint);
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new BitmapDrawable(c.getResources(),foreground)));
        IconPolicy p=new IconPolicy();p.color=IconPolicy.ALGORITHM;IconPolicy.save(c,PKG,p);IconLayerStore.enabled(c,true);
        Robolectric.buildContentProvider(IconLayerProvider.class).create().get().generate(PKG);
        var generated=IconAssets.resolve(c,PKG,IconPolicy.read(c,PKG),"",256,true);
        assertNotNull(generated);
        Rect bounds=IconImages.contentBounds(generated.foreground());
        assertEquals(128*.845f,bounds.width(),2f);assertEquals(128*.845f,bounds.height(),2f);
        assertEquals(128+(96-128)*.845f,bounds.exactCenterX(),1f);
        assertEquals(128,bounds.exactCenterY(),1f);
        assertEquals(Color.BLUE,generated.background().getPixel(0,0));
        assertEquals(new Rect(32,64,160,192),IconImages.contentBounds(foreground));
    }
    @Test public void refreshMigratesExistingAlgorithmCacheButDoesNotGenerateNewSelections()throws Exception{
        Shadows.shadowOf(c.getPackageManager()).setApplicationIcon(PKG,new BitmapDrawable(c.getResources(),IconImages.solid(Color.RED,96)));
        IconLayerStore.enabled(c,true);
        IconPolicy cached=new IconPolicy();cached.color=IconPolicy.ALGORITHM;
        cached.generatedBackground=upload(IconImages.solid(Color.BLUE,256));
        cached.generatedForeground=cached.generatedBackground;cached.generatedVersion="4:old";
        IconPolicy.save(c,PKG,cached);
        String fresh="com.example.newselection";IconPolicy selected=new IconPolicy();selected.color=IconPolicy.ALGORITHM;
        IconPolicy.save(c,fresh,selected);
        var provider=Robolectric.buildContentProvider(IconLayerProvider.class).create().get();
        provider.migrateCached();
        var migrated=IconPolicy.read(c,PKG);
        assertTrue(migrated.generatedVersion.startsWith("6:"));
        assertNotEquals(cached.generatedForeground,migrated.generatedForeground);
        assertFalse("Migration reclaims the superseded generated hash",IconOverrides.image(c,cached.generatedForeground).exists());
        assertEquals("",IconPolicy.read(c,fresh).generatedVersion);
        assertEquals("",IconPolicy.read(c,fresh).generatedForeground);
        File pixels=IconOverrides.image(c,migrated.generatedForeground);assertTrue(pixels.setLastModified(123456000));
        provider.migrateCached();assertEquals("Current caches are reused during refresh",123456000,pixels.lastModified());
    }
    @Test public void nonSquareArtworkKeepsItsAspectAndGeneratedSubjectFitsTheAdaptiveViewport(){
        Bitmap wide=IconImages.solid(Color.RED,200);
        Bitmap crop=Bitmap.createBitmap(wide,0,0,200,100);
        Bitmap rendered=IconImages.render(new BitmapDrawable(c.getResources(),crop),96);
        Rect bounds=IconImages.contentBounds(rendered);assertEquals(96,bounds.width());assertEquals(48,bounds.height());
        var layers=IconImages.separate(rendered);
        var icon=new ImportedIcon(c.getResources(),layers.background(),layers.foreground());
        Rect finalBounds=IconImages.contentBounds(layers.foreground());
        assertTrue(finalBounds.width()<=53);assertEquals(2f,(float)finalBounds.width()/finalBounds.height(),.15f);
        assertNotNull(IconImages.render(icon,96));
    }
    @Test public void roundedArtworkShrinksInsideTheAdaptiveViewportWithoutLosingItsOwnBackground(){
        Bitmap source=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLUE);
        new Canvas(source).drawRoundRect(0,0,96,96,24,24,paint);
        var layers=IconImages.separate(source);
        float visibleWidth=IconImages.contentBounds(layers.foreground()).width()*1.5f;
        assertTrue("Rounded subjects should occupy 60-70 percent of the former full viewport",visibleWidth>=96*.60f&&visibleWidth<=96*.70f);
        assertEquals(Color.BLUE,layers.background().getPixel(0,0));
        assertEquals(Color.BLUE,layers.foreground().getPixel(48,48));
    }
    private String upload(Bitmap b)throws Exception{
        File file=File.createTempFile("test-user-image-",".png",c.getCacheDir());
        try(OutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        return IconOverrides.importImage(c,Uri.fromFile(file));
    }
}
