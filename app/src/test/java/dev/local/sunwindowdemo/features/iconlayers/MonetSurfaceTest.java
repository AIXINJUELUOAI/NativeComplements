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
    @Test public void disablingRestoresNativeSplashAndDroppingActivityMonoDoesNotInventArtwork(){
        config.putBoolean("coverMonet",false);load();
        assertNull(SystemIconModule.splashIcon(context,PKG,new ColorDrawable(Color.RED)));
        ActivityShadow shadow=Shadow.extract(activity);shadow.icon=new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.BLUE));
        config.putBoolean("coverMonet",true);load();
        assertTrue(IconAssets.snapshot().activityMonos().isEmpty());
        assertNull(IconAssets.cover(context,PKG,new ColorDrawable(Color.RED)));
        assertNull(SystemIconModule.splashIcon(context,PKG,null));
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
    @Implements(LauncherActivityInfo.class)
    public static class ActivityShadow {
        ComponentName component;Drawable icon;
        @Implementation protected ComponentName getComponentName(){return component;}
        @Implementation protected Drawable getIcon(int density){return icon;}
    }
}
