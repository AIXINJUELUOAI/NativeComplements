package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.os.Binder;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Executes the real Engine adapter; native selector behavior remains deliberately unchanged. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class WindowMiniResumeDiagnosticTest {
    static final Object LOCK=new Object();
    public static final class Display { public Object mFocusedApp; }
    public static final class Task {
        Top top;final Display display=new Display();int focusWrites;
        public Top topRunningActivity(){return top;}
        public boolean isFocusable(){return false;}
        public void setFocusable(boolean ignored){focusWrites++;}
        public Display getDisplayContent(){return display;}
    }
    public static final class Fragment {
        final Task task;Top top;boolean sleeping,diagnosticFailure;int visibility,nativeCalls,selectorCalls;
        Fragment(Task task){this.task=task;}
        public Task getTask(){return task;}
        public Top topRunningActivity(){return top;}
        public Top topRunningActivity(boolean focusableOnly){
            selectorCalls++;if(diagnosticFailure)throw new IllegalStateException("diagnostic selector unavailable");
            return focusableOnly&&!top.focusable?null:top;
        }
        public boolean shouldSleepOrShutDownActivities(){return sleeping;}
        public int getVisibility(Object resuming){return visibility;}
        public boolean resumeTopActivity(Object previous,ActivityOptions options,boolean skipPause){
            nativeCalls++;assertFalse(skipPause);assertNull(previous);assertNull(options);
            // Model the target's native first gate, not a replacement policy in the adapter.
            if(!top.focusable)return false;
            top.state="RESUMED";return true;
        }
    }
    public static final class Top {
        final Task task;final Fragment fragment;
        public boolean finishing;boolean shown=true,attached=true,makeActive=true,compat=true,focusable,canBeTop=true,makeFailure;
        String state="PAUSED";
        Top(Task task,Fragment fragment){this.task=task;this.fragment=fragment;}
        public Task getTask(){return task;}
        public Fragment getTaskFragment(){return fragment;}
        public String getState(){return state;}
        public boolean isVisibleRequested(){return shown;}
        public boolean attachedToProcess(){return attached;}
        public boolean shouldMakeActive(Object resuming){if(makeFailure)throw new IllegalStateException("native predicate unavailable");return makeActive;}
        public boolean canResumeByCompat(){return compat;}
        public boolean isFocusable(){return focusable;}
        public boolean canBeTopRunning(){return canBeTop;}
    }
    Task task;Fragment fragment;Top top;WindowEngine engine;WindowEngine.Session session;List<String> logs;
    private static void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static FrameworkAccess access()throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        FrameworkAccess result=(FrameworkAccess)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),FrameworkAccess.class);
        set(result,"methods",new java.util.concurrent.ConcurrentHashMap<>());set(result,"fields",new java.util.concurrent.ConcurrentHashMap<>());
        set(result,"activity",Object.class);return result;
    }
    @Before public void setup()throws Exception{
        task=new Task();fragment=new Fragment(task);top=new Top(task,fragment);task.top=top;fragment.top=top;logs=new ArrayList<>();
        engine=new WindowEngine(access(),logs::add);session=new WindowEngine.Session();session.task=task;session.taskId=123;
        session.frame="mini";session.edgeHidden=true;session.lease=new Binder();
        set(engine,"lock",LOCK);set(engine,"context",RuntimeEnvironment.getApplication());set(engine,"sessions",List.of(session));
        set(engine,"active",session);set(engine,"visibleResumeReady",true);
    }
    private void resume()throws Exception{
        Method method=WindowEngine.class.getDeclaredMethod("resumeMini",WindowEngine.Session.class);method.setAccessible(true);
        synchronized(LOCK){method.invoke(engine,session);}
    }
    private List<String> traces(){return logs.stream().filter(message->message.startsWith("MINI_RESUME ")).toList();}
    private boolean ready()throws Exception{Field field=WindowEngine.class.getDeclaredField("visibleResumeReady");field.setAccessible(true);return field.getBoolean(engine);}
    @Test public void nativeFalseSelectorAndPausedStateAreLoggedWithoutMakingTaskFocusable()throws Exception{
        resume();assertEquals(1,fragment.nativeCalls);assertEquals("PAUSED",top.state);assertEquals(1,traces().size());
        String trace=traces().get(0);assertTrue(trace.contains("task=123"));assertTrue(trace.contains("stage=native-false"));
        assertTrue(trace.contains("state=PAUSED"));assertTrue(trace.contains("selected=false"));assertTrue(trace.contains("focusable=false"));
        assertTrue(trace.contains("canBeTop=true"));assertEquals(0,task.focusWrites);assertTrue(ready());
        resume();assertEquals(2,fragment.nativeCalls);assertEquals(1,traces().size());
        top.state="RESUMED";resume();assertNull(session.lastMiniResumeState);assertEquals(2,fragment.nativeCalls);
        top.state="PAUSED";resume();assertEquals(2,traces().size());assertEquals(traces().get(0),traces().get(1));
        top.canBeTop=false;resume();assertEquals(3,traces().size());assertTrue(traces().get(2).contains("canBeTop=false"));
    }
    @Test public void nativeTrueIsDiagnosticOnlyAndKeepsNativeResult()throws Exception{
        top.focusable=true;resume();assertEquals(1,fragment.nativeCalls);assertEquals("RESUMED",top.state);
        assertTrue(traces().get(0).contains("stage=native-true"));assertTrue(traces().get(0).contains("selected=true"));
        assertTrue(traces().get(0).contains("focusable=true"));assertEquals(0,task.focusWrites);
    }
    @Test public void retainMakeActiveCompatAndUnavailableStillPreventNativeExecution()throws Exception{
        fragment.sleeping=true;resume();assertTrue(traces().get(0).contains("stage=retain"));
        fragment.sleeping=false;top.makeActive=false;resume();assertTrue(traces().get(1).contains("stage=make-active"));
        top.makeActive=true;top.compat=false;resume();assertTrue(traces().get(2).contains("stage=compat"));
        top.compat=true;top.makeFailure=true;resume();assertTrue(traces().get(3).contains("stage=unavailable"));
        assertEquals(0,fragment.nativeCalls);assertEquals("PAUSED",top.state);assertTrue(ready());assertEquals(0,task.focusWrites);
        top.makeFailure=false;assertOriginalRetainGates();
    }
    @Test public void diagnosticReadFailureDoesNotPreventNativeOrDisableFutureAttempts()throws Exception{
        fragment.diagnosticFailure=true;resume();assertEquals(1,fragment.nativeCalls);assertEquals("PAUSED",top.state);assertTrue(ready());
        assertTrue(traces().get(0).contains("stage=native-false"));assertTrue(traces().get(0).contains("detail=unavailable"));
        resume();assertEquals(2,fragment.nativeCalls);assertTrue(ready());assertEquals(1,traces().size());
        assertFalse(logs.stream().anyMatch(message->message.startsWith("MINI_RESUME_UNAVAILABLE")));
        fragment.diagnosticFailure=false;resume();assertEquals(3,fragment.nativeCalls);assertEquals(2,traces().size());assertEquals(0,task.focusWrites);
    }
    private void assertOriginalRetainGates(){
        for(int gate=0;gate<9;gate++){
            session.restoring=gate==0;session.closing=gate==1;session.fullTransition=gate==2;session.parked=gate==3;
            top.finishing=gate==4;top.shown=gate!=5;top.attached=gate!=6;fragment.visibility=gate==7?1:0;
            session.prioritySuspended=gate==8;
            assertFalse(engine.shouldResumeMiniActivity(top,null));
        }
        assertEquals(0,fragment.nativeCalls);assertEquals(4,traces().size());assertEquals(0,task.focusWrites);
    }
}
