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
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AliveSettings.APPLIED,0).edit().clear().commit();
        AliveSettings.Snapshot defaults=AliveSettings.readApplied(context);
        assertEquals(0,defaults.aod);assertEquals(0,defaults.lock);assertEquals(6,defaults.home);
        Class<?> textures=Class.forName("org.aliveclean.TextureMotion");
        var constructor=textures.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class);constructor.setAccessible(true);
        var packet=textures.getDeclaredMethod("packet");packet.setAccessible(true);
        for(int mode=0;mode<3;mode++){
            Object motion=constructor.newInstance(1080,2400,mode,defaults.lock,defaults.home);
            java.nio.ByteBuffer values=(java.nio.ByteBuffer)packet.invoke(motion);
            for(int slot=0;slot<20;slot++)assertEquals(0f,values.getFloat(),0f);
        }
        // Independent channels: selecting home glass must not allocate a lock glass timeline.
        var lockTrack=textures.getDeclaredField("lock");lockTrack.setAccessible(true);
        var homeTrack=textures.getDeclaredField("home");homeTrack.setAccessible(true);
        Object homeOnly=constructor.newInstance(1080,2400,1,0,7);
        assertNull(lockTrack.get(homeOnly));assertNotNull(homeTrack.get(homeOnly));
        Object lockOnly=constructor.newInstance(1080,2400,2,3,6);
        assertNotNull(lockTrack.get(lockOnly));assertNull(homeTrack.get(lockOnly));
    }
    @Test public void draftStaysIsolatedUntilApplyingTheWholeSnapshot()throws Exception{
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        var applied=context.getSharedPreferences(AliveSettings.APPLIED,0);
        var draft=context.getSharedPreferences(AliveSettings.DRAFT,0);
        applied.edit().clear().putInt("style",4).commit();draft.edit().clear().commit();
        AliveSettings.Snapshot old=AliveSettings.beginEdit(context);
        assertEquals(4,old.aod);assertEquals(0,old.lock);assertEquals(6,old.home);
        AliveSettings.Snapshot next=new AliveSettings.Snapshot(0,3,7,"alive-image-1234.jpg");
        AliveSettings.saveDraft(context,next);
        assertEquals(next,AliveSettings.readDraft(context));assertEquals(old,AliveSettings.readApplied(context));
        java.io.File photo=next.photoFile(context);
        try{
            try(var output=new java.io.FileOutputStream(photo)){output.write(1);}
            assertEquals(next,AliveSettings.applyDraft(context));
            assertEquals(next,AliveSettings.readApplied(context));
            assertTrue(applied.contains("applied_revision"));
        }finally{photo.delete();applied.edit().clear().commit();draft.edit().clear().commit();}
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
