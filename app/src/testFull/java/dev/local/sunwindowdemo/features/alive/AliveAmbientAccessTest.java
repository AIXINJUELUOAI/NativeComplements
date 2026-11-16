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
        var device=AliveAssets.runtime(context);
        java.io.File runtimePhoto=next.photoFile(device);
        java.io.File snapshot=new java.io.File(device.getNoBackupFilesDir(),"alive-applied.json");
        var publish=AliveSettings.class.getDeclaredMethod("publish",android.content.Context.class,AliveSettings.Snapshot.class);
        publish.setAccessible(true);
        try{
            try(var output=new java.io.FileOutputStream(photo)){output.write(1);}
            // Publication is checked independently of the imported, SHA-pinned 29 MiB native generation.
            publish.invoke(null,context,next);
            assertEquals(next,AliveSettings.readApplied(device));
            assertTrue(runtimePhoto.isFile());assertNotEquals(photo.getCanonicalPath(),runtimePhoto.getCanonicalPath());
            assertFalse(device.getSharedPreferences(AliveSettings.DRAFT,0).contains("photo"));
            assertFalse(applied.contains("applied_revision"));
            assertTrue(AliveSettings.runtimePreferences(context).contains("applied_revision"));
            var users=context.getSystemService(android.os.UserManager.class);
            org.robolectric.Shadows.shadowOf(users).setUserUnlocked(false);
            assertEquals(next,AliveSettings.readApplied(device));
            org.robolectric.Shadows.shadowOf(users).setUserUnlocked(true);
            // A changed same-name source must not overwrite the photo or its published snapshot.
            try(var output=new java.io.FileOutputStream(photo)){output.write(2);}
            try{publish.invoke(null,context,next);fail("Changed immutable photo accepted");}
            catch(java.lang.reflect.InvocationTargetException rejected){assertTrue(rejected.getCause() instanceof java.io.IOException);}
            assertEquals(1,java.nio.file.Files.readAllBytes(runtimePhoto.toPath())[0]);
            assertEquals(next,AliveSettings.readApplied(device));
            java.io.File blocked=new java.io.File(snapshot.getPath()+".new");assertTrue(blocked.mkdir());
            try{publish.invoke(null,context,new AliveSettings.Snapshot(1,2,8,next.photo));fail("Failed snapshot publication accepted");}
            catch(java.lang.reflect.InvocationTargetException rejected){assertTrue(rejected.getCause() instanceof java.io.IOException);}
            finally{blocked.delete();}
            assertEquals(next,AliveSettings.readApplied(device));
        }finally{
            org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.UserManager.class)).setUserUnlocked(true);
            photo.delete();runtimePhoto.delete();new android.util.AtomicFile(snapshot).delete();
            applied.edit().clear().commit();draft.edit().clear().commit();AliveSettings.runtimePreferences(context).edit().clear().commit();
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
