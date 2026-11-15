package dev.local.sunwindowdemo.features.alive;

import android.content.ComponentName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class AliveAmbientAccessTest {
    @Test public void photoStylesDoNotSilentlyEnableLockOrHomeGlass()throws Exception{
        Class<?> renderer=Class.forName("org.aliveclean.AliveRenderer");
        var lock=renderer.getDeclaredField("LOCK_TEXTURE");lock.setAccessible(true);
        var home=renderer.getDeclaredField("HOME_TEXTURE");home.setAccessible(true);
        assertEquals(0,lock.getInt(null));assertEquals(6,home.getInt(null));
        Class<?> textures=Class.forName("org.aliveclean.TextureMotion");
        var constructor=textures.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class);constructor.setAccessible(true);
        var packet=textures.getDeclaredMethod("packet");packet.setAccessible(true);
        for(int mode=0;mode<3;mode++){
            Object motion=constructor.newInstance(1080,2400,mode,lock.getInt(null),home.getInt(null));
            java.nio.ByteBuffer values=(java.nio.ByteBuffer)packet.invoke(motion);
            for(int slot=0;slot<20;slot++)assertEquals(0f,values.getFloat(),0f);
        }
    }
    @Test public void onlyExactAmbientComponentAndKnownUserCanConsumeOneBindException()throws Exception{
        Class<?> type=Class.forName(AliveAmbientAccess.class.getName()+"$BindScope");
        var constructor=type.getDeclaredConstructor(ComponentName.class,int.class);constructor.setAccessible(true);
        var suppress=type.getDeclaredMethod("suppress",ComponentName.class);suppress.setAccessible(true);
        ComponentName own=new ComponentName("dev.nativecomplements.module","dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService");
        ComponentName basic=new ComponentName("dev.nativecomplements.module","dev.local.sunwindowdemo.features.alive.AliveWallpaperService");
        Object scope=constructor.newInstance(own,0);
        assertEquals(false,suppress.invoke(scope,basic));assertEquals(true,suppress.invoke(scope,own));assertEquals(false,suppress.invoke(scope,own));
        assertEquals(false,suppress.invoke(constructor.newInstance(own,-1),own));
        assertEquals(false,suppress.invoke(constructor.newInstance(basic,0),own));
        assertEquals(false,suppress.invoke(constructor.newInstance(null,-1),own));
    }
}
