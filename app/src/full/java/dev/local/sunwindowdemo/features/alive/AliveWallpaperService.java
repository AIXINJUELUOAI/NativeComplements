package dev.local.sunwindowdemo.features.alive;

import android.app.KeyguardManager;
import android.content.*;
import android.os.*;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import org.aliveclean.AliveRenderer;

/** Own wallpaper only: no authentication, input, other wallpaper or SystemUI modification. */
public final class AliveWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine(){return new PhotoEngine();}

    private final class PhotoEngine extends Engine {
        private AliveRenderer renderer;
        private boolean ambient,visible,registered,destroyed;
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
            update();
        }
        private void update(){
            if(destroyed||renderer==null)return;
            boolean interactive=getSystemService(PowerManager.class).isInteractive();
            boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
            // Preview is a real system-hosted preview, not an artificial HOME/unlocked override.
            renderer.mode(AlivePhase.phase(interactive,ambient,locked),true);
            renderer.visible(visible&&interactive);
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder,int format,int width,int height){
            super.onSurfaceChanged(holder,format,width,height);
            renderer.attach(holder.getSurface(),width,height);update();
        }
        @Override public void onVisibilityChanged(boolean next){visible=next;update();}
        // Platform system callback; metadata deliberately does not request privileged ambient support.
        public void onAmbientModeChanged(boolean next,long duration){
            ambient=next;
            if(renderer!=null){
                boolean interactive=getSystemService(PowerManager.class).isInteractive();
                renderer.mode(AlivePhase.phase(interactive,ambient,getSystemService(KeyguardManager.class).isKeyguardLocked()),duration>0);
                renderer.visible(visible&&interactive);
            }
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
            if(registered)unregisterReceiver(screens);
            getSharedPreferences("alive_wallpaper",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);
            if(renderer!=null){renderer.close();renderer=null;}
            super.onDestroy();
        }
    }
}
