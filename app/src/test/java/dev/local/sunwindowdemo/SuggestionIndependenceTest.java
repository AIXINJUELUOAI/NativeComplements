package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class SuggestionIndependenceTest {
    public static class Activity { public String packageName="example.editor"; }
    public static class Task {
        public int mUserId;
        Display display;
        public Activity topRunningActivity(){return new Activity();}
        public Display getDisplayContent(){return display;}
        public int getWindowingMode(){return 1;}
        public int getActivityType(){return 1;}
        public boolean isLeafTask(){return true;}
        public boolean isVisibleRequested(){return true;}
    }
    public static class Window {
        public WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
        Display display;
        int uid=12345;
        boolean shown=true;
        Window(Display display){this.display=display;mAttrs.type=1;mAttrs.packageName="example.editor";}
        public boolean isVisible(){return shown;}
        public int getOwningUid(){return uid;}
        public Display getDisplayContent(){return display;}
    }
    public static class InsetsState {
        public Insets calculateInsets(Rect frame,Rect bounds,int type,boolean ignore){return Insets.of(0,0,0,300);}
    }
    public static class InsetsController { public InsetsState getRawInsetsState(){return new InsetsState();} }
    public static class Display {
        public int mDisplayId;
        public Window mCurrentFocus=new Window(this);
        Window ime=new Window(this);
        Task task=new Task();
        Display(){task.display=this;}
        public Window getImeWindow(){return ime;}
        public Rect getBounds(){return new Rect(0,0,1080,2400);}
        public InsetsController getInsetsStateController(){return new InsetsController();}
        public void forAllTasks(Consumer<Object> callback){callback.accept(task);}
    }
    public static class Root {
        Display display=new Display();
        public Display getDefaultDisplay(){return display;}
        public Task getTopDisplayFocusedLeafTask(){return display.task;}
    }
    public static class Wms {
        public Context mContext=RuntimeEnvironment.getApplication();
        public Root mRoot=new Root();
        public Object mGlobalLock=new Object();
        public int mCurrentUserId;
        public List<Window> mHidingNonSystemOverlayWindows=new ArrayList<>();
        public boolean updateFocusedWindowLocked(int mode,boolean surfaces){return true;}
        public void updateNonSystemOverlayWindowsVisibilityIfNeeded(Window window,boolean shown){}
    }
    private static void systemUi(Context context){
        android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();info.packageName=BackBridge.PACKAGE;
        info.applicationInfo=new android.content.pm.ApplicationInfo();info.applicationInfo.uid=10002;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        android.content.pm.PackageInfo manager=new android.content.pm.PackageInfo();manager.packageName=Wire.PKG;
        manager.applicationInfo=new android.content.pm.ApplicationInfo();manager.applicationInfo.uid=10003;
        Shadows.shadowOf(context.getPackageManager()).installPackage(manager);
    }
    private static void awake(Context context){
        Shadows.shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
    }
    @Test public void nativeFactsNeedNoWindowEngineAndRejectStaleWrongUserOrDisplay()throws Exception{
        Wms service=new Wms();systemUi(service.mContext);awake(service.mContext);
        SuggestionEnvironment.Access access=new SuggestionEnvironment.Access(service);
        Intent facts=new Intent();access.snapshot(facts);
        assertFalse(facts.hasExtra("environmentError"));
        assertEquals("example.editor",facts.getStringExtra("foreground"));
        assertEquals("example.editor",facts.getStringExtra("fullscreenApp"));
        assertFalse(facts.getBooleanExtra("systemOverlay",true));assertEquals(2100,facts.getIntExtra("imeTop",0));
        long time=facts.getLongExtra("environmentAt",-1);
        assertTrue(SuggestionEnvironment.fresh(facts,time,0));
        assertFalse(SuggestionEnvironment.fresh(facts,time+2501,0));
        assertFalse(SuggestionEnvironment.fresh(facts,time-1,0));
        assertFalse(SuggestionEnvironment.fresh(facts,time,10));
        facts.putExtra("environmentDisplay",1);assertFalse(SuggestionEnvironment.fresh(facts,time,0));
        facts.putExtra("environmentDisplay",0);facts.removeExtra("imeVisible");assertFalse(SuggestionEnvironment.fresh(facts,time,0));
        service.mCurrentUserId=10;Intent switched=new Intent();access.snapshot(switched);assertTrue(switched.hasExtra("environmentError"));
    }
    @Test public void systemOcclusionIsRealAndCannotBeForgedByTitle()throws Exception{
        Wms service=new Wms();systemUi(service.mContext);awake(service.mContext);
        SuggestionEnvironment.Access access=new SuggestionEnvironment.Access(service);
        Window focus=service.mRoot.display.mCurrentFocus;
        focus.mAttrs.type=2017;focus.mAttrs.setTitle("NativeComplements-controls");focus.mAttrs.packageName=BackBridge.PACKAGE;
        Intent forged=new Intent();access.snapshot(forged);assertTrue(forged.getBooleanExtra("systemOverlay",false));
        focus.uid=10002;Intent owned=new Intent();access.snapshot(owned);assertFalse(owned.getBooleanExtra("systemOverlay",true));
        service.mHidingNonSystemOverlayWindows.add(new Window(service.mRoot.display));
        Intent nativeBlock=new Intent();access.snapshot(nativeBlock);assertTrue(nativeBlock.getBooleanExtra("systemOverlay",false));
    }
    private static int injected;
    public static boolean inject(InputEvent event,int mode,int uid){injected++;assertEquals(12345,uid);return true;}
    @Test public void otpWithoutEngineStopsAfterRealFocusChangeAndRejectsBadRequests()throws Exception{
        Wms service=new Wms();awake(service.mContext);SuggestionEnvironment.Access access=new SuggestionEnvironment.Access(service);
        Handler main=new Handler(Looper.getMainLooper());int[] result={-1};injected=0;
        SuggestionInput input=new SuggestionInput(access,null,SuggestionIndependenceTest.class.getMethod("inject",InputEvent.class,int.class,int.class),main);
        ResultReceiver reply=new ResultReceiver(main){protected void onReceiveResult(int code,Bundle data){result[0]=code;}};
        Intent request=new Intent().putExtra("digits","1234").putExtra("delay",100).putExtra("token",Wire.token()).putExtra("inputReply",reply);
        input.start(request);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,injected);
        service.mRoot.display.mCurrentFocus=new Window(service.mRoot.display);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        assertEquals(0,result[0]);assertEquals(2,injected);
        result[0]=-1;input.start(new Intent(request).putExtra("digits","12x4"));Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,result[0]);assertEquals(2,injected);
        service.mCurrentUserId=10;assertThrows(java.lang.reflect.InvocationTargetException.class,()->{
            var display=SuggestionEnvironment.Access.class.getDeclaredMethod("display");display.invoke(access);
        });
    }
    @Test public void requestUidMustResolveToThisUsersActualManagerOrSystemUi(){
        Context context=RuntimeEnvironment.getApplication();systemUi(context);
        assertTrue(SuggestionEnvironment.authorized(context,10002));
        assertFalse(SuggestionEnvironment.authorized(context,110002));
        assertFalse(SuggestionEnvironment.authorized(context,1000));
        assertFalse(SuggestionEnvironment.authorized(context,12345));
    }
}
