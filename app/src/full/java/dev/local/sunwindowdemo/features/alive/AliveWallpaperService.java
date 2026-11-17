package dev.local.sunwindowdemo.features.alive;

import android.app.KeyguardManager;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.aliveclean.AliveRenderer;

/** Own wallpaper only: no authentication, input, other wallpaper or SystemUI modification. */
public class AliveWallpaperService extends WallpaperService {
    protected boolean supportsOwnAmbient(){return false;}
    @Override public Engine onCreateEngine(){return new PhotoEngine();}

    static void registerScreenEvents(Context context,BroadcastReceiver receiver,Handler handler){
        // Only platform-protected actions: USER_PRESENT can come from SystemUI's separate UID,
        // which NOT_EXPORTED rejects. Events trigger real KeyguardManager reads, never grant HOME.
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_USER_UNLOCKED);
        context.registerReceiver(receiver,filter,null,handler,Context.RECEIVER_EXPORTED);
    }

    private final class PhotoEngine extends Engine {
        private AliveRenderer renderer;
        private boolean ambient,visible,registered,destroyed,displayRegistered,displayRemoved,sleepHidden;
        private DisplayManager displays;
        private BroadcastReceiver handoffReceiver;
        private int displayId=Display.INVALID_DISPLAY;
        private final AlivePhase.SleepWindow sleep=new AlivePhase.SleepWindow();
        private final AlivePhase.WakeWindow wakeWindow=new AlivePhase.WakeWindow();
        private boolean sampled,wasInteractive,wasAmbient;
        private int lastAwakePhase=-1,lastRenderedPhase=-1;
        private long sleepGeneration;
        private AliveSleepHandoff.Request handoff;
        private boolean handoffAcknowledged,handoffDone,nativeWake;
        private long nativeWakeDeadline;
        private int systemAod=-1;
        private boolean aodRegistered;
        private final android.database.ContentObserver aodObserver=new android.database.ContentObserver(new Handler(Looper.getMainLooper())){
            @Override public void onChange(boolean selfChange){readAod();}
        };
        private void readAod(){
            int next=AliveSystemState.aod(AliveWallpaperService.this);
            if(next==systemAod)return;
            systemAod=next;nativeWake=false;endSleep();
            if(displayState()==AlivePhase.DisplayState.ON&&!ambient&&getSystemService(PowerManager.class).isInteractive())sleep.wake();
            update("aod-setting",false);
        }
        private boolean ownAmbient(){return supportsOwnAmbient()&&systemAod==1;}
        private final Runnable handoffExpired=()->cancelHandoff();
        private String lastState;
        private long ambientDuration=-1;
        private final Runnable sleepExpired=()->update("sleep-expired",false);
        private final Runnable wakeExpired=()->{
            clearWake();
            // Expiration while visible must not finish an in-flight expansion.
            long now=SystemClock.uptimeMillis();
            if(!destroyed&&renderer!=null&&!visible&&!sleep.active(now)&&!sleep.holding(now)){nativeWake=false;renderer.visible(false,false);}
        };
        private void clearWake(){wakeWindow.clear();main.removeCallbacks(wakeExpired);}
        private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
            @Override public void onDisplayAdded(int id){if(id==displayId)update("display-added",true);}
            @Override public void onDisplayChanged(int id){if(id==displayId)update("display-changed",true);}
            @Override public void onDisplayRemoved(int id){if(id==displayId){displayRemoved=true;update("display-removed",false);}}
        };
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Runnable refresh=()->update("unlock-resample",true);
        private void recheckUnlock(){
            main.removeCallbacks(refresh);
            if(!visible||destroyed||ambient||sleep.active(SystemClock.uptimeMillis())
                    ||displayState()!=AlivePhase.DisplayState.ON||!getSystemService(PowerManager.class).isInteractive())return;
            // ponytail: at most four real-state reads per native event, never a persistent poll or assumed HOME.
            for(long delay:new long[]{100,300,600,1000})main.postDelayed(refresh,delay);
        }
        private final BroadcastReceiver screens=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                String action=intent.getAction();
                if(Intent.ACTION_SCREEN_OFF.equals(action))beginSleep();
                else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_USER_PRESENT.equals(action))wake();
                if(Intent.ACTION_USER_PRESENT.equals(action))clearWake();
                if(Intent.ACTION_USER_UNLOCKED.equals(action)&&renderer!=null){invalidateScene();renderer.reload();}
                update("broadcast:"+action,true);
                if(!Intent.ACTION_SCREEN_OFF.equals(action))recheckUnlock();
            }
        };
        private final SharedPreferences.OnSharedPreferenceChangeListener changed=(preferences,key)->{
            if(renderer!=null&&("applied_revision".equals(key)||"resources_revision".equals(key)
                    ||isPreview()&&("aod".equals(key)||"lock".equals(key)||"home".equals(key)||"photo".equals(key)))){invalidateScene();renderer.reload();}
        };
        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            renderer=new AliveRenderer(AliveWallpaperService.this,isPreview());
            systemAod=AliveSystemState.aod(AliveWallpaperService.this);
            try{getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(AliveSystemState.AOD_SETTING),false,aodObserver);aodRegistered=true;}
            catch(RuntimeException unavailable){systemAod=-1;}
            AliveSettings.runtimePreferences(AliveWallpaperService.this).registerOnSharedPreferenceChangeListener(changed);
            if(isPreview())AliveSettings.credential(AliveWallpaperService.this).getSharedPreferences(AliveSettings.DRAFT,MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);
            registerScreenEvents(AliveWallpaperService.this,screens,main);registered=true;
            if(!isPreview())try{
                handoffReceiver=AliveHandoffTransport.install(AliveWallpaperService.this,
                    envelope->acceptHandoff(envelope.action(),envelope.extras()));
            }catch(RuntimeException unavailable){android.util.Log.w("NativeComplements","ALIVE_HANDOFF_RECEIVER_UNAVAILABLE",unavailable);}
            Display own=getDisplayContext().getDisplay();
            if(own!=null)displayId=own.getDisplayId();
            displays=getSystemService(DisplayManager.class);
            if(displays!=null){displays.registerDisplayListener(displayListener,main);displayRegistered=true;}
            update();
        }
        private void beginSleep(){
            main.removeCallbacks(refresh);
            if(!ownAmbient()||displayState()!=AlivePhase.DisplayState.ON)return;
            nativeWake=false;
            long now=SystemClock.uptimeMillis();
            if(sleep.begin(now)){
                nativeWakeDeadline=0;
                clearWake();wakeWindow.invalidate();
                if(handoff!=null&&handoff.waking)cancelHandoff();
                // From an app the wallpaper was not on screen: build the awake scene now and replace the
                // stale buffer, so the first exposed frame is the real start of the full collapse.
                sleepHidden=!visible;
                if(sleepHidden&&renderer!=null&&!isPreview()&&displayId==Display.DEFAULT_DISPLAY)
                    renderer.prewarm(lastAwakePhase==AlivePhase.LOCK?AlivePhase.LOCK:AlivePhase.HOME);
            }
            main.removeCallbacks(sleepExpired);
            if(sleep.active(now))main.postDelayed(sleepExpired,sleep.remaining(now));
        }
        private void cancelHandoff(){
            AliveSleepHandoff.Request old=handoff;handoff=null;handoffAcknowledged=handoffDone=false;
            main.removeCallbacks(handoffExpired);
            if(renderer!=null){renderer.onUnavailable(null);renderer.clearHandoff();}
            if(old!=null)old.cancel();
        }
        private boolean handoffDisplayReady(){
            AlivePhase.DisplayState state=displayState();
            return state==AlivePhase.DisplayState.ON
                    ||state==AlivePhase.DisplayState.DOZE&&ambient&&sleep.holding(SystemClock.uptimeMillis());
        }
        private boolean keepPreparedSleep(){
            return handoff!=null&&!handoff.expired()&&sleep.active(SystemClock.uptimeMillis())
                    &&!handoff.waking&&ownAmbient()&&!isPreview()&&!destroyed
                    &&displayId==Display.DEFAULT_DISPLAY&&displayState()==AlivePhase.DisplayState.ON
                    &&!getSystemService(PowerManager.class).isInteractive();
        }
        private boolean handoffReady(AliveSleepHandoff.Request request){
            return ownAmbient()&&!isPreview()&&!destroyed&&visible&&displayId==Display.DEFAULT_DISPLAY
                &&!request.expired()&&getSurfaceHolder().getSurface().isValid()
                &&AlivePhase.handoffState(request.waking,getSystemService(PowerManager.class).isInteractive(),displayState(),handoffDisplayReady());
        }
        private void finishHandoff(AliveSleepHandoff.Request request,long epoch){
            if(handoff!=request||epoch!=wakeWindow.epoch()||!handoffReady(request))return;
            handoffDone=true;if(handoffAcknowledged)request.finished();
        }
        private void acknowledgeHandoff(){
            AliveSleepHandoff.Request pending=handoff;
            if(pending==null||handoffAcknowledged)return;
            if(pending.expired()){cancelHandoff();return;}
            long epoch=wakeWindow.epoch();
            if(pending.waking){
                if(!nativeWake||SystemClock.uptimeMillis()>=nativeWakeDeadline){cancelHandoff();return;}
                if(!handoffReady(pending)||ambient)return;
                renderer.wake(awakeTarget(),()->main.post(()->{
                    if(handoff!=pending||epoch!=wakeWindow.epoch()||!handoffReady(pending))return;
                    handoffAcknowledged=true;pending.ready();if(handoffDone)pending.finished();
                }),()->main.post(()->finishHandoff(pending,epoch)));
                return;
            }
            if(!handoffReady(pending))return;
            renderer.presented(()->main.post(()->{
                if(handoff!=pending||epoch!=wakeWindow.epoch()||!handoffReady(pending))return;
                handoffAcknowledged=true;pending.ready();if(handoffDone)pending.finished();
            }),()->main.post(()->finishHandoff(pending,epoch)));
        }
        private int awakeTarget(){return getSystemService(KeyguardManager.class).isKeyguardLocked()?AlivePhase.LOCK:AlivePhase.HOME;}
        private void wake(){
            long now=SystemClock.uptimeMillis();
            if(wakeWindow.arm(now,renderer!=null&&renderer.error().isBlank()
                    &&supportsOwnAmbient()&&!isPreview()&&displayId==Display.DEFAULT_DISPLAY
                    &&displayState()==AlivePhase.DisplayState.ON&&getSurfaceHolder().getSurface().isValid()
                    &&getSystemService(KeyguardManager.class).isKeyguardLocked(),sleep))
                main.postDelayed(wakeExpired,wakeWindow.remaining(now));
            if(handoff!=null&&!handoff.waking)cancelHandoff();
            sleepGeneration++;sleep.wake();wasInteractive=false;main.removeCallbacks(sleepExpired);
            if(!nativeWake&&nativeWakeDeadline==0&&ownAmbient()&&displayId==Display.DEFAULT_DISPLAY&&!isPreview()
                    &&displayState()==AlivePhase.DisplayState.ON&&getSystemService(PowerManager.class).isInteractive()){
                nativeWake=true;nativeWakeDeadline=now+2500;
            }
        }
        private void endSleep(){invalidateScene();cancelHandoff();sleepGeneration++;sleep.end();main.removeCallbacks(sleepExpired);}
        private void invalidateScene(){clearWake();wakeWindow.invalidate();nativeWake=false;cancelHandoff();}
        private void update(){update("resample",true);}
        private void update(String event,boolean animate){
            if(destroyed||renderer==null)return;
            boolean interactive=getSystemService(PowerManager.class).isInteractive();
            boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
            AlivePhase.DisplayState state=displayState();
            if(sampled&&wasInteractive&&!interactive)beginSleep();
            if(interactive&&!ambient&&sampled&&(!wasInteractive||wasAmbient))wake();
            if(state==AlivePhase.DisplayState.OFF||state==AlivePhase.DisplayState.UNKNOWN
                    ||state==AlivePhase.DisplayState.DOZE_SUSPEND)endSleep();
            if(!visible||!interactive||ambient||state!=AlivePhase.DisplayState.ON)main.removeCallbacks(refresh);
            sampled=true;wasInteractive=interactive;wasAmbient=ambient;
            long now=SystemClock.uptimeMillis();
            if(!locked)clearWake();
            if(state!=AlivePhase.DisplayState.ON){clearWake();nativeWake=false;}
            if(nativeWake&&now>=nativeWakeDeadline)nativeWake=false;
            boolean wakePreserved=wakeWindow.active(now);
            sleep.visibility(visible,now);
            boolean sleeping=sleep.active(now);
            boolean holding=ownAmbient()&&!interactive&&ambient&&state==AlivePhase.DisplayState.ON&&sleep.holding(now);
            int phase=AlivePhase.phase(ownAmbient(),interactive&&state==AlivePhase.DisplayState.ON,ambient,locked,sleeping||holding);
            if(!sleeping&&interactive&&!ambient&&state==AlivePhase.DisplayState.ON)lastAwakePhase=phase;
            boolean draw=AlivePhase.drawable(ownAmbient(),visible,interactive,ambient,state,sleeping||holding);
            if(handoff!=null&&(handoff.expired()||!ownAmbient()||displayId!=Display.DEFAULT_DISPLAY
                    ||handoff.waking&&(!interactive||state!=AlivePhase.DisplayState.ON)))cancelHandoff();
            if(!draw&&handoffAcknowledged&&!briefHidden())cancelHandoff();
            boolean transition=animate&&state!=AlivePhase.DisplayState.DOZE_SUSPEND&&AlivePhase.animate(ownAmbient(),lastRenderedPhase,phase);
            lastRenderedPhase=phase;
            // Hidden time never consumes the collapse: retain only an already-created scene.
            boolean start=sleeping&&draw&&sleep.present(now);
            if(start){
                if(sleepHidden)transition=animate;
                long generation=sleepGeneration,renderEpoch=wakeWindow.epoch();
                AliveSleepHandoff.Request request=handoff;
                renderer.sleep(lastAwakePhase,transition,()->main.post(()->{
                    if(destroyed||generation!=sleepGeneration||!ownAmbient())return;
                    long presentedAt=SystemClock.uptimeMillis();
                    if(sleep.start(presentedAt)){
                        wakeWindow.presented(renderEpoch);
                        sleep.visibility(visible,presentedAt);
                        main.removeCallbacks(sleepExpired);main.postDelayed(sleepExpired,sleep.remaining(presentedAt));
                        acknowledgeHandoff();
                    }
                }),()->main.post(()->{if(request!=null)finishHandoff(request,renderEpoch);}));
            }else if(nativeWake&&ownAmbient()&&interactive&&state==AlivePhase.DisplayState.ON){
                // Target follows real authentication; only decorative geometry changes.
                if(draw&&!ambient)renderer.wake(locked?AlivePhase.LOCK:AlivePhase.HOME,null,null);
            }else if(wakePreserved){
                // Wake can precede ambient=false or a short visibility gap. Neither may force AMBIENT's endpoint.
                if(draw&&interactive&&!ambient&&phase!=AlivePhase.AMBIENT)renderer.mode(phase,true);
            }else if(holding&&!sleeping){
                // hold() below keeps the presented AMBIENT scene. A mode() jump here would drop the
                // pending endpoint callback, so SystemUI never learned the collapse had finished.
            }else if(!sleeping||sleep.presented()){
                // Ordinary visibility still receives only the current endpoint, never a deferred unlock.
                if(!sleeping&&(!interactive||!draw||"visibility".equals(event)))transition=false;
                renderer.mode(phase,transition);
            }
            if(holding&&!sleeping){
                // Keep the final buffer through native ON→DOZE; never initialize or animate just for this bridge.
                renderer.hold(visible);
            }else renderer.visible(draw,sleeping||holding||wakePreserved);
            acknowledgeHandoff();
            if(sleeping||holding){main.removeCallbacks(sleepExpired);main.postDelayed(sleepExpired,
                sleeping?sleep.remaining(now):sleep.holdRemaining(now));}
            String facts="display="+displayId+":"+state+" phase="+phase+" draw="+draw+" visible="+visible
                +" interactive="+interactive+" locked="+locked+" ambient="+ambient+" duration="+ambientDuration+" sleeping="+sleeping+" holding="+holding
                +" wake-preserved="+wakePreserved;
            if(!facts.equals(lastState)||!"resample".equals(event)){
                android.util.Log.i("NativeComplements","ALIVE_ENGINE event="+event+" "+facts+" remaining="+sleep.remaining(now)+" wake-remaining="+wakeWindow.remaining(now));
                lastState=facts;
            }
        }
        private AlivePhase.DisplayState displayState(){
            Display own=displays==null||displayRemoved?null:displays.getDisplay(displayId);
            if(own==null)return AlivePhase.DisplayState.UNKNOWN;
            return switch(own.getState()){
                case Display.STATE_ON -> AlivePhase.DisplayState.ON;
                case Display.STATE_DOZE -> AlivePhase.DisplayState.DOZE;
                case Display.STATE_DOZE_SUSPEND -> AlivePhase.DisplayState.DOZE_SUSPEND;
                case Display.STATE_OFF -> AlivePhase.DisplayState.OFF;
                default -> AlivePhase.DisplayState.UNKNOWN;
            };
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder,int format,int width,int height){
            super.onSurfaceChanged(holder,format,width,height);
            invalidateScene();update();renderer.attach(holder.getSurface(),width,height);acknowledgeHandoff();
        }
        @Override public void onVisibilityChanged(boolean next){
            long now=SystemClock.uptimeMillis();
            visible=next;main.removeCallbacks(hiddenSettled);
            if(!next){
                hiddenSince=now;
                // WM briefly hides the wallpaper while its target moves between launcher and keyguard.
                // Only a hide that outlasts the grace period cancels an acknowledged handoff.
                if(handoffAcknowledged)main.postDelayed(hiddenSettled,HIDDEN_GRACE_MS);
                if(!wakeWindow.active(now)&&(handoff==null||!handoff.waking))nativeWake=false;
            }else{
                // Hidden for real (an app was shown over the wake): returning later is an ordinary
                // visibility change, never a replay of the AMBIENT→awake wake animation.
                if(hiddenSince>=0&&now-hiddenSince>=HIDDEN_GRACE_MS){
                    nativeWake=false;clearWake();wakeWindow.invalidate();
                    if(handoff!=null&&handoff.waking)cancelHandoff();
                }
                hiddenSince=-1;
            }
            update("visibility",true);recheckUnlock();
        }
        private static final long HIDDEN_GRACE_MS=150;
        private long hiddenSince=-1;
        private final Runnable hiddenSettled=()->{if(!visible&&handoffAcknowledged)cancelHandoff();update("hidden-settled",true);};
        private boolean briefHidden(){return !visible&&hiddenSince>=0&&SystemClock.uptimeMillis()-hiddenSince<HIDDEN_GRACE_MS;}
        // Only the separate ambient component opts into the module-authorized native ambient path.
        public void onAmbientModeChanged(boolean next,long duration){
            ambient=next;ambientDuration=duration;
            if(next){
                // Native screen-off admission may legitimately deliver duration=0. Keep only
                // an already prepared ON wait; draw/ACK still require real visibility/lock/surface.
                // Never start/renew an episode or initialize a hidden scene here.
                boolean prepared=duration<=0&&keepPreparedSleep();
                if(duration<=0){if(!prepared)endSleep();}else beginSleep();
                update("ambient",duration>0||prepared);
            }else{
                // Zero is the platform ambient-call duration, not an instruction to skip wake geometry.
                wake();update("ambient-exit",true);recheckUnlock();
            }
        }
        public boolean shouldZoomOutWallpaper(){return false;}
        /** Called only by the sender-attributed SystemUI transport receiver. */
        private void acceptHandoff(String action,Bundle extras){
            if(destroyed||renderer==null)return;
            if(AliveSleepHandoff.ACTION_CANCEL.equals(action)){
                if(handoff!=null&&extras!=null&&handoff.token.equals(extras.getString("token")))cancelHandoff();
            }
            else if((AliveSleepHandoff.ACTION_PREPARE.equals(action)||AliveSleepHandoff.ACTION_PREPARE_WAKE.equals(action))&&ownAmbient()&&!isPreview()){
                AliveSleepHandoff.Request next=AliveSleepHandoff.parse(action,extras,displayId);
                if(next!=null&&(handoff==null||!handoff.token.equals(next.token))){
                    cancelHandoff();handoff=next;
                    renderer.onUnavailable(()->main.post(()->{if(handoff==next)cancelHandoff();}));
                    main.postDelayed(handoffExpired,Math.max(0,next.expires-SystemClock.uptimeMillis()));
                    if(next.waking){wake();update("system-wake",true);}
                    else if(!getSystemService(PowerManager.class).isInteractive()){
                        beginSleep();update("system-sleep",true);
                    }else cancelHandoff();
                }
            }
        }
        @Override public Bundle onCommand(String action,int x,int y,int z,Bundle extras,boolean resultRequested){
            // Commands are triggers, never a substitute for the system's accepted keyguard state.
            if("android.wallpaper.wakingup".equals(action)||"android.wallpaper.goingtosleep".equals(action)
                    ||"android.wallpaper.keyguardgoingaway".equals(action)||"android.wallpaper.keyguardappearing".equals(action)){
                if("android.wallpaper.goingtosleep".equals(action))beginSleep();
                else if("android.wallpaper.wakingup".equals(action))wake();
                update("command:"+action,true);
                if(!"android.wallpaper.goingtosleep".equals(action))recheckUnlock();
            }
            return super.onCommand(action,x,y,z,extras,resultRequested);
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder){invalidateScene();cancelHandoff();renderer.detach();super.onSurfaceDestroyed(holder);}
        @Override public void onDestroy(){
            destroyed=true;endSleep();main.removeCallbacks(refresh);
            if(registered){unregisterReceiver(screens);registered=false;}
            if(handoffReceiver!=null){unregisterReceiver(handoffReceiver);handoffReceiver=null;}
            if(displayRegistered){displays.unregisterDisplayListener(displayListener);displayRegistered=false;}
            if(aodRegistered){getContentResolver().unregisterContentObserver(aodObserver);aodRegistered=false;}
            AliveSettings.runtimePreferences(AliveWallpaperService.this).unregisterOnSharedPreferenceChangeListener(changed);
            if(isPreview())AliveSettings.credential(AliveWallpaperService.this).getSharedPreferences(AliveSettings.DRAFT,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);
            if(renderer!=null){renderer.close();renderer=null;}
            super.onDestroy();
        }
    }
}
