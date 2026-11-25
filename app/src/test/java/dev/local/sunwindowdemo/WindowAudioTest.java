package dev.local.sunwindowdemo;

import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=WindowAudioTest.PlaybackSnapshot.class)
public class WindowAudioTest {
    private static final int UID=10266,OTHER_UID=10267;
    @org.robolectric.annotation.Implements(AudioManager.class)
    public static class PlaybackSnapshot extends org.robolectric.shadows.ShadowAudioManager {
        static List<AudioPlaybackConfiguration> configs=List.of();
        @org.robolectric.annotation.Implementation protected List<AudioPlaybackConfiguration> getActivePlaybackConfigurations(){return configs;}
    }
    @Before public void resetSnapshot(){PlaybackSnapshot.configs=List.of();}
    private static final class NativePlayer implements InvocationHandler {
        final List<Float> values=new ArrayList<>();boolean fail;
        @Override public Object invoke(Object target,Method method,Object[] args){
            if(method.getName().equals("setVolume")){
                if(fail)throw new IllegalStateException("native send failed");values.add((Float)args[0]);return null;
            }
            if(method.getName().equals("asBinder"))return new android.os.Binder();
            return null;
        }
    }
    private static NativePlayer nativePlayer(AudioPlaybackConfiguration config)throws Exception{
        NativePlayer nativePlayer=new NativePlayer();
        Class<?> iplayer=Class.forName("android.media.IPlayer");
        Object proxy=Proxy.newProxyInstance(iplayer.getClassLoader(),new Class<?>[]{iplayer},nativePlayer);
        Class<?> shell=Class.forName("android.media.AudioPlaybackConfiguration$IPlayerShell");
        Constructor<?> ctor=shell.getDeclaredConstructor(AudioPlaybackConfiguration.class,iplayer);ctor.setAccessible(true);
        Field field=AudioPlaybackConfiguration.class.getDeclaredField("mIPlayerShell");field.setAccessible(true);field.set(config,ctor.newInstance(config,proxy));
        return nativePlayer;
    }

    @Test public void observedNativeBaselineComposesWithoutChangingAppGain()throws Throwable{
        WindowAudioGain.Player player=new WindowAudioGain.Player(UID);
        List<Float> nativeGain=new ArrayList<>();
        float appGain=.37f;
        player.mute(true,v->{nativeGain.add(v);return null;});
        player.mute(false,v->{nativeGain.add(v);return null;});
        assertEquals(List.of(0f,1f),nativeGain);
        assertEquals(.37f,appGain,0f);
    }
    @Test public void nativeOwnerLatestValueIsPreservedWhileModuleKeepsZero()throws Throwable{
        WindowAudioGain.Player player=new WindowAudioGain.Player(UID);
        List<Float> nativeGain=new ArrayList<>();
        WindowAudioGain.VolumeCall call=v->{nativeGain.add(v);return "original";};
        player.mute(true,call);
        assertEquals("original",player.external(.24f,call));
        player.mute(false,call);
        assertEquals(List.of(0f,0f,.24f),nativeGain);
    }
    @Test public void nativeCallMuteAndUnmuteBeforeDuringAfterModuleMuteDoNotFight()throws Throwable{
        WindowAudioGain.Player player=new WindowAudioGain.Player(UID);
        List<Float> values=new ArrayList<>();
        WindowAudioGain.VolumeCall call=v->{values.add(v);return null;};
        player.external(0f,call);player.mute(true,call);player.mute(false,call);
        assertEquals(List.of(0f,0f,0f),values);
        player.mute(true,call);player.external(1f,call);player.mute(false,call);
        assertEquals(List.of(0f,0f,0f,0f,0f,1f),values);
    }
    @Test public void failedOriginalDoesNotCommitAndRestoreFailureRetainsPendingGain()throws Throwable{
        WindowAudioGain.Player player=new WindowAudioGain.Player(UID);
        RuntimeException failure=new RuntimeException("binder");
        WindowAudioGain.VolumeCall bad=v->{throw failure;};
        try{player.external(.3f,bad);fail();}catch(RuntimeException e){assertSame(failure,e);}
        List<Float> values=new ArrayList<>();
        WindowAudioGain.VolumeCall call=v->{values.add(v);return null;};
        player.mute(true,call);
        try{player.external(.5f,bad);fail();}catch(RuntimeException e){assertSame(failure,e);}
        try{player.mute(false,bad);fail();}catch(RuntimeException e){assertSame(failure,e);}
        player.mute(false,call);player.mute(false,call);
        assertEquals(List.of(0f,1f),values);
    }
    @Test public void releasedPlayerIsNotWrittenAndOtherUidRemainsIndependent()throws Throwable{
        WindowAudioGain.Player first=new WindowAudioGain.Player(UID),other=new WindowAudioGain.Player(OTHER_UID);
        List<Float> values=new ArrayList<>();
        WindowAudioGain.VolumeCall call=v->{values.add(v);return null;};
        first.mute(true,call);other.external(.6f,call);first.release();first.mute(false,call);other.mute(true,call);other.mute(false,call);
        assertEquals(List.of(0f,.6f,0f,.6f),values);
    }
    @Test public void nativeOwnerAndWorkerGainWritesAreSerializedPerPlayer()throws Throwable{
        WindowAudioGain.Player player=new WindowAudioGain.Player(UID);
        List<Float> values=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread nativeOwner=new Thread(()->{try{player.external(.4f,v->{entered.countDown();assertTrue(finish.await(2,TimeUnit.SECONDS));values.add(v);return null;});}catch(Throwable e){failure.set(e);}});
        nativeOwner.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        Thread worker=new Thread(()->{try{player.mute(true,v->{values.add(v);return null;});}catch(Throwable e){failure.set(e);}});
        worker.start();finish.countDown();nativeOwner.join(2000);worker.join(2000);
        assertFalse(nativeOwner.isAlive());assertFalse(worker.isAlive());assertNull(failure.get());
        player.mute(false,v->{values.add(v);return null;});assertEquals(List.of(.4f,0f,.4f),values);
    }

