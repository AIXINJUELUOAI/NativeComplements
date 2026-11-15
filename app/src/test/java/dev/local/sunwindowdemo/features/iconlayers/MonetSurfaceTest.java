package dev.local.sunwindowdemo.features.iconlayers;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=MonetSurfaceTest.ActivityShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonetSurfaceTest {
    private static final String PKG="com.example.activitymono";
    private Context context;
    private Bundle config;
    private LauncherActivityInfo activity;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();config=new Bundle();
        config.putBoolean("systemThemed",true);config.putBoolean("coverMonet",true);
        // The icon-adaptation master switch is deliberately OFF.
        config.putBoolean("enabled",false);
        activity=Shadow.newInstanceOf(LauncherActivityInfo.class);
        ActivityShadow shadow=Shadow.extract(activity);shadow.component=new ComponentName(PKG,PKG+".Main");
        shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE),new ColorDrawable(Color.WHITE));
        Shadows.shadowOf(context.getSystemService(LauncherApps.class)).addActivity(android.os.Process.myUserHandle(),activity);
        load();
    }
    private void load(){assertTrue(IconAssets.load(context,config,()->false));}
    private static void snapshot(IconAssets.Snapshot value)throws Exception{
        Field field=IconAssets.class.getDeclaredField("snapshot");field.setAccessible(true);field.set(null,value);
    }
    private static int pixel(Drawable drawable){
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        drawable.setBounds(0,0,192,192);drawable.draw(new Canvas(bitmap));return bitmap.getPixel(96,96);
    }
    @Test public void activityMonoWorksForApplicationAndSplashWithoutMasterAdaptation(){
        assertTrue(IconAssets.snapshot().entries().isEmpty());
        assertTrue(IconAssets.snapshot().activityMonos().containsKey(PKG));
        Drawable applicationIcon=new ColorDrawable(Color.RED);
        assertNotNull(IconAssets.cover(context,PKG,applicationIcon));
        assertNotNull(SystemIconModule.splashIcon(context,PKG,null));
        assertEquals(IconAssets.themedColors(context)[1],pixel(SystemIconModule.splashIcon(context,PKG,applicationIcon)));
        assertNull(IconAssets.window(context,PKG,applicationIcon));
    }
    @Test public void systemThemeAndIndependentSwitchesGateBothSurfaces(){
        config.putBoolean("coverMonet",false);config.putBoolean("windowMonet",true);load();
        assertNull(IconAssets.cover(context,PKG,null));assertNotNull(IconAssets.window(context,PKG,null));
        config.putBoolean("systemThemed",false);load();
        assertTrue(IconAssets.snapshot().activityMonos().isEmpty());
        assertNull(IconAssets.cover(context,PKG,null));assertNull(IconAssets.window(context,PKG,null));
        assertNull(SystemIconModule.splashIcon(context,PKG,new ColorDrawable(Color.RED)));
    }
    @Test public void disablingRestoresNativeSplashAndMissingActivityMonoIsConverted(){
        config.putBoolean("coverMonet",false);load();
        assertNull(SystemIconModule.splashIcon(context,PKG,new ColorDrawable(Color.RED)));
        ActivityShadow shadow=Shadow.extract(activity);shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE));
        config.putBoolean("coverMonet",true);load();
        assertTrue(IconAssets.snapshot().activityMonos().containsKey(PKG));
        assertNotNull(IconAssets.cover(context,PKG,new ColorDrawable(Color.RED)));
        assertNotNull(SystemIconModule.splashIcon(context,PKG,null));
    }
    @Test public void moduleMonoWinsAndNativeInputIsNotTintedInPlace()throws Exception{
        Bitmap transparent=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        IconAssets.Entry entry=new IconAssets.Entry(null,null,transparent,null,false,true,true);
        snapshot(new IconAssets.Snapshot(Map.of(PKG,entry),IconAssets.snapshot().activityMonos(),"",0,0,Set.of()));
        Drawable nativeMono=new ColorDrawable(Color.WHITE);
        AdaptiveIconDrawable original=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE),nativeMono);
        AdaptiveIconDrawable themed=(AdaptiveIconDrawable)IconAssets.cover(context,PKG,original);
        // Module's transparent mono wins over the opaque activity/native monochrome.
        assertEquals(IconAssets.themedColors(context)[0],pixel(themed));
        assertEquals(Color.WHITE,pixel(nativeMono));
    }
    @Test public void splashFallsBackToModuleColourWhenMonetIsUnavailable()throws Exception{
        Bitmap bg=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bg.eraseColor(Color.GREEN);
        Bitmap fg=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        IconAssets.Entry entry=new IconAssets.Entry(bg,fg,null,null,true,false,false);
        snapshot(new IconAssets.Snapshot(Map.of(PKG,entry),Map.of(),"",0,0,Set.of()));
        assertNotNull(SystemIconModule.splashIcon(context,PKG,null));
        assertEquals(Color.GREEN,pixel(SystemIconModule.splashIcon(context,PKG,null)));
    }
    @Test public void opaqueLegacyArtworkKeepsTonalDetailAndBothSurfacesShareConvertedSource(){
        Bitmap photo=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)photo.setPixel(x,y,Color.rgb(x*8,y*8,(x+y)*4));
        ActivityShadow shadow=Shadow.extract(activity);shadow.icon=new BitmapDrawable(context.getResources(),photo);
        config.putBoolean("windowMonet",true);load();
        Bitmap mask=IconAssets.snapshot().activityMonos().get(PKG);assertNotNull(mask);
        int min=255,max=0;for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++){
            int p=mask.getPixel(x,y);assertEquals(255,Color.red(p));assertEquals(255,Color.green(p));assertEquals(255,Color.blue(p));
            min=Math.min(min,Color.alpha(p));max=Math.max(max,Color.alpha(p));
        }
        assertTrue("a photograph must not become a solid/empty silhouette",max-min>100);assertTrue(max>128);
        assertEquals(pixel(IconAssets.cover(context,PKG,null)),pixel(IconAssets.window(context,PKG,null)));
        config.putBoolean("windowMonet",false);config.putBoolean("coverMonet",false);load();
        assertTrue(IconAssets.snapshot().activityMonos().isEmpty());
        assertNull(IconAssets.window(context,PKG,shadow.icon));assertNull(IconAssets.cover(context,PKG,shadow.icon));
    }
    @Test public void adaptiveForegroundIsPreferredAndEmptyForegroundUsesFullArtwork(){
        Bitmap foreground=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(foreground);android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(Color.RED);
        canvas.drawRect(10,8,22,24,paint);
        ActivityShadow shadow=Shadow.extract(activity);
        shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new BitmapDrawable(context.getResources(),foreground));load();
        Bitmap mask=IconAssets.snapshot().activityMonos().get(PKG);assertEquals(0,Color.alpha(mask.getPixel(0,0)));assertEquals(255,Color.alpha(mask.getPixel(mask.getWidth()/2,mask.getHeight()/2)));
        shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new ColorDrawable(Color.TRANSPARENT));load();
        assertNotNull(IconAssets.snapshot().activityMonos().get(PKG));assertNotNull(IconAssets.cover(context,PKG,null));
    }
    @Test public void seventyPercentPhotographicForegroundKeepsDetailInsteadOfSolidAlpha(){
        Bitmap foreground=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        for(int y=8;y<92;y++)for(int x=8;x<92;x++)foreground.setPixel(x,y,Color.rgb((x-8)*3,(y-8)*3,(x+y-16)*3/2));
        ActivityShadow shadow=Shadow.extract(activity);
        shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new BitmapDrawable(context.getResources(),foreground));load();
        Bitmap mask=IconAssets.snapshot().activityMonos().get(PKG);assertNotNull(mask);
        int min=255,max=0;
        // Sample only within the opaque photograph, not its transparent margins.
        for(int y=20;y<108;y++)for(int x=20;x<108;x++){
            int alpha=Color.alpha(mask.getPixel(x,y));min=Math.min(min,alpha);max=Math.max(max,alpha);
        }
        assertTrue("photo tonal detail must survive the foreground branch",max-min>100);
    }
    @Test public void fullSolidForegroundFallsBackToFullActualArtwork(){
        Bitmap image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)image.setPixel(x,y,Color.rgb(x*8,y*8,(x+y)*4));
        Bitmap solid=IconImages.render(new ColorDrawable(Color.TRANSPARENT),128);
        solid.eraseColor(Color.BLUE);assertNull(IconImages.forcedMonochrome(solid,true));
        // A near-transparent, uniform foreground must not erase the detailed real background.
        ActivityShadow shadow=Shadow.extract(activity);
        shadow.icon=new AdaptiveIconDrawable(new BitmapDrawable(context.getResources(),image),new ColorDrawable(0x010000ff));load();
        Bitmap mask=IconAssets.snapshot().activityMonos().get(PKG);assertNotNull(mask);
        int min=255,max=0;
        for(int y=16;y<112;y++)for(int x=16;x<112;x++){
            int alpha=Color.alpha(mask.getPixel(x,y));min=Math.min(min,alpha);max=Math.max(max,alpha);
        }
        assertTrue("full artwork detail must survive a meaningless foreground",max-min>100);
    }
    @Test public void brokenActivityIconFallsBackToApplicationOnWorker(){
        android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();info.packageName=PKG;
        info.applicationInfo=new android.content.pm.ApplicationInfo();info.applicationInfo.packageName=PKG;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        Shadows.shadowOf(context.getPackageManager()).setApplicationIcon(PKG,new ColorDrawable(Color.RED));
        ActivityShadow shadow=Shadow.extract(activity);shadow.broken=true;load();
        assertNotNull(IconAssets.snapshot().activityMonos().get(PKG));assertNotNull(IconAssets.cover(context,PKG,null));
    }
    @Test public void whollyTransparentArtworkStillCannotInventVisibleContent(){
        ActivityShadow shadow=Shadow.extract(activity);shadow.icon=new ColorDrawable(Color.TRANSPARENT);load();
        assertNull(IconAssets.snapshot().activityMonos().get(PKG));
    }
    @Implements(LauncherActivityInfo.class)
    public static class ActivityShadow {
        ComponentName component;Drawable icon;boolean broken;
        @Implementation protected ComponentName getComponentName(){return component;}
        @Implementation protected Drawable getIcon(int density){if(broken)throw new IllegalStateException("activity unavailable");return icon;}
    }
}
