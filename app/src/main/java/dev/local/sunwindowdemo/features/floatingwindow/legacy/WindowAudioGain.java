package dev.local.sunwindowdemo;

import android.media.AudioPlaybackConfiguration;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** AAudio's IPlayer gain is separate from its app/stream gain, but shared with native call mute.
 * Observe that slot from player birth; never assume an already-existing player's gain was one.
 */
final class WindowAudioGain {
    @FunctionalInterface interface VolumeCall { Object run(float volume) throws Throwable; }

    static final class Player {
        final int uid;
        private float base=1f;
        private boolean muted,released;
        Player(int uid){this.uid=uid;}
        synchronized Object external(float volume,VolumeCall call)throws Throwable{
            Object result=call.run(muted?0f:volume);
            base=volume; // Only commit after the original call succeeds, including when composed to zero.
            return result;
        }
        synchronized void mute(boolean value,VolumeCall call)throws Throwable{
            if(released)return;
            if(value==muted)return;
            call.run(value?0f:base);
            muted=value;
        }
        synchronized void release(){released=true;}
    }

    volatile Runnable onBorn;
    private static volatile WindowAudioGain installed;
    private final Map<Integer,Player> players=new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> ownCall=ThreadLocal.withInitial(()->false);
    private final Method uid,id,type,proxy,setVolume;
    private final int aaudioType;

