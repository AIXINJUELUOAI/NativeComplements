package dev.local.sunwindowdemo;

import static org.junit.Assert.*;

import com.android.quickstep.GestureState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35,shadows=SwipeMiniTest.NavBarShadow.class)
public class SwipeMiniTest {
    @org.robolectric.annotation.Implements(com.android.quickstep.util.NavBarPosition.class)
    public static class NavBarShadow {
        static boolean left,right;
        @org.robolectric.annotation.Implementation protected boolean isLeftEdge(){return left;}
        @org.robolectric.annotation.Implementation protected boolean isRightEdge(){return right;}
    }
    @org.junit.After public void resetNavBar(){NavBarShadow.left=NavBarShadow.right=false;}
    @Test public void resolvesExactQuickstepMembers()throws Exception{
        SwipeMini.Targets t=SwipeMini.resolve(getClass().getClassLoader());
        assertEquals("updateDisplacement",t.displacement().getName());
        assertEquals("calculateEndTarget",t.endTarget().getName());
        assertEquals(GestureState.GestureEndTarget.class,t.endTarget().getReturnType());
        assertSame(GestureState.GestureEndTarget.HOME,t.home());
        assertSame(GestureState.GestureEndTarget.RECENTS,t.recentsTarget());
        assertEquals("mRecentsView",t.recents().getName());
        assertEquals("mDeviceState",t.deviceState().getName());assertEquals("getNavBarPosition",t.navBarPosition().getName());
        assertEquals("isLeftEdge",t.leftEdge().getName());assertEquals("isRightEdge",t.rightEdge().getName());
        assertEquals("getDisplayInfo",t.readDisplayInfo().getName());assertEquals("logicalHeight",t.logicalHeight().getName());
        assertEquals("mGestureState",t.gesture().getName());
        assertEquals("mRemoteTargetHandles",t.handles().getName());
        assertEquals("getCurrentCropRect",t.currentCrop().getName());
        assertEquals("getCurrentMatrix",t.currentMatrix().getName());
        assertEquals("getCurrentCornerRadius",t.currentRadius().getName());
        assertEquals("setTargetAlpha",t.targetAlpha().getName());
        assertEquals("findWorkspaceView",t.workspaceView().getName());
    }

    @Test public void thresholdIsStrictlyMoreThanOneThird()throws Exception{
        assertEquals(1f/3f,SwipeMini.THRESHOLD,0f);
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        var display=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        var handler=new com.android.quickstep.AbsSwipeUpHandler(){};
        var targets=SwipeMini.resolve(getClass().getClassLoader());
        var info=targets.displayInfo().newInstance();assertEquals(true,targets.readDisplayInfo().invoke(display,info));int height=targets.logicalHeight().getInt(info);
        targets.context().set(handler,context);
        targets.recents().set(handler,new com.android.quickstep.views.RecentsView(context));
        var track=SwipeMini.class.getDeclaredMethod("track",SwipeMini.Targets.class,Object.class,float.class);track.setAccessible(true);
        var armed=SwipeMini.class.getDeclaredField("armed");armed.setAccessible(true);
        try{
            SwipeMini.configure(new android.os.Binder(),false);
            track.invoke(null,targets,handler,-height/3f);
            assertFalse(armed.getBoolean(null));
            track.invoke(null,targets,handler,-height/3f-1);
            assertTrue(armed.getBoolean(null));
            track.invoke(null,targets,handler,-height/3f+1);
            assertFalse(armed.getBoolean(null));
        }finally{SwipeMini.configure(null,true);}
    }

