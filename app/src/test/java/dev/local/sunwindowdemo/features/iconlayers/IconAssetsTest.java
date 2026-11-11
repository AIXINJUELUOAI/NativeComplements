package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconAssetsTest {
    private static final String PKG="com.example.adapted";
    private Context context;
    private TestProvider provider;
    private int callbacks;

    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();
        PackageInfo app=new PackageInfo();app.packageName=PKG;app.applicationInfo=new ApplicationInfo();app.applicationInfo.packageName=PKG;
        Shadows.shadowOf(context.getPackageManager()).installPackage(app);
        Shadows.shadowOf(context.getPackageManager()).setApplicationIcon(PKG,new ColorDrawable(Color.RED));
        provider=new TestProvider();ProviderInfo info=new ProviderInfo();info.authority=IconLayerStore.URI.getAuthority();
        provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
        provider.config=new Bundle();provider.config.putBoolean("enabled",true);provider.config.putString("generation","g_"+"a".repeat(32));
        provider.config.putStringArrayList("targets",new ArrayList<>(List.of(PKG)));
        provider.config.putStringArrayList("packages",new ArrayList<>(List.of(PKG)));
        provider.background=png(Color.BLUE);provider.foreground=png(Color.TRANSPARENT);
        set("host",context);set("worker",new Handler(Looper.getMainLooper()));set("ready",false);
        set("snapshot",new IconAssets.Snapshot(Map.of(),"",0,0,Set.of()));
        IconAssets.onReload(()->callbacks++);
    }
    private static void set(String name,Object value)throws Exception{
        Field field=IconAssets.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
    private void reload()throws Exception{
        Method method=IconAssets.class.getDeclaredMethod("reload");method.setAccessible(true);method.invoke(null);idle(0);
    }
    private void idle(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
    private File png(int color)throws Exception{
        File file=File.createTempFile("icon-test-",".png",context.getCacheDir());
        Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);
        try(var stream=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}
        bitmap.recycle();return file;
    }
    private int menuPixel()throws Exception{
        Method method=Class.forName("dev.local.sunwindowdemo.AppCatalog").getDeclaredMethod("icon",Context.class,String.class,Drawable.class);
        method.setAccessible(true);Drawable icon=(Drawable)method.invoke(null,context,PKG,new ColorDrawable(Color.RED));
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,192,192);icon.draw(new Canvas(bitmap));
        return bitmap.getPixel(96,96);
    }

    @Test public void startupRetriesNullAndEmptyTargetsWithoutExposingOriginalIcon()throws Exception{
        provider.nullReads=1;provider.emptyReads=1;
        reload();assertFalse(IconAssets.ready());assertEquals(Color.TRANSPARENT,menuPixel());
        idle(250);assertFalse(IconAssets.ready());assertEquals(0,callbacks);
        idle(500);assertTrue(IconAssets.ready());assertEquals(3,provider.reads);assertEquals(1,callbacks);
        assertTrue(IconAssets.colored(context,PKG) instanceof ImportedIcon);
        assertEquals(Color.BLUE,menuPixel());
    }
    @Test public void failedRefreshRetainsSnapshotAndNewerDisabledChoiceCancelsOldRetry()throws Exception{
        reload();var previous=IconAssets.snapshot();assertEquals(Color.BLUE,menuPixel());
        provider.nullReads=100;reload();assertSame(previous,IconAssets.snapshot());assertTrue(IconAssets.ready());
        assertEquals(Color.BLUE,menuPixel());
        provider.nullReads=0;provider.config=new Bundle();reload();int reads=provider.reads;
        assertTrue(IconAssets.ready());assertEquals(Color.RED,menuPixel());
        idle(20000);assertEquals(reads,provider.reads);assertEquals(Color.RED,menuPixel());
    }
    @Test public void retriesAreBoundedAndRemovedListenersStayRemoved()throws Exception{
        provider.nullReads=100;Runnable listener=()->fail("removed listener called");
        IconAssets.onReload(listener);IconAssets.removeOnReload(listener);
        reload();idle(30000);assertEquals(6,provider.reads);assertFalse(IconAssets.ready());assertEquals(0,callbacks);
        provider.nullReads=0;provider.config=new Bundle();reload();assertTrue(IconAssets.ready());assertEquals(1,callbacks);
    }

    public static class TestProvider extends ContentProvider {
        Bundle config;int reads,nullReads,emptyReads;File background,foreground;
        @Override public boolean onCreate(){return true;}
        @Override public Bundle call(String method,String arg,Bundle extras){
            reads++;if(nullReads-->0)return null;
            Bundle result=new Bundle(config);
            if(emptyReads-->0)result.putStringArrayList("targets",new ArrayList<>());
            return result;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            return ParcelFileDescriptor.open("0.png".equals(uri.getLastPathSegment())?background:foreground,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){throw new UnsupportedOperationException();}
        @Override public String getType(Uri uri){return null;}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    }
}
