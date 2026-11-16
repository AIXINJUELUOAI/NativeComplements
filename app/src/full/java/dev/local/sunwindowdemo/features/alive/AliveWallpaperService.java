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

    private final class PhotoEngine extends Engine {
        private AliveRenderer renderer;
        private boolean ambient,visible,registered,destroyed,displayRegistered,displayRemoved;
        private DisplayManager displays;
        private int displayId=Display.INVALID_DISPLAY;
        private final AlivePhase.SleepWindow sleep=new AlivePhase.SleepWindow();
        private boolean sampled,wasInteractive,wasAmbient;
        private int lastAwakePhase=-1;
        private long sleepGeneration;
        private String lastState;
        private long ambientDuration=-1;
        private final Runnable sleepExpired=()->update("sleep-expired",false);
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
                update("broadcast:"+action,true);
                if(!Intent.ACTION_SCREEN_OFF.equals(action))recheckUnlock();
            }
        };
        private final SharedPreferences.OnSharedPreferenceChangeListener changed=(preferences,key)->{
            if(renderer!=null&&("applied_revision".equals(key)||"resources_revision".equals(key)
                    ||isPreview()&&("aod".equals(key)||"lock".equals(key)||"home".equals(key)||"photo".equals(key))))renderer.reload();
        };
        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            renderer=new AliveRenderer(AliveWallpaperService.this,isPreview());
            getSharedPreferences(AliveSettings.APPLIED,MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);
            if(isPreview())getSharedPreferences(AliveSettings.DRAFT,MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);
            IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screens,filter,null,main,Context.RECEIVER_NOT_EXPORTED);registered=true;
            Display own=getDisplayContext().getDisplay();
            if(own!=null)displayId=own.getDisplayId();
            displays=getSystemService(DisplayManager.class);
            if(displays!=null){displays.registerDisplayListener(displayListener,main);displayRegistered=true;}
            update();
        }
        private void beginSleep(){
            main.removeCallbacks(refresh);
            if(!supportsOwnAmbient()||displayState()!=AlivePhase.DisplayState.ON)return;
            long now=SystemClock.uptimeMillis();sleep.begin(now);
            main.removeCallbacks(sleepExpired);
            if(sleep.active(now))main.postDelayed(sleepExpired,sleep.remaining(now));
        }
        private void wake(){sleepGeneration++;sleep.wake();wasInteractive=false;main.removeCallbacks(sleepExpired);}
        private void endSleep(){sleepGeneration++;sleep.end();main.removeCallbacks(sleepExpired);}
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
            sleep.visibility(visible,now);
            boolean sleeping=sleep.active(now);
            boolean holding=supportsOwnAmbient()&&!interactive&&ambient&&state==AlivePhase.DisplayState.ON&&sleep.holding(now);
            int phase=sleeping||holding?AlivePhase.AMBIENT:AlivePhase.phase(interactive&&state==AlivePhase.DisplayState.ON,ambient,locked);
            if(!sleeping&&interactive&&!ambient&&state==AlivePhase.DisplayState.ON)lastAwakePhase=phase;
            boolean draw=AlivePhase.drawable(supportsOwnAmbient(),visible,interactive,ambient,state,sleeping||holding);
            boolean transition=animate&&state!=AlivePhase.DisplayState.DOZE_SUSPEND;
            // Hidden time never consumes the collapse: retain only an already-created scene.
            boolean start=sleeping&&draw&&sleep.present(now);
            if(start){
                long generation=sleepGeneration;
                renderer.sleep(lastAwakePhase,transition,()->main.post(()->{
                    if(destroyed||generation!=sleepGeneration)return;
                    long presentedAt=SystemClock.uptimeMillis();
                    if(sleep.start(presentedAt)){
                        sleep.visibility(visible,presentedAt);
                        main.removeCallbacks(sleepExpired);main.postDelayed(sleepExpired,sleep.remaining(presentedAt));
                    }
                }));
            }else if(!sleeping||sleep.presented()){
                if(!sleeping&&!interactive)transition=false;
                renderer.mode(phase,transition);
            }
            if(holding&&!sleeping){
                // Keep the final buffer through native ON→DOZE; never initialize or animate just for this bridge.
                renderer.hold(visible);
            }else renderer.visible(draw,sleeping||holding);
            if(sleeping||holding){main.removeCallbacks(sleepExpired);main.postDelayed(sleepExpired,
                sleeping?sleep.remaining(now):sleep.holdRemaining(now));}
            String facts="display="+displayId+":"+state+" phase="+phase+" draw="+draw+" visible="+visible
                +" interactive="+interactive+" locked="+locked+" ambient="+ambient+" duration="+ambientDuration+" sleeping="+sleeping+" holding="+holding;
            if(!facts.equals(lastState)||!"resample".equals(event)){
                android.util.Log.i("NativeComplements","ALIVE_ENGINE event="+event+" "+facts+" remaining="+sleep.remaining(now));
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
            update();renderer.attach(holder.getSurface(),width,height);
        }
        @Override public void onVisibilityChanged(boolean next){visible=next;update("visibility",true);recheckUnlock();}
        // Only the separate ambient component opts into the module-authorized native ambient path.
        public void onAmbientModeChanged(boolean next,long duration){
            ambient=next;ambientDuration=duration;
            if(next){
                if(duration<=0)endSleep();else beginSleep();
                update("ambient",duration>0);
            }else{
                // Zero is the platform ambient-call duration, not an instruction to skip wake geometry.
                wake();update("ambient-exit",true);recheckUnlock();
            }
        }
        public boolean shouldZoomOutWallpaper(){return false;}
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
        @Override public void onSurfaceDestroyed(SurfaceHolder holder){renderer.detach();super.onSurfaceDestroyed(holder);}
        @Override public void onDestroy(){
            destroyed=true;main.removeCallbacks(refresh);main.removeCallbacks(sleepExpired);
            if(registered){unregisterReceiver(screens);registered=false;}
            if(displayRegistered){displays.unregisterDisplayListener(displayListener);displayRegistered=false;}
            getSharedPreferences(AliveSettings.APPLIED,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);
            if(isPreview())getSharedPreferences(AliveSettings.DRAFT,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);
            if(renderer!=null){renderer.close();renderer=null;}
            super.onDestroy();
        }
    }
}