    @SuppressWarnings("unchecked") private static Map<Integer,WindowAudioGain.Player> players(WindowAudioGain gain)throws Exception{
        Field field=WindowAudioGain.class.getDeclaredField("players");field.setAccessible(true);return (Map<Integer,WindowAudioGain.Player>)field.get(gain);
    }
    private static void installed(WindowAudioGain gain)throws Exception{
        Field field=WindowAudioGain.class.getDeclaredField("installed");field.setAccessible(true);field.set(null,gain);
    }
    private static WindowAudioGain gain()throws Exception{
        // Robolectric's API35 omits API37's AAUDIO constant. Keep the production ABI gate strict;
        // only allocate this fixture without its constructor, still resolving all real proxy methods.
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);
        WindowAudioGain gain=(WindowAudioGain)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,WindowAudioGain.class);
        Map<String,Object> members=new HashMap<>();
        members.put("players",new java.util.concurrent.ConcurrentHashMap<Integer,WindowAudioGain.Player>());
        members.put("ownCall",ThreadLocal.withInitial(()->false));members.put("aaudioType",13);
        for(var entry:Map.of("uid","getClientUid","id","getPlayerInterfaceId","type","getPlayerType","proxy","getPlayerProxy").entrySet()){
            Method method=AudioPlaybackConfiguration.class.getDeclaredMethod(entry.getValue());method.setAccessible(true);members.put(entry.getKey(),method);
        }
        Method setVolume=((Method)members.get("proxy")).getReturnType().getDeclaredMethod("setVolume",float.class);
        setVolume.setAccessible(true);members.put("setVolume",setVolume);
        for(var entry:members.entrySet()){
            Field field=WindowAudioGain.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(gain,entry.getValue());
        }
        return gain;
    }
    private static AudioPlaybackConfiguration config(int id,int uid)throws Exception{
        Constructor<AudioPlaybackConfiguration> ctor=AudioPlaybackConfiguration.class.getDeclaredConstructor(int.class);ctor.setAccessible(true);
        AudioPlaybackConfiguration config=ctor.newInstance(id);
        for(String name:List.of("mClientUid","mPlayerType")){
            Field field=AudioPlaybackConfiguration.class.getDeclaredField(name);field.setAccessible(true);field.set(config,name.equals("mClientUid")?uid:13);
        }
        return config;
    }
    @After public void clearInstalled()throws Exception{installed(null);}

    @Test public void unknownAAudioIsRejectedRatherThanReportedAsShapedSuccess()throws Exception{
        WindowAudioGain gain=gain();installed(gain);
        AudioPlaybackConfiguration config=config(100,UID);
        assertFalse(gain.known(config));
        WindowAudio audio=WindowAudio.create(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),s->{});
        assertNotNull(audio);
        AudioPlaybackConfiguration known=config(99,UID);NativePlayer nativePlayer=nativePlayer(known);
        players(gain).put(99,new WindowAudioGain.Player(UID));
        PlaybackSnapshot.configs=List.of(known,config);
        assertFalse(audio.setMuted(UID,true));
        assertFalse(audio.isMuted(UID));
        assertEquals(List.of(0f,1f),nativePlayer.values);
    }
    @Test public void bornPlayerTrackingIsBoundToUidAndRestoreFailureStaysPending()throws Exception{
        WindowAudioGain gain=gain();installed(gain);AudioPlaybackConfiguration config=config(101,UID);
        players(gain).put(101,new WindowAudioGain.Player(UID));
        assertTrue(gain.known(config));assertFalse(gain.known(config(101,OTHER_UID)));
        NativePlayer nativePlayer=nativePlayer(config);
        WindowAudio audio=WindowAudio.create(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),s->{});
        PlaybackSnapshot.configs=List.of(config);assertTrue(audio.setMuted(UID,true));
        nativePlayer.fail=true;assertFalse(audio.setMuted(UID,false));assertTrue(audio.isMuted(UID));
        Field gained=WindowAudio.class.getDeclaredField("gained");gained.setAccessible(true);
        assertEquals(1,((Map<?,?>)gained.get(audio)).size());
        nativePlayer.fail=false;assertTrue(audio.setMuted(UID,false));
        assertFalse(audio.isMuted(UID));assertTrue(((Map<?,?>)gained.get(audio)).isEmpty());
        assertEquals(List.of(0f,1f),nativePlayer.values);
    }
    @Test public void restoreRetriesOnWorkerWithoutAnyPlaybackEvent()throws Exception{
        WindowAudioGain gain=gain();installed(gain);AudioPlaybackConfiguration config=config(301,UID);
        players(gain).put(301,new WindowAudioGain.Player(UID));NativePlayer nativePlayer=nativePlayer(config);
        WindowAudio audio=WindowAudio.create(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),s->{});
        PlaybackSnapshot.configs=List.of(config);assertTrue(audio.setMuted(UID,true));
        nativePlayer.fail=true;assertFalse(audio.setMuted(UID,false));assertTrue(audio.isMuted(UID));
        nativePlayer.fail=false;
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertFalse(audio.isMuted(UID));assertEquals(List.of(0f,1f),nativePlayer.values);
    }
    @Test public void bornPlayerCallbackMutesNewAAudioAndLeavesOtherUidAlone()throws Exception{
        WindowAudioGain gain=gain();installed(gain);
        WindowAudio audio=WindowAudio.create(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),s->{});
        assertTrue(audio.setMuted(UID,true));
        AudioPlaybackConfiguration target=config(201,UID),other=config(202,OTHER_UID);
        NativePlayer targetNative=nativePlayer(target),otherNative=nativePlayer(other);
        players(gain).put(201,new WindowAudioGain.Player(UID));players(gain).put(202,new WindowAudioGain.Player(OTHER_UID));
        PlaybackSnapshot.configs=List.of(target,other);gain.onBorn.run();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(0f),targetNative.values);assertTrue(otherNative.values.isEmpty());
        assertTrue(audio.setMuted(UID,false));assertEquals(List.of(0f,1f),targetNative.values);
    }
    @Test public void noPlayersCanBeCancelledWithoutChangingGlobalVolume()throws Exception{
        AudioManager manager=RuntimeEnvironment.getApplication().getSystemService(AudioManager.class);
        int volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        WindowAudio audio=WindowAudio.create(RuntimeEnvironment.getApplication(),new Handler(Looper.getMainLooper()),s->{});
        assertNotNull(audio);assertTrue(audio.setMuted(UID,true));assertTrue(audio.isMuted(UID));
        assertTrue(audio.setMuted(UID,false));assertFalse(audio.isMuted(UID));
        assertEquals(volume,manager.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertFalse(audio.setMuted(1000,true));
    }
}
