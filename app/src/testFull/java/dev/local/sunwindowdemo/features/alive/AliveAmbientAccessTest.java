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
