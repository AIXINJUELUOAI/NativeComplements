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
    @Test public void handoffRequestsHaveSeparateWakeDeadlineAndOrderedNonceReplies()throws Exception{
        assertTrue(AliveHandoffTransport.acceptsSender(10123,10082,10082,"com.android.systemui"));
        assertFalse(AliveHandoffTransport.acceptsSender(10123,10082,10123,"com.android.systemui"));
        assertFalse(AliveHandoffTransport.acceptsSender(10123,10082,10082,"example.sender"));
        assertFalse(AliveHandoffTransport.acceptsSender(10123,10082,-1,null));
        assertFalse(AliveHandoffTransport.acceptsSender(10123,110082,110082,"com.android.systemui"));
        assertTrue(AliveHandoffTransport.acceptsSender(110123,110082,110082,"com.android.systemui"));
        java.util.List<Integer> replies=new java.util.ArrayList<>();String token=java.util.UUID.randomUUID().toString();
        android.os.Binder reply=new android.os.Binder(){
            @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel result,int flags){
                assertEquals(AliveSleepHandoff.REPLY_CODE,code);data.enforceInterface("dev.nativecomplements.module.alive.SleepReply");
                assertEquals(token,data.readString());assertEquals(0,data.readInt());replies.add(data.readInt());data.enforceNoDataAvail();return true;
            }
        };
        android.os.Bundle extras=new android.os.Bundle();extras.putString("token",token);extras.putBinder("reply",reply);
        long now=android.os.SystemClock.uptimeMillis();extras.putLong("expires",now+2500);
        AliveSleepHandoff.Request wake=AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE_WAKE,extras,0);
        assertNotNull(wake);assertTrue(wake.waking);wake.ready();wake.finished();wake.cancel();
        assertEquals(java.util.List.of(1,2,0),replies);
        extras.putLong("expires",now+2501);assertNull(AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE_WAKE,extras,0));
        extras.putLong("expires",now+7800);
        AliveSleepHandoff.Request sleep=AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE,extras,0);assertNotNull(sleep);assertFalse(sleep.waking);
        assertNull(AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE,extras,1));
        extras.putLong("expires",now);assertNull(AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE,extras,0));
        extras.putLong("expires",now+100);extras.putString("token","bad");assertNull(AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE,extras,0));
        extras.putString("token",token);extras.putBinder("reply",null);assertNull(AliveSleepHandoff.parse(AliveSleepHandoff.ACTION_PREPARE,extras,0));
    }
    @Test public void screenReceiverAllowsSystemUiUidButOnlyPlatformProtectedActions(){
        final boolean[] registered={false};
        android.content.Context context=new android.content.ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()){
            @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver receiver,android.content.IntentFilter filter,
                    String permission,android.os.Handler handler,int flags){
                assertEquals(android.content.Context.RECEIVER_EXPORTED,flags);
                assertEquals(4,filter.countActions());
                for(String action:new String[]{android.content.Intent.ACTION_SCREEN_ON,android.content.Intent.ACTION_SCREEN_OFF,
                        android.content.Intent.ACTION_USER_PRESENT,android.content.Intent.ACTION_USER_UNLOCKED})assertTrue(filter.hasAction(action));
                assertNull(permission);registered[0]=true;return null;
            }
        };
        AliveWallpaperService.registerScreenEvents(context,new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context c,android.content.Intent intent){}
        },new android.os.Handler(android.os.Looper.getMainLooper()));
        assertTrue(registered[0]);
    }
    @Test public void systemAodPreferenceDistinguishesOffOnAndUnknown(){
        var resolver=org.robolectric.RuntimeEnvironment.getApplication().getContentResolver();
        android.provider.Settings.Secure.putString(resolver,AliveSystemState.AOD_SETTING,null);
        assertEquals(-1,AliveSystemState.aod(org.robolectric.RuntimeEnvironment.getApplication()));
        android.provider.Settings.Secure.putInt(resolver,AliveSystemState.AOD_SETTING,0);
        assertEquals(0,AliveSystemState.aod(org.robolectric.RuntimeEnvironment.getApplication()));
        android.provider.Settings.Secure.putInt(resolver,AliveSystemState.AOD_SETTING,1);
        assertEquals(1,AliveSystemState.aod(org.robolectric.RuntimeEnvironment.getApplication()));
        android.provider.Settings.Secure.putInt(resolver,AliveSystemState.AOD_SETTING,2);
        assertEquals(-1,AliveSystemState.aod(org.robolectric.RuntimeEnvironment.getApplication()));
        android.provider.Settings.Secure.putString(resolver,AliveSystemState.AOD_SETTING,null);
    }
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