    @Test public void thresholdFollowsPhysicalRotationWhileLauncherResourcesStayPortrait()throws Exception{
        var app=org.robolectric.RuntimeEnvironment.getApplication();
        var configuration=new android.content.res.Configuration(app.getResources().getConfiguration());
        configuration.orientation=android.content.res.Configuration.ORIENTATION_PORTRAIT;
        var launcher=app.createConfigurationContext(configuration);
        var metrics=launcher.getResources().getDisplayMetrics();metrics.widthPixels=1200;metrics.heightPixels=2670;
        var display=app.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        var shadow=org.robolectric.Shadows.shadowOf(display);
        var handler=new com.android.quickstep.AbsSwipeUpHandler(){};var targets=SwipeMini.resolve(getClass().getClassLoader());
        targets.context().set(handler,launcher);targets.recents().set(handler,new com.android.quickstep.views.RecentsView(launcher));
        var track=SwipeMini.class.getDeclaredMethod("track",SwipeMini.Targets.class,Object.class,float.class);track.setAccessible(true);
        var armed=SwipeMini.class.getDeclaredField("armed");armed.setAccessible(true);
        try{
            SwipeMini.configure(new android.os.Binder(),false);
            for(int rotation:new int[]{0,1,3,2,0}){
                boolean landscape=rotation%2!=0;int height=landscape?1200:2670;
                shadow.setRealWidth(landscape?2670:1200);shadow.setRealHeight(height);shadow.setRotation(rotation);
                assertEquals(2670,launcher.getResources().getDisplayMetrics().heightPixels);
                assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT,launcher.getResources().getConfiguration().orientation);
                var info=targets.displayInfo().newInstance();assertEquals(true,targets.readDisplayInfo().invoke(display,info));
                assertEquals(height,targets.logicalHeight().getInt(info));assertEquals(landscape?2670:1200,targets.logicalWidth().getInt(info));
                for(int edge:new int[]{0,1,2}){
                    NavBarShadow.left=edge==1;NavBarShadow.right=edge==2;int extent=edge==0?height:(landscape?2670:1200);
                    track.invoke(null,targets,handler,-extent/3f);assertFalse("rotation="+rotation+" edge="+edge,armed.getBoolean(null));
                    track.invoke(null,targets,handler,-extent/3f-1);assertTrue(armed.getBoolean(null));
                    track.invoke(null,targets,handler,-extent/3f+1);assertFalse(armed.getBoolean(null));
                }
            }
            track.invoke(null,targets,handler,-1000f);assertTrue(armed.getBoolean(null));
            track.invoke(null,targets,handler,Float.NaN);assertFalse(armed.getBoolean(null));
        }finally{SwipeMini.configure(null,true);}
    }

    private static void set(Object owner,Class<?> type,String name,Object value)throws Exception{
        var field=type.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object poseHandler(SwipeMini.Targets targets,android.graphics.RectF crop,android.graphics.Matrix matrix,float radius)throws Exception{
        var handler=new com.android.quickstep.AbsSwipeUpHandler(){};
        targets.gesture().set(handler,new GestureState(){@Override public int[] getRunningTaskIds(boolean all){return new int[]{123};}});
        var simulator=new com.android.quickstep.util.TaskViewSimulator(){
            @Override public android.graphics.RectF getCurrentRect(){throw new AssertionError("Launcher-space pose must not be read");}
            @Override public android.graphics.RectF getCurrentCropRect(){return crop;}
            @Override public android.graphics.Matrix getCurrentMatrix(){return matrix;}
            @Override public float getCurrentCornerRadius(){return radius;}
        };
        targets.handles().set(handler,new com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle[]{
            new com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle(){
                @Override public com.android.quickstep.util.TaskViewSimulator getTaskViewSimulator(){return simulator;}
            }});
        return handler;
    }
    @Test public void handoffUsesNativeSurfacePoseAndRadiusInBothLandscapeDirections()throws Exception{
        var targets=SwipeMini.resolve(getClass().getClassLoader());
        var handoff=SwipeMini.class.getDeclaredMethod("handoff",SwipeMini.Targets.class,Object.class);handoff.setAccessible(true);
        var crop=new android.graphics.RectF(0,0,1200,2670);
        android.graphics.RectF[] received={null};float[] receivedRadius={0};boolean[] accepted={true};int[] calls={0};
        var channel=new android.os.Binder(){@Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags){
            assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION,code);data.enforceInterface(SwipeMini.CHANNEL);assertEquals(123,data.readInt());
            received[0]=data.readTypedObject(android.graphics.RectF.CREATOR);receivedRadius[0]=data.readFloat();data.enforceNoDataAvail();calls[0]++;
            reply.writeNoException();reply.writeBoolean(accepted[0]);return true;
        }};
        try{
            SwipeMini.configure(channel,false);
            float[][] transforms={{.5f,0,100,0,.5f,150,0,0,1},{0,-.5f,1600,.5f,0,100,0,0,1},{0,.5f,100,-.5f,0,1000,0,0,1}};
            var expected=new android.graphics.RectF[]{new android.graphics.RectF(100,150,700,1485),new android.graphics.RectF(265,100,1600,700),new android.graphics.RectF(100,400,1435,1000)};
            for(int index=0;index<transforms.length;index++){
                var matrix=new android.graphics.Matrix();matrix.setValues(transforms[index]);
                Object handler=poseHandler(targets,crop,matrix,48);
                assertEquals(true,handoff.invoke(null,targets,handler));assertEquals(expected[index],received[0]);assertEquals(24,receivedRadius[0],.001f);
                assertEquals(new android.graphics.RectF(0,0,1200,2670),crop);
                float[] unchanged=new float[9];matrix.getValues(unchanged);assertArrayEquals(transforms[index],unchanged,0);
                accepted[0]=false;assertEquals(false,handoff.invoke(null,targets,handler));accepted[0]=true;
            }
            assertEquals(6,calls[0]);
            assertEquals(false,handoff.invoke(null,targets,poseHandler(targets,crop,new android.graphics.Matrix(),-1)));
            assertEquals(6,calls[0]);
        }finally{SwipeMini.configure(null,true);}
    }

    @Test public void landscapeHostKeepsAdmissionChecksAndRequiresBackendAcceptance()throws Exception{
        var app=org.robolectric.RuntimeEnvironment.getApplication();
        var configuration=new android.content.res.Configuration(app.getResources().getConfiguration());
        configuration.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        var context=app.createConfigurationContext(configuration);
        var constructor=SystemUiHost.class.getDeclaredConstructor(android.content.Context.class);constructor.setAccessible(true);
        var host=constructor.newInstance(context);
        var previousPreferences=Config.hostPreferences;
        var preferences=context.getSharedPreferences("swipe-landscape-test",0);
        preferences.edit().putBoolean("enabled",true).putBoolean("swipeMini",true).putBoolean("landscape",true).commit();
        Config.hostPreferences=preferences;
        set(host,SystemUiHost.class,"config",new Config(context));set(host,SystemUiHost.class,"settingsReady",true);set(host,SystemUiHost.class,"backendReady",true);
        set(host,SystemUiHost.class,"lastSwipe",-1000L);
        var keyguard=context.getSystemService(android.app.KeyguardManager.class);org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        var task=new android.app.ActivityManager.RunningTaskInfo();task.taskId=123;task.baseActivity=new android.content.ComponentName("example.game","example.game.Main");
        var displayField=android.app.ActivityManager.RunningTaskInfo.class.getField("displayId");displayField.setInt(task,0);
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.ActivityManager.class)).setTasks(java.util.List.of(task));
        var card=new android.graphics.RectF(265,100,1600,700);int[] preparations={0};boolean[] accept={false};
        var endpoint=Wire.class.getDeclaredField("endpoint");endpoint.setAccessible(true);Object previousEndpoint=endpoint.get(null);
        endpoint.set(null,new android.os.Binder(){@Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags){
            assertEquals(Wire.PREPARE_SWIPE,code);data.enforceInterface(Wire.CHANNEL);assertEquals(123,data.readInt());assertEquals("example.game",data.readString());
            assertTrue(Wire.validToken(data.readString()));assertEquals(card,data.readTypedObject(android.graphics.RectF.CREATOR));assertEquals(24,data.readFloat(),0);data.enforceNoDataAvail();
            preparations[0]++;reply.writeNoException();reply.writeBoolean(accept[0]);return true;
        }});
        var swipe=SystemUiHost.class.getDeclaredMethod("swipeMini",int.class,android.graphics.RectF.class,float.class);swipe.setAccessible(true);
        try{
            assertEquals(false,swipe.invoke(host,123,card,24));assertEquals(1,preparations[0]);
            accept[0]=true;assertEquals(true,swipe.invoke(host,123,card,24));assertEquals(2,preparations[0]);
            set(host,SystemUiHost.class,"lastSwipe",-1000L);
            displayField.setInt(task,1);assertEquals(false,swipe.invoke(host,123,card,24));displayField.setInt(task,0);
            org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(true);assertEquals(false,swipe.invoke(host,123,card,24));org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(false);
            set(host,SystemUiHost.class,"pinnedTask",123);assertEquals(false,swipe.invoke(host,123,card,24));set(host,SystemUiHost.class,"pinnedTask",-1);
            set(host,SystemUiHost.class,"backendReady",false);assertEquals(false,swipe.invoke(host,123,card,24));set(host,SystemUiHost.class,"backendReady",true);
            preferences.edit().putBoolean("swipeMini",false).commit();assertEquals(false,swipe.invoke(host,123,card,24));assertEquals(2,preparations[0]);
        }finally{endpoint.set(null,previousEndpoint);Config.hostPreferences=previousPreferences;}
    }

    @Test public void onlyAcceptedHandlerUsesNativeNoIconBranch(){
        Object handler=new Object(),other=new Object(),icon=new Object();
        try{
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.acceptHandler(handler);
            assertNull(SwipeMini.workspaceTarget(handler,icon));
            assertNull(SwipeMini.workspaceTarget(handler,null));
            assertSame(icon,SwipeMini.workspaceTarget(other,icon));
            SwipeMini.finishHandler(other);
            assertNull(SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.finishHandler(handler);
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
            SwipeMini.acceptHandler(handler);
            SwipeMini.configure(null,true);
            assertSame(icon,SwipeMini.workspaceTarget(handler,icon));
        }finally{SwipeMini.finishHandler(handler);}
    }

    @Test public void pauseDetectorIsNotRequiredToResolve()throws Exception{
        ClassLoader withoutPause=new ClassLoader(getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.contains("MotionPauseDetector")||name.contains("ActiveGestureLog"))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        assertNotNull(SwipeMini.resolve(withoutPause));
    }

    @Test public void disablingHintDoesNotDisarmTheGesture()throws Exception{
        var armed=SwipeMini.class.getDeclaredField("armed");armed.setAccessible(true);
        try{
            SwipeMini.configure(new android.os.Binder(),true);
            armed.setBoolean(null,true);
            SwipeMini.configure(new android.os.Binder(),false);
            assertTrue(armed.getBoolean(null));
            SwipeMini.configure(null,false);
            assertFalse(armed.getBoolean(null));
        }finally{SwipeMini.configure(null,true);}
    }

    @Test public void missingNativeGeometryOrNavigationAxisStaysNative(){
        for(String missing:new String[]{"android.view.DisplayInfo","com.android.quickstep.util.NavBarPosition"}){
            ClassLoader loader=new ClassLoader(getClass().getClassLoader()){
                @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                    if(name.equals(missing))throw new ClassNotFoundException(name);
                    return super.loadClass(name,resolve);
                }
            };
            assertThrows(ClassNotFoundException.class,()->SwipeMini.resolve(loader));
        }
    }

    @Test public void missingQuickstepStaysNative(){
        ClassLoader empty=new ClassLoader(getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("com.android.quickstep."))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        assertThrows(ClassNotFoundException.class,()->SwipeMini.resolve(empty));
    }
}
