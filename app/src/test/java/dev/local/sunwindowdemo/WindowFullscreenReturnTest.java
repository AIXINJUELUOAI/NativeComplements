package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowFullscreenReturnTest {
    public static final class Display { public int mDisplayId; }
    public static final class Policy { public int mCurrentUserId; }
    public static final class Wms { public final Policy mPolicy=new Policy(); }
    public static final class Task {
        public int mTaskId,mUserId;public boolean mLaunchNextToBubble,mReparentLeafTaskIfRelaunch,mReparentLeafTaskIfRelaunchFromHome;
        public Rect mLastNonFullscreenBounds=new Rect(11,22,333,444);
        int mode=1,type=1,mutations;boolean attached=true,visible=true,leaf=true,root=true,alwaysOnTop;
        Display display;Object area;Activity top;final Configuration requested=new Configuration();
        public boolean isAttached(){return attached;}
        public boolean isVisibleRequested(){return visible;}
        public int getWindowingMode(){return mode;}
        public int getActivityType(){return type;}
        public boolean isLeafTask(){return leaf;}
        public boolean isRootTask(){return root;}
        public boolean isAlwaysOnTop(){return alwaysOnTop;}
        public Display getDisplayContent(){return display;}
        public Object getTaskDisplayArea(){return area;}
        public Activity topRunningActivity(){return top;}
        public Configuration getRequestedOverrideConfiguration(){return requested;}
        public void setWindowingMode(int value){mutations++;mode=value;}
        public void setBounds(Rect value){mutations++;mLastNonFullscreenBounds=value;}
    }
    public static final class Activity {
        public boolean finishing;public String packageName,launchedFromPackage;public int launchedFromUid;
        public Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("test-return://callback")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int uid;boolean attached=true,bubble;Task task;Display display;
        public Task getTask(){return task;}
        public int getUid(){return uid;}
        public boolean attachedToProcess(){return attached;}
        public boolean getLaunchedFromBubble(){return bubble;}
        public Display getDisplayContent(){return display;}
    }
    public static final class Root {
        final Map<Integer,Task> tasks=new HashMap<>();Display display;
        public Object anyTaskForId(int id,int mode){assertEquals(0,mode);return tasks.get(id);}
        public Display getDefaultDisplay(){return display;}
    }
    static final class Lease extends Binder { boolean alive=true;@Override public boolean isBinderAlive(){return alive;} }
    FrameworkAccess access;WindowEngine engine;WindowEngine.Session session;Task sourceTask,target;Activity source,request,nativeTop;Root root;Wms wms;Lease lease;
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static Object call(Object owner,String method,Class<?>[] types,Object...args)throws Exception{
        Method found=owner.getClass().getDeclaredMethod(method,types);found.setAccessible(true);return found.invoke(owner,args);
    }
    private static ActivityOptions mode(int mode)throws Exception{
        ActivityOptions options=ActivityOptions.makeBasic();call(options,"setLaunchWindowingMode",new Class<?>[]{int.class},mode);return options;
    }
    private static int mode(ActivityOptions options)throws Exception{return (Integer)call(options,"getLaunchWindowingMode",new Class<?>[]{});}
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess access=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(access,"methods",new HashMap<>());set(access,"fields",new HashMap<>());
        set(access,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));return access;
    }
    @Before public void setup()throws Exception{
        root=new Root();root.display=new Display();wms=new Wms();sourceTask=new Task();target=new Task();
        sourceTask.mTaskId=139;sourceTask.mode=5;target.mTaskId=123;sourceTask.display=target.display=root.display;
        sourceTask.area=target.area=new Object();
        root.tasks.put(139,sourceTask);root.tasks.put(123,target);
        source=new Activity();source.uid=10222;source.packageName="source.video";source.task=sourceTask;source.display=root.display;sourceTask.top=source;
        nativeTop=new Activity();nativeTop.uid=10273;nativeTop.packageName="target.reader";nativeTop.task=target;nativeTop.display=root.display;target.top=nativeTop;
        request=new Activity();request.uid=nativeTop.uid;request.packageName=nativeTop.packageName;request.launchedFromUid=source.uid;
        request.launchedFromPackage=source.packageName;request.display=root.display;
        lease=new Lease();session=new WindowEngine.Session();session.task=sourceTask;session.taskId=139;session.uid=source.uid;
        session.pkg=source.packageName;session.lease=lease;
        access=access();engine=new WindowEngine(access,message->{});set(engine,"lock",new Object());set(engine,"root",root);set(engine,"wms",wms);
        set(engine,"active",session);set(engine,"sessions",List.of(session));set(engine,"uiReady",true);set(engine,"uiLease",lease);
        set(engine,"context",RuntimeEnvironment.getApplication());
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
    }
    private boolean protect(ActivityOptions options)throws Exception{return engine.keepNativeFullscreen(request,source,target,options,source.uid);}
    private void unchanged(){assertEquals(0,sourceTask.mutations);assertEquals(0,target.mutations);assertEquals(5,sourceTask.mode);assertFalse(engine.owns(target));}
    @Test public void repeatedComputeAndLayoutDecisionsLeaveOptionsIntentTasksAndHistoryUntouched()throws Exception{
        ActivityOptions original=mode(0);call(original,"setPendingIntentLaunchFlags",new Class<?>[]{int.class},Intent.FLAG_ACTIVITY_NEW_TASK);
        int beforePendingFlags=(Integer)call(original,"getPendingIntentLaunchFlags",new Class<?>[]{});
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK,beforePendingFlags);
        Intent before=new Intent(request.intent);Rect oldHistory=target.mLastNonFullscreenBounds;
        WindowHistory history=(WindowHistory)get(engine,"history");WindowHistory.Entry entry=new WindowHistory.Entry(123,0,"target.reader","target.reader.Main",null);history.windowed(entry);
        ShareWindow.NativeLaunch launch=new ShareWindow.NativeLaunch(request,source,target,original,source.uid);
        for(int phase=0;phase<2;phase++){
            assertTrue(protect(original));
            boolean inherit=ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,target.area,source,target);
            assertFalse(inherit);assertEquals(1,inherit?sourceTask.mode:target.mode);
        }
        assertSame(original,launch.options());assertEquals(0,mode(original));assertNull(original.getLaunchBounds());
        assertEquals(beforePendingFlags,call(original,"getPendingIntentLaunchFlags",new Class<?>[]{}));
        assertTrue(before.filterEquals(request.intent));assertEquals(before.getFlags(),request.intent.getFlags());
        assertSame(oldHistory,target.mLastNonFullscreenBounds);assertEquals(new Rect(11,22,333,444),oldHistory);assertEquals(List.of(entry),history.entries());
        assertSame(lease,session.lease);assertEquals(1,((List<?>)get(engine,"sessions")).size());unchanged();
        assertTrue(protect(null));assertTrue(protect(mode(1)));
    }
    @Test public void explicitPresentationAndTaskOrDisplayRoutesAreNotRewritten()throws Exception{
        for(int value:new int[]{2,5,6})assertFalse(protect(mode(value)));
        ActivityOptions bounds=mode(0);bounds.setLaunchBounds(new Rect(0,0,10,10));assertFalse(protect(bounds));
        for(String setter:new String[]{"setLaunchTaskId","setLaunchDisplayId","setCallerDisplayId","setLaunchTaskDisplayAreaFeatureId"}){
            ActivityOptions routed=mode(0);call(routed,setter,new Class<?>[]{int.class},0);assertFalse(protect(routed));
        }
        ActivityOptions fragment=mode(0);call(fragment,"setLaunchTaskFragmentToken",new Class<?>[]{IBinder.class},new Binder());assertFalse(protect(fragment));
        Class<?> tokenType=Class.forName("android.window.WindowContainerToken"),remoteType=Class.forName("android.window.IWindowContainerToken");
        Object remote=Proxy.newProxyInstance(remoteType.getClassLoader(),new Class<?>[]{remoteType},(proxy,method,args)->new Binder());
        Object token=tokenType.getConstructor(remoteType).newInstance(remote);
        for(String setter:new String[]{"setLaunchRootTask","setLaunchTaskDisplayArea"}){
            ActivityOptions routed=mode(0);call(routed,setter,new Class<?>[]{tokenType},token);assertFalse(protect(routed));
        }
        unchanged();
    }
    @Test public void nativeFalseAndAbsentOrMismatchedLaunchScopeRemainNative()throws Exception{
        ShareWindow.NativeLaunch launch=new ShareWindow.NativeLaunch(request,source,target,null,source.uid);
        assertFalse(ShareWindow.inheritSourceMode(access,engine,launch,false,null,null,null,null));
        assertTrue(ShareWindow.inheritSourceMode(access,engine,null,true,root.display,target.area,source,target));
        assertTrue(ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,target.area,new Activity(),target));
        assertTrue(ShareWindow.inheritSourceMode(access,engine,launch,true,new Display(),target.area,source,target));
        assertTrue(ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,new Object(),source,target));
        Object area=sourceTask.area;sourceTask.area=new Object();
        assertTrue(ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,target.area,source,target));sourceTask.area=area;
        for(Object selected:new Object[]{null,new Task()}){
            ShareWindow.NativeLaunch other=new ShareWindow.NativeLaunch(request,source,selected,null,source.uid);
            assertTrue(ShareWindow.inheritSourceMode(access,engine,other,true,root.display,target.area,source,target));
        }
        target.mode=5;assertTrue(ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,target.area,source,target));target.mode=1;
        assertFalse(ShareWindow.inheritSourceMode(access,engine,launch,true,root.display,target.area,source,target));unchanged();
    }
    @Test public void callerLeaseAndNativeTargetIdentityAreExact()throws Exception{
        assertFalse(engine.keepNativeFullscreen(request,source,target,null,-1));
        assertFalse(engine.keepNativeFullscreen(request,source,target,null,10274));
        request.launchedFromUid++;assertFalse(protect(null));request.launchedFromUid--;
        request.launchedFromPackage="foreign";assertFalse(protect(null));request.launchedFromPackage=source.packageName;
        source.packageName="foreign";assertFalse(protect(null));source.packageName=session.pkg;
        lease.alive=false;assertFalse(protect(null));lease.alive=true;set(engine,"uiLease",new Binder());assertFalse(protect(null));set(engine,"uiLease",lease);
        root.tasks.put(123,new Task());assertFalse(protect(null));root.tasks.put(123,target);
        root.tasks.put(139,new Task());assertFalse(protect(null));root.tasks.put(139,sourceTask);
        target.top=new Activity();assertFalse(protect(null));target.top=nativeTop;
        request.uid++;assertFalse(protect(null));request.uid--;request.packageName="foreign";assertFalse(protect(null));request.packageName=nativeTop.packageName;
        request.display=new Display();assertFalse(protect(null));request.display=root.display;
        target.display=new Display();assertFalse(protect(null));target.display=root.display;wms.mPolicy.mCurrentUserId=1;assertFalse(protect(null));wms.mPolicy.mCurrentUserId=0;
        unchanged();
    }
    @Test public void transitionSecurityNativeModesAndExistingOwnershipRemainNative()throws Exception{
        for(int gate=0;gate<12;gate++){
            setup();session.restoring=gate==0;session.closing=gate==1;session.fullTransition=gate==2;session.transitioning=gate==3;
            session.entering=gate==4;session.returning=gate==5;session.parked=gate==6;session.prioritySuspended=gate==7;session.screenSuspended=gate==8;
            if(gate==9)session.frame="mini";if(gate==10)set(engine,"uiReady",false);if(gate==11)set(engine,"recovering",true);
            assertFalse("gate="+gate,protect(null));unchanged();
        }
        setup();source.bubble=true;assertFalse(protect(null));source.bubble=false;sourceTask.mLaunchNextToBubble=true;assertFalse(protect(null));sourceTask.mLaunchNextToBubble=false;
        target.mode=5;assertFalse(protect(null));target.mode=1;target.alwaysOnTop=true;assertFalse(protect(null));target.alwaysOnTop=false;
        target.leaf=false;assertFalse(protect(null));target.leaf=true;target.root=false;assertFalse(protect(null));target.root=true;
        target.type=2;assertFalse(protect(null));target.type=1;
        for(String field:new String[]{"mLaunchNextToBubble","mReparentLeafTaskIfRelaunch","mReparentLeafTaskIfRelaunchFromHome"}){
            set(target,field,true);assertFalse(protect(null));set(target,field,false);
        }
        Object wc=get(target.requested,"windowConfiguration");call(wc,"setWindowingMode",new Class<?>[]{int.class},5);assertFalse(protect(null));call(wc,"setWindowingMode",new Class<?>[]{int.class},1);
        call(wc,"setBounds",new Class<?>[]{Rect.class},new Rect(1,2,30,40));assertFalse(protect(null));call(wc,"setBounds",new Class<?>[]{Rect.class},new Rect());
        WindowEngine.Session other=new WindowEngine.Session();other.task=target;set(engine,"sessions",List.of(session,other));assertFalse(protect(null));set(engine,"sessions",List.of(session));
        engine.shareLaunch.set(new WindowEngine.ShareLaunch(request,null,lease,sourceTask,false));assertFalse(protect(null));engine.shareLaunch.remove();
        request.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);assertFalse(protect(null));unchanged();
    }
}
