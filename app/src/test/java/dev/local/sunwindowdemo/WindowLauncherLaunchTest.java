package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import dev.local.sunwindowdemo.core.LauncherTargets;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36,manifest=Config.NONE)
public class WindowLauncherLaunchTest {
    /** ROM getter shapes with opaque fixture tokens; production separately requires the exact platform token type. */
    public static final class Options {
        public int mode,type,task=-1,display=-1,callerDisplay=-1,feature=-1;
        public boolean avoid,overlay,behind,transientLaunch,bubble,alwaysTop;
        public Rect bounds;
        public Object root,area;
        public android.os.IBinder fragment;
        public int getLaunchWindowingMode(){return mode;}
        public int getLaunchActivityType(){return type;}
        public int getLaunchTaskId(){return task;}
        public int getLaunchDisplayId(){return display;}
        public int getCallerDisplayId(){return callerDisplay;}
        public int getLaunchTaskDisplayAreaFeatureId(){return feature;}
        public boolean getAvoidMoveToFront(){return avoid;}
        public boolean getTaskOverlay(){return overlay;}
        public boolean getLaunchTaskBehind(){return behind;}
        public boolean getTransientLaunch(){return transientLaunch;}
        public boolean getLaunchNextToBubble(){return bubble;}
        public boolean getTaskAlwaysOnTop(){return alwaysTop;}
        public Object getLaunchRootTask(){return root;}
        public Object getLaunchTaskDisplayArea(){return area;}
        public android.os.IBinder getLaunchTaskFragmentToken(){return fragment;}
        public Rect getLaunchBounds(){return bounds;}
    }
    public static final class Verdict {boolean allowed=true;public boolean allows(){return allowed;}}
    public static final class Starter {
        public boolean mDoResume=true;
        public Verdict mBalVerdict=new Verdict();
        boolean avoid;public boolean avoidMoveToFront(){return avoid;}
    }
    public static final class Supervisor {
        public int startActivityFromRecents(int pid,int uid,int taskId,SafeOptions options){return 2;}
    }
    public static final class SafeOptions {public ActivityOptions getOptions(Supervisor supervisor){return null;}}
    public static final class Activity {
        public Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        public int launchedFromUid=10111,mUserId;
        public String launchedFromPackage=LauncherTargets.LINEAGE,packageName="test.target";
        int uid=10222;Task task;Object display;
        public int getUid(){return uid;}
        public Task getTask(){return task;}
        public Object getDisplayContent(){return display;}
    }
    public static final class Task {
        public int mTaskId=55,mUserId;
        Object display=new Object();Activity top;
        public Object getDisplayContent(){return display;}
        public Activity topRunningActivity(){return top;}
    }
    public static final class Root {
        Task task;public Task anyTaskForId(int id,int mode){assertEquals(0,mode);return task!=null&&task.mTaskId==id?task:null;}
    }
    FrameworkAccess a;WindowEngine engine;WindowLauncherLaunch access;
    Root root;Task task;Activity request,started;Starter starter;Supervisor supervisor;
    final Map<String,XposedInterface.Hooker> hooks=new HashMap<>();
    final List<String> logs=new ArrayList<>();
    final List<XposedInterface.HookHandle> handles=new ArrayList<>();
    int nativeCalls,optionCalls,deopts,installed,unhooked,failAt=-1;boolean deoptAllowed=true,failLog;
    private static void set(Object owner,String name,Object value)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static FrameworkAccess access()throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess a=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(a,"methods",new HashMap<>());set(a,"fields",new HashMap<>());
        set(a,"lookup",Root.class.getMethod("anyTaskForId",int.class,int.class));return a;
    }
    @Before public void setup()throws Exception {
        a=access();root=new Root();task=new Task();root.task=task;started=new Activity();started.task=task;started.display=task.display;task.top=started;
        request=new Activity();starter=new Starter();supervisor=new Supervisor();
        engine=new WindowEngine(a,logs::add);WindowEngine.Session mini=new WindowEngine.Session();
        mini.task=task;mini.taskId=task.mTaskId;mini.frame="mini";
        set(engine,"root",root);set(engine,"lock",new Object());set(engine,"sessions",List.of(mini));set(engine,"pinned",mini);
        access=new WindowLauncherLaunch(a,engine,Starter.class,Options.class,Object.class,line->{logs.add(line);if(failLog)throw new Error("private sink contents");});
    }
    private XposedModule module() {
        XposedModule module=new XposedModule(){};
        module.attachFramework((XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.class},(p,m,args)->{
            if(m.getName().equals("deoptimize")){deopts++;return deoptAllowed;}
            if(m.getName().equals("hook")) {
                Executable executable=(Executable)args[0];
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.HookBuilder.class},(bp,bm,ba)->{
                    if(bm.getName().equals("setExceptionMode"))return bp;
                    if(bm.getName().equals("intercept")) {
                        if(++installed==failAt)throw new Error("install failure");
                        hooks.put(executable.getName(),(XposedInterface.Hooker)ba[0]);
                        return new XposedInterface.HookHandle(){
                            public Executable getExecutable(){return executable;}
                            public void unhook(){unhooked++;hooks.remove(executable.getName());}
                        };
                    }
                    throw new AssertionError(bm);
                });
            }
            throw new AssertionError(m);
        }));return module;
    }
    private boolean install()throws Exception {
        return access.installRecents(module(),Supervisor.class.getMethod("startActivityFromRecents",int.class,int.class,int.class,SafeOptions.class),
                SafeOptions.class.getMethod("getOptions",Supervisor.class),handles);
    }
    interface Native {Object run()throws Throwable;}
    private Object call(String method,Object owner,Object[] args,Native body)throws Throwable {
        XposedInterface.Chain chain=(XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.Chain.class},(p,m,ca)->{
            if(m.getName().equals("getThisObject"))return owner;
            if(m.getName().equals("getArg"))return args[(Integer)ca[0]];
            if(m.getName().equals("proceed")){if(method.equals("getOptions"))optionCalls++;else nativeCalls++;return body.run();}
            throw new AssertionError(m);
        });return hooks.get(method).intercept(chain);
    }
    private Object recents(SafeOptions options,Native body)throws Throwable {
        return call("startActivityFromRecents",supervisor,new Object[]{1,10111,55,options},body);
    }
    private Object options(SafeOptions safe,Supervisor owner,ActivityOptions result)throws Throwable {
        return call("getOptions",safe,new Object[]{owner},()->result);
    }
    @Test public void exactNativeSelectedTaskAcceptsUnattachedRequestWithoutSourceAndRejectsMismatches()throws Exception {
        assertSame(task,WindowLauncherLaunch.launcherTask(a,request,started,10111));
        request.task=task;request.display=task.display;assertSame(task,WindowLauncherLaunch.launcherTask(a,request,started,10111));
        request.task=new Task();assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.task=null;
        request.uid++;assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.uid--;
        request.packageName="foreign";assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.packageName=started.packageName;
        request.launchedFromUid++;assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.launchedFromUid--;
        request.launchedFromPackage="third.party.launcher";assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.launchedFromPackage=LauncherTargets.LINEAGE;
        request.mUserId=10;assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.mUserId=0;
        request.display=new Object();assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.display=null;
        Activity foreign=new Activity();foreign.uid++;task.top=foreign;assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));task.top=started;
        request.intent.setAction(Intent.ACTION_VIEW);assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));request.intent.setAction(Intent.ACTION_MAIN);
        request.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);assertNull(WindowLauncherLaunch.launcherTask(a,request,started,10111));
    }
    @Test public void optionsAreReadOnlyAndOnlyDefaultFullscreenAndExactRecentsTaskRouteAreAllowed()throws Exception {
        assertTrue(WindowLauncherLaunch.optionsAllowed(a,null,-1));Options options=new Options();
        assertTrue(WindowLauncherLaunch.optionsAllowed(a,options,-1));
        options.mode=1;assertTrue(WindowLauncherLaunch.optionsAllowed(a,options,-1));
        options.mode=5;assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,-1));options.mode=0;
        options.bounds=new Rect();assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,-1));options.bounds=null;
        options.task=55;assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,-1));
        assertTrue(WindowLauncherLaunch.optionsAllowed(a,options,55));assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,56));options.task=-1;
        for(String field:new String[]{"display","callerDisplay"}) {
            set(options,field,0);assertTrue(WindowLauncherLaunch.optionsAllowed(a,options,55));
            set(options,field,1);assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,55));set(options,field,-1);
        }
        options.feature=0;assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,55));options.feature=-1;
        options.type=2;assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,55));options.type=0;
        for(String field:new String[]{"avoid","overlay","behind","transientLaunch","bubble","alwaysTop"}) {
            set(options,field,true);assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,55));set(options,field,false);
        }
        options.fragment=new android.os.Binder();assertFalse(WindowLauncherLaunch.optionsAllowed(a,options,55));
        assertEquals(-1,options.task);assertNull(options.bounds);assertFalse(options.avoid);
    }
    @Test public void startResultHonorsNativeVerdictAndDiagnosticFaultStaysLocal()throws Exception {
        assertTrue(install());
        for(int code:new int[]{-96,1,101,102})access.beforeStartResult(new Object(),request,started,null,code,10111);
        starter.mDoResume=false;access.beforeStartResult(starter,new Object(),started,null,0,10111);
        starter.mDoResume=true;starter.avoid=true;access.beforeStartResult(starter,new Object(),started,null,2,10111);
        starter.avoid=false;starter.mBalVerdict.allowed=false;access.beforeStartResult(starter,new Object(),started,null,3,10111);
        assertFalse(logs.stream().anyMatch(s->s.contains("OBSERVE_UNAVAILABLE")));
        starter.mBalVerdict.allowed=true;failLog=true;
        access.beforeStartResult(starter,new Object(),started,null,0,10111);
        assertTrue(logs.stream().anyMatch(s->s.equals("MINI_LAUNCH_OBSERVE_UNAVAILABLE")));
        // Real Engine's missing context safely rejects; it cannot restore or pick another task by package.
        access.beforeStartResult(starter,request,started,null,0,10111);assertTrue(engine.owns(task));
    }
    @Test public void recentsUsesOnlyNativeValidatedReturnedOptionsAndNeverCallsGetterAgain()throws Throwable {
        assertTrue(install());SafeOptions safe=new SafeOptions();
        Integer original=Integer.valueOf(2);
        assertSame(original,recents(safe,()->{
            WindowLauncherLaunch.Recent scope=access.recent.get();assertSame(task,scope.task);assertFalse(scope.observed);
            assertNull(options(safe,supervisor,null));assertTrue(scope.observed);assertTrue(scope.allowed);
            assertEquals(1,optionCalls);return original;
        }));
        assertEquals(1,optionCalls);assertEquals(1,nativeCalls);assertNull(access.recent.get());
        recents(safe,()->{assertFalse(access.recent.get().observed);return 3;});
        recents(null,()->{assertTrue(access.recent.get().observed);assertTrue(access.recent.get().allowed);return 2;});
        assertEquals(1,optionCalls);assertEquals(3,nativeCalls);assertTrue(engine.owns(task));
    }
    @Test public void nestedScopesRestoreAndWrongSafeOrSupervisorCannotValidateParent()throws Throwable {
        assertTrue(install());SafeOptions outerSafe=new SafeOptions(),innerSafe=new SafeOptions();
        recents(outerSafe,()->{
            WindowLauncherLaunch.Recent parent=access.recent.get();
            options(innerSafe,supervisor,null);options(outerSafe,new Supervisor(),null);assertFalse(parent.observed);
            recents(innerSafe,()->{assertNotSame(parent,access.recent.get());options(innerSafe,supervisor,null);return 2;});
            assertSame(parent,access.recent.get());assertFalse(parent.observed);
            ActivityOptions invalid=ActivityOptions.makeBasic();invalid.setLaunchBounds(new Rect(1,2,3,4));
            assertSame(invalid,options(outerSafe,supervisor,invalid));assertFalse(parent.allowed);
            options(outerSafe,supervisor,null);assertTrue(parent.observed);assertFalse(parent.allowed);return 2;
        });assertNull(access.recent.get());assertEquals(2,nativeCalls);
    }
    @Test public void nativeThrowableIdentityIsPreservedAndScopesClearOnErrors()throws Throwable {
        assertTrue(install());SafeOptions safe=new SafeOptions();Error original=new AssertionError("native private detail");
        try{recents(safe,()->{throw original;});fail();}catch(Throwable actual){assertSame(original,actual);}
        assertNull(access.recent.get());assertEquals(1,nativeCalls);
        RuntimeException getterError=new IllegalStateException("getOptions private detail");
        try{recents(safe,()->call("getOptions",safe,new Object[]{supervisor},()->{throw getterError;}));fail();}
        catch(Throwable actual){assertSame(getterError,actual);}
        assertNull(access.recent.get());assertEquals(2,nativeCalls);assertEquals(1,optionCalls);
        recents(safe,()->{
            ActivityOptions value=ActivityOptions.makeBasic();
            assertSame(value,call("getOptions",safe,new Object[]{},()->value)); // Observation fault after native return.
            assertFalse(access.recent.get().allowed);return 2;
        });
        assertEquals(3,nativeCalls);assertEquals(2,optionCalls);
        assertFalse(logs.toString().contains("private detail"));
        // A read-only candidate lookup fault must not suppress or retry native execution.
        set(engine,"root",new Object());failLog=true;Object result=new Object();
        assertSame(result,recents(safe,()->result));assertEquals(4,nativeCalls);assertNull(access.recent.get());
    }
    @Test public void optionalInstallFailureRollsBackOnlyItsHandlesAndDeoptFailureInstallsNone()throws Exception {
        failAt=2;assertFalse(install());assertEquals(2,deopts);assertEquals(1,unhooked);assertTrue(hooks.isEmpty());assertTrue(handles.isEmpty());
        failAt=-1;installed=0;deoptAllowed=false;assertFalse(install());assertEquals(0,installed);assertTrue(handles.isEmpty());
        assertTrue(logs.stream().anyMatch(s->s.contains("MINI_LAUNCH_NATIVE_UNAVAILABLE")));
    }
}
