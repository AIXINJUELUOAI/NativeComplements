package dev.local.sunwindowdemo;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RotationSuggestionTest {
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static View find(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){
            View found=find(group.getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    public static final class NativeRotation {
        public int target=1,hidden,restored;
        public void hide(){hidden++;}
        public void state(boolean show,boolean immediate){if(show)restored++;}
    }
    @Test public void launcherActionsRequireSystemUiAndCurrentProposal()throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.pm.PackageInfo systemUi=new android.content.pm.PackageInfo();
        systemUi.packageName="com.android.systemui";
        systemUi.applicationInfo=new android.content.pm.ApplicationInfo();systemUi.applicationInfo.uid=1000;
        org.robolectric.Shadows.shadowOf(context.getPackageManager()).installPackage(systemUi);
        NativeRotation nativeRotation=new NativeRotation();
        AtomicInteger clicks=new AtomicInteger();View view=new View(context);view.setOnClickListener(v->clicks.incrementAndGet());
        Class<?> type=Class.forName(LauncherRotationSuggestion.class.getName()+"$Proposal");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object proposal=constructor.newInstance(context,nativeRotation,nativeRotation,view,
            NativeRotation.class.getMethod("hide"),NativeRotation.class.getMethod("state",boolean.class,boolean.class),NativeRotation.class.getField("target"));
        Field active=LauncherRotationSuggestion.class.getDeclaredField("active");active.setAccessible(true);active.set(null,proposal);
        Method transact=type.getDeclaredMethod("onTransact",int.class,android.os.Parcel.class,android.os.Parcel.class,int.class);transact.setAccessible(true);
        try{
            org.robolectric.shadows.ShadowBinder.setCallingUid(23456);
            assertFalse(deliver(transact,proposal,1));
            org.robolectric.shadows.ShadowBinder.setCallingUid(1000);
            assertTrue(deliver(transact,proposal,1));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,nativeRotation.hidden);
            assertTrue(deliver(transact,proposal,2));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,clicks.get());
            deliver(transact,proposal,2);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,clicks.get());
            active.set(null,proposal);nativeRotation.target=2;deliver(transact,proposal,2);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,clicks.get());
            active.set(null,proposal);deliver(transact,proposal,3);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,nativeRotation.restored);
            assertNull(active.get(null));
        }finally{active.set(null,null);org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid());}
    }
    @Test public void diagnosticProviderRejectsOtherUidsAndUnboundedWrites(){
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.pm.PackageInfo systemUi=new android.content.pm.PackageInfo();systemUi.packageName="com.android.systemui";
        systemUi.applicationInfo=new android.content.pm.ApplicationInfo();systemUi.applicationInfo.uid=1000;
        org.robolectric.Shadows.shadowOf(context.getPackageManager()).installPackage(systemUi);
        var provider=Robolectric.buildContentProvider(dev.local.sunwindowdemo.features.suggestions.SuggestionProvider.class).create().get();
        Bundle status=new Bundle();status.putString("hook","已安装");
        try{
            org.robolectric.shadows.ShadowBinder.setCallingUid(23456);
            assertThrows(SecurityException.class,()->provider.call("rotation_status",null,status));
            org.robolectric.shadows.ShadowBinder.setCallingUid(1000);
            provider.call("rotation_status",null,status);
            assertEquals("已安装",context.createDeviceProtectedStorageContext().getSharedPreferences("rotation_status",0).getString("hook",""));
            status.putString("arbitrary_setting","x");assertThrows(IllegalArgumentException.class,()->provider.call("rotation_status",null,status));
            status.remove("arbitrary_setting");status.putString("hook","x".repeat(201));
            assertThrows(IllegalArgumentException.class,()->provider.call("rotation_status",null,status));
        }finally{org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid());}
    }
    private static boolean deliver(Method method,Object proposal,int code)throws Exception {
        android.os.Parcel data=android.os.Parcel.obtain();
        try{data.writeInterfaceToken(LauncherRotationSuggestion.CHANNEL);data.setDataPosition(0);return (Boolean)method.invoke(proposal,code,data,null,0);}
        finally{data.recycle();}
    }
    @Test public void onlyTakeOverWhenEnabledAndDrawableAndUseNativeClickOnce()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        SuggestionHost host=new SuggestionHost(activity);
        Bundle settings=new Bundle();settings.putBoolean("rotate",true);
        AtomicInteger clicks=new AtomicInteger();
        try {
            set(host,"settings",settings);
            assertFalse(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            set(host,"windowContext",activity);set(host,"moduleContext",activity);
            set(host,"wm",activity.getSystemService(WindowManager.class));
            // Child defaults on, but the master switch is off: native button must survive.
            assertFalse(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            settings.putBoolean("enabled",true);
            set(host,"systemOverlay",true);
            assertFalse(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            set(host,"systemOverlay",false);
            settings.putBoolean("rotate",false);
            assertFalse(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            settings.putBoolean("rotate",true);
            int auto=Settings.System.getInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0);
            int rotation=Settings.System.getInt(activity.getContentResolver(),Settings.System.USER_ROTATION,0);
            android.graphics.drawable.Drawable nativeIcon=new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE);
            host.rotationIcon(nativeIcon);
            assertTrue(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            View button=find((View)get(host,"view"),"旋转屏幕");assertNotNull(button);
            assertSame(nativeIcon,((android.widget.ImageView)button).getDrawable());
            button.performClick();button.performClick();assertEquals(1,clicks.get());
            assertEquals(auto,Settings.System.getInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0));
            assertEquals(rotation,Settings.System.getInt(activity.getContentResolver(),Settings.System.USER_ROTATION,0));
            assertTrue(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
            host.rotationSuggestion(null,null);
            Method rotate=SuggestionHost.class.getDeclaredMethod("rotateScreen");rotate.setAccessible(true);rotate.invoke(host);
            assertEquals(1,clicks.get());assertNull(get(host,"rotationClick"));
            set(host,"wm",null);
            assertFalse(host.rotationSuggestion(clicks::incrementAndGet,()->{}));
        } finally { host.close();activity.finish(); }
    }
}