    private WindowAudioGain()throws Exception{
        uid=member(AudioPlaybackConfiguration.class,"getClientUid");
        id=member(AudioPlaybackConfiguration.class,"getPlayerInterfaceId");
        type=member(AudioPlaybackConfiguration.class,"getPlayerType");
        proxy=member(AudioPlaybackConfiguration.class,"getPlayerProxy");
        setVolume=member(proxy.getReturnType(),"setVolume",float.class);
        Field aaudio=AudioPlaybackConfiguration.class.getDeclaredField("PLAYER_TYPE_AAUDIO");
        if(aaudio.getType()!=int.class||!Modifier.isStatic(aaudio.getModifiers())||!Modifier.isFinal(aaudio.getModifiers()))throw new NoSuchFieldException("PLAYER_TYPE_AAUDIO");
        aaudioType=aaudio.getInt(null);
        if(aaudioType!=13)throw new NoSuchFieldException("PLAYER_TYPE_AAUDIO value");
        for(Method method:new Method[]{uid,id,type})check(method,int.class);
        check(proxy,Class.forName("android.media.PlayerProxy"));check(setVolume,void.class);
    }
    private static Method member(Class<?> owner,String name,Class<?>... args)throws Exception{
        Method method=owner.getDeclaredMethod(name,args);method.setAccessible(true);return method;
    }
    private static void check(Method method,Class<?> result)throws NoSuchMethodException{
        if(method.getReturnType()!=result||Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException(method.toString());
    }
    static WindowAudioGain installed(){return installed;}
    boolean isAAudio(AudioPlaybackConfiguration config)throws Exception{return (Integer)type.invoke(config)==aaudioType;}
    private Player player(AudioPlaybackConfiguration config)throws Exception{
        Player player=players.get((Integer)id.invoke(config));
        return player!=null&&player.uid==(Integer)uid.invoke(config)?player:null;
    }
    boolean known(AudioPlaybackConfiguration config)throws Exception{return player(config)!=null;}
    void mute(AudioPlaybackConfiguration config,boolean value)throws Throwable{
        Player player=player(config);
        if(player==null)throw new IllegalStateException("AAudio gain baseline not observed");
        Object target=proxy.invoke(config);
        if(target==null)throw new IllegalStateException("AAudio player released");
        player.mute(value,volume->{
            boolean previous=ownCall.get();ownCall.set(true);
            try{return setVolume.invoke(target,volume);}
            finally{if(previous)ownCall.set(true);else ownCall.remove();}
        });
    }

    /** Called synchronously at backend selection, before AudioService/player registration starts. */
    static List<XposedInterface.HookHandle> install(XposedInterface module,ClassLoader loader,Consumer<String> log){
        List<XposedInterface.HookHandle> handles=new ArrayList<>();
        try{
            WindowAudioGain gain=new WindowAudioGain();
            Class<?> card=Class.forName("android.media.PlayerBase$PlayerIdCard",false,loader);
            Constructor<?> born=AudioPlaybackConfiguration.class.getDeclaredConstructor(card,int.class,int.class,int.class);
            Class<?> monitor=Class.forName("com.android.server.audio.PlaybackActivityMonitor",false,loader);
            Method release=member(monitor,"releasePlayer",int.class,int.class);
            Field nativePlayers=monitor.getDeclaredField("mPlayers"),nativeLock=monitor.getDeclaredField("mPlayerLock");
            nativePlayers.setAccessible(true);nativeLock.setAccessible(true);
            Field conf=gain.proxy.getReturnType().getDeclaredField("mConf");conf.setAccessible(true);
            if(conf.getType()!=AudioPlaybackConfiguration.class||Modifier.isStatic(conf.getModifiers())||
                !Map.class.isAssignableFrom(nativePlayers.getType())||Modifier.isStatic(nativePlayers.getModifiers())||
                nativeLock.getType()!=Object.class||Modifier.isStatic(nativeLock.getModifiers()))throw new NoSuchFieldException("AAudio monitor fields");
            Method track=member(monitor,"trackPlayer",card);
            Method muteCall=member(monitor,"mutePlayersForCall",int[].class),unmuteCall=member(monitor,"unmutePlayersForCall");
            check(release,void.class);check(track,int.class);check(muteCall,void.class);check(unmuteCall,void.class);
            Method death=member(monitor,"playerDeath",int.class);
            Method serviceRelease=member(Class.forName("com.android.server.audio.AudioService",false,loader),"releasePlayer",int.class);
            check(death,void.class);check(serviceRelease,void.class);
            if(!module.deoptimize(track)||!module.deoptimize(muteCall)||!module.deoptimize(unmuteCall)||
                !module.deoptimize(death)||!module.deoptimize(serviceRelease))throw new IllegalStateException("AAudio gain caller deoptimize");
            handles.add(module.hook(born).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{
                    AudioPlaybackConfiguration config=(AudioPlaybackConfiguration)chain.getThisObject();
                    if(gain.isAAudio(config)){
                        gain.players.put((Integer)gain.id.invoke(config),new Player((Integer)gain.uid.invoke(config)));
                        Runnable notify=gain.onBorn;if(notify!=null)notify.run();
                    }
                }catch(Exception e){log.accept("WINDOW_AUDIO_GAIN_BIRTH_FAILED "+FrameworkAccess.reason(e));}
                return result;
            }));
            handles.add(module.hook(gain.setVolume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(gain.ownCall.get())return chain.proceed();
                Player player;
                try{player=gain.player((AudioPlaybackConfiguration)conf.get(chain.getThisObject()));}
                catch(Exception e){log.accept("WINDOW_AUDIO_GAIN_OBSERVE_FAILED "+FrameworkAccess.reason(e));return chain.proceed();}
                if(player==null)return chain.proceed();
                return player.external((Float)chain.getArg(0),volume->chain.proceed(new Object[]{volume}));
            }));
            handles.add(module.hook(release).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{
                    int piid=(Integer)chain.getArg(0);
                    Player removed=null;
                    synchronized(nativeLock.get(chain.getThisObject())){
                        // An unauthorized release returns normally, but leaves the native player alive.
                        if(!((Map<?,?>)nativePlayers.get(chain.getThisObject())).containsKey(piid))removed=gain.players.remove(piid);
                    }
                    if(removed!=null)removed.release();
                }catch(Exception e){log.accept("WINDOW_AUDIO_GAIN_RELEASE_FAILED "+FrameworkAccess.reason(e));}
                return result;
            }));
            installed=gain;
            log.accept("WINDOW_AUDIO_GAIN_READY observed AAudio native multiplier");
            return handles;
        }catch(Throwable failure){
            for(XposedInterface.HookHandle handle:handles)try{handle.unhook();}catch(Throwable ignored){}
            log.accept("WINDOW_AUDIO_GAIN_UNAVAILABLE "+FrameworkAccess.reason(failure));return List.of();
        }
    }
}
