package dev.local.sunwindowdemo;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.os.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface;

/** Per-UID mute: separate native shaper for AudioTrack, composed native multiplier for AAudio.
 * App/stream volume, native duck/fade shapers and the user's volume setting stay intact.
 * All player IPC runs on the module worker, outside the WM lock.
 */
final class WindowAudio {
    // Dedicated positive shaper id; separate from Android's small system shaper ids.
    private static final int SHAPER_ID=0x5357;
    private final AudioManager audio;
    private final Handler handler;
    private final Consumer<String> log;
    private final Method uid,id,proxy,apply,type;
    private final WindowAudioGain gain;
    private final VolumeShaper.Configuration mute,reference;
    private final VolumeShaper.Operation terminate;
    private final Set<Integer> muted=ConcurrentHashMap.newKeySet();
    private final Map<Integer,AudioPlaybackConfiguration> shaped=new HashMap<>();
    private final Map<Integer,AudioPlaybackConfiguration> gained=new HashMap<>();
    private final Set<Integer> restorePending=ConcurrentHashMap.newKeySet();
    private boolean registered;
    private final Runnable retryRestore=this::retryRestore;
    private void retryRestore(){
        try{updated(audio.getActivePlaybackConfigurations());}
        catch(RuntimeException e){log.accept("WINDOW_AUDIO_RETRY_FAILED "+FrameworkAccess.reason(e));scheduleRestore();}
    }
    private void scheduleRestore(){
        handler.removeCallbacks(retryRestore);
        if(!restorePending.isEmpty())handler.postDelayed(retryRestore,1000);
    }
    private final AudioManager.AudioPlaybackCallback callback=new AudioManager.AudioPlaybackCallback(){
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){updated(configs);}
    };
    // This helper executes only in hooked system_server, which may use these
    // archived API37 volume-shaper members; the app process never invokes them.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    private WindowAudio(Context context,Handler worker,Consumer<String> logger)throws Exception{
        audio=context.getSystemService(AudioManager.class);handler=worker;log=logger;gain=WindowAudioGain.installed();
        if(audio==null)throw new IllegalStateException("AudioManager missing");
        uid=AudioPlaybackConfiguration.class.getDeclaredMethod("getClientUid");
        id=AudioPlaybackConfiguration.class.getDeclaredMethod("getPlayerInterfaceId");
        proxy=AudioPlaybackConfiguration.class.getDeclaredMethod("getPlayerProxy");
        type=AudioPlaybackConfiguration.class.getDeclaredMethod("getPlayerType");
        for(Method m:new Method[]{uid,id,proxy,type})m.setAccessible(true);
        apply=proxy.getReturnType().getDeclaredMethod("applyVolumeShaper",VolumeShaper.Configuration.class,VolumeShaper.Operation.class);
        apply.setAccessible(true);
        VolumeShaper.Configuration.Builder builder=new VolumeShaper.Configuration.Builder()
            .setDuration(1).setCurve(new float[]{0,1},new float[]{0,0});
        Method setId=builder.getClass().getDeclaredMethod("setId",int.class);setId.setAccessible(true);setId.invoke(builder,SHAPER_ID);
        mute=builder.build();
        Constructor<VolumeShaper.Configuration> constructor=VolumeShaper.Configuration.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);reference=constructor.newInstance(SHAPER_ID);
        Class<?> operationBuilder=Class.forName("android.media.VolumeShaper$Operation$Builder");
        Constructor<?> createOperation=operationBuilder.getDeclaredConstructor();createOperation.setAccessible(true);
        Object operation=createOperation.newInstance();
        Method end=operationBuilder.getDeclaredMethod("terminate"),build=operationBuilder.getDeclaredMethod("build");
        end.setAccessible(true);build.setAccessible(true);end.invoke(operation);
        terminate=(VolumeShaper.Operation)build.invoke(operation);
        if(gain!=null)gain.onBorn=()->handler.post(()->{
            if(!muted.isEmpty())try{updated(audio.getActivePlaybackConfigurations());}
            catch(RuntimeException e){log.accept("WINDOW_AUDIO_SYNC_FAILED "+FrameworkAccess.reason(e));}
        });
    }
    static List<XposedInterface.HookHandle> install(XposedInterface module,ClassLoader loader,Consumer<String> log){
        return WindowAudioGain.install(module,loader,log);
    }
    static WindowAudio create(Context context,Handler handler,Consumer<String> log){
        try{WindowAudio result=new WindowAudio(context,handler,log);log.accept("WINDOW_AUDIO_READY native per-UID volume shaper");return result;}
        catch(Exception|LinkageError e){log.accept("WINDOW_AUDIO_UNAVAILABLE "+FrameworkAccess.reason(e));return null;}
    }
    boolean isMuted(int clientUid){return muted.contains(clientUid)||restorePending.contains(clientUid);}
    boolean setMuted(int clientUid,boolean value){
        if(clientUid<android.os.Process.FIRST_APPLICATION_UID)return false;
        List<AudioPlaybackConfiguration> configs=null;
        try{
            configs=audio.getActivePlaybackConfigurations();
            if(value){
                if(!registered){audio.registerAudioPlaybackCallback(callback,handler);registered=true;}
                muted.add(clientUid);
            }else muted.remove(clientUid);
            Set<Integer> failed=sync(configs);
            if(!failed.isEmpty()){
                muted.removeAll(failed);sync(configs);
                if(failed.contains(clientUid)){
                    log.accept("WINDOW_AUDIO_FAILED uid="+clientUid+" player mute/restore incomplete");return false;
                }
            }
            log.accept("WINDOW_AUDIO uid="+clientUid+" muted="+value);return true;
        }catch(RuntimeException e){
            muted.remove(clientUid);sync(configs);
            log.accept("WINDOW_AUDIO_FAILED "+FrameworkAccess.reason(e));return false;
        }
    }
    private void updated(List<AudioPlaybackConfiguration> configs){
        Set<Integer> failed=sync(configs);
        if(!failed.isEmpty()){
            muted.removeAll(failed);sync(configs);
            log.accept("WINDOW_AUDIO_FAILED player update rolled back uids="+failed);
        }
    }
    private void shape(AudioPlaybackConfiguration config,boolean value)throws Exception{
        Object player=proxy.invoke(config);
        if(player==null)throw new IllegalStateException("AudioTrack player released");
        apply.invoke(player,value?mute:reference,value?VolumeShaper.Operation.PLAY:terminate);
    }
    /** A null snapshot is unknown, not evidence that all players have been released. */
    private Set<Integer> sync(List<AudioPlaybackConfiguration> configs){
        Set<Integer> failed=new HashSet<>();
        Map<Integer,AudioPlaybackConfiguration> live=new HashMap<>();
        try{
            if(configs!=null)for(AudioPlaybackConfiguration config:configs){
                int player=(Integer)id.invoke(config),client=(Integer)uid.invoke(config);live.put(player,config);
                if(!muted.contains(client))continue;
                boolean aaudio=(Integer)type.invoke(config)==13;
                Map<Integer,AudioPlaybackConfiguration> applied=aaudio?gained:shaped;
                if(applied.containsKey(player))continue;
                try{
                    if(aaudio){
                        if(gain==null||!gain.known(config))throw new IllegalStateException("AAudio gain baseline unavailable");
                        gain.mute(config,true);
                    }else shape(config,true);
                    applied.put(player,config);
                }catch(Throwable e){failed.add(client);log.accept("WINDOW_AUDIO_PLAYER_FAILED player="+player+" "+FrameworkAccess.reason(e));}
            }
            restorePending.clear();
            for(Map<Integer,AudioPlaybackConfiguration> applied:List.of(shaped,gained)){
                for(Iterator<Map.Entry<Integer,AudioPlaybackConfiguration>> it=applied.entrySet().iterator();it.hasNext();){
                    Map.Entry<Integer,AudioPlaybackConfiguration> entry=it.next();
                    int client=(Integer)uid.invoke(entry.getValue());
                    boolean present=configs==null||live.containsKey(entry.getKey());
                    if(present&&muted.contains(client))continue;
                    try{
                        if(applied==gained){
                            if(gain.known(entry.getValue()))gain.mute(entry.getValue(),false);
                        }else shape(entry.getValue(),false);
                        it.remove();
                    }catch(Throwable e){
                        if(!present)it.remove();
                        else{restorePending.add(client);failed.add(client);}
                        log.accept("WINDOW_AUDIO_RESTORE_FAILED player="+entry.getKey()+" "+FrameworkAccess.reason(e));
                    }
                }
            }
        }catch(Exception e){failed.addAll(muted);log.accept("WINDOW_AUDIO_SYNC_FAILED "+FrameworkAccess.reason(e));}
        if(muted.isEmpty()&&shaped.isEmpty()&&gained.isEmpty()&&registered){
            try{audio.unregisterAudioPlaybackCallback(callback);registered=false;}
            catch(RuntimeException e){log.accept("WINDOW_AUDIO_UNREGISTER_FAILED "+FrameworkAccess.reason(e));}
        }
        scheduleRestore();
        return failed;
    }
}
