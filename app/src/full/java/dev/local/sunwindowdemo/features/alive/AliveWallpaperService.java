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
        private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
            @Override public void onDisplayAdded(int id){if(id==displayId)update();}
            @Override public void onDisplayChanged(int id){if(id==displayId)update();}
            @Override public void onDisplayRemoved(int id){if(id==displayId){displayRemoved=true;update();}}
        };
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Runnable refresh=this::update;
        private final BroadcastReceiver screens=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){update();}
        };
        private final SharedPreferences.OnSharedPreferenceChangeListener changed=(preferences,key)->{
            if(renderer!=null&&("style".equals(key)||"photo_revision".equals(key)||"resources_revision".equals(key)))renderer.reload();
        };
        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            renderer=new AliveRenderer(AliveWallpaperService.this);
            getSharedPreferences("alive_wallpaper",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);
            IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screens,filter,null,main,Context.RECEIVER_NOT_EXPORTED);registered=true;
            Display own=getDisplayContext().getDisplay();
            if(own!=null)displayId=own.getDisplayId();
            displays=getSystemService(DisplayManager.class);
            if(displays!=null){displays.registerDisplayListener(displayListener,main);displayRegistered=true;}
            update();
        }
        private void update(){update(true);}
        private void update(boolean animate){
            if(destroyed||renderer==null)return;
            boolean interactive=getSystemService(PowerManager.class).isInteractive();
            boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
            AlivePhase.DisplayState state=displayState();
            // Preview is a real system-hosted preview, not an artificial HOME/unlocked override.
            renderer.mode(AlivePhase.phase(interactive&&state==AlivePhase.DisplayState.ON,ambient,locked),
                animate&&state!=AlivePhase.DisplayState.DOZE_SUSPEND);
            renderer.visible(AlivePhase.drawable(supportsOwnAmbient(),visible,interactive,ambient,state));
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
        @Override public void onVisibilityChanged(boolean next){visible=next;update();}
        // Only the separate ambient component opts into the module-authorized native ambient path.
        public void onAmbientModeChanged(boolean next,long duration){
            ambient=next;update(duration>0);
        }
        public boolean shouldZoomOutWallpaper(){return false;}
        @Override public Bundle onCommand(String action,int x,int y,int z,Bundle extras,boolean resultRequested){
            // Commands are triggers, never a substitute for the system's accepted keyguard state.
            if("android.wallpaper.wakingup".equals(action)||"android.wallpaper.goingtosleep".equals(action)
                    ||"android.wallpaper.keyguardgoingaway".equals(action)||"android.wallpaper.keyguardappearing".equals(action)){
                update();main.removeCallbacks(refresh);main.postDelayed(refresh,100);
            }
            return super.onCommand(action,x,y,z,extras,resultRequested);
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder){renderer.detach();super.onSurfaceDestroyed(holder);}
        @Override public void onDestroy(){
            destroyed=true;main.removeCallbacks(refresh);
            if(registered){unregisterReceiver(screens);registered=false;}
            if(displayRegistered){displays.unregisterDisplayListener(displayListener);displayRegistered=false;}
            getSharedPreferences("alive_wallpaper",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);
            if(renderer!=null){renderer.close();renderer=null;}
            super.onDestroy();
        }
    }
}
