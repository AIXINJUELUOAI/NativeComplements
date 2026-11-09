package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AtomicFile;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.List;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class LinkedWindowsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private WindowEngine engine;
    private Class<?> session;
    private AtomicFile journal;
    private String token;

    @Before public void setup()throws Exception{
        engine=new WindowEngine(null,message->{});
        session=Class.forName(WindowEngine.class.getName()+"$Session");
        journal=new AtomicFile(temporary.newFile("linked.json"));
        journal.delete();set(engine,"journal",journal);token=Wire.token();
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=WindowEngine.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(engine,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private Object create(int id,float font)throws Exception{
        Constructor<?> constructor=session.getDeclaredConstructor();constructor.setAccessible(true);
        Object value=constructor.newInstance();
        set(value,"task",new Object());set(value,"taskId",id);set(value,"userId",0);
        set(value,"pkg","com.example.app");set(value,"component","com.example.app/.Main");
        set(value,"token",token);set(value,"baseId","task-"+id);set(value,"crop",new Point(1200,2600));
        set(value,"mode",0);set(value,"topState",0);set(value,"bounds",new Rect());
        Configuration original=new Configuration();original.fontScale=font;set(value,"originalConfig",original);
        set(value,"placement",new Geometry.Placement(900,1600,.7f,20,80));
        return value;
    }
    private void save(Object value)throws Throwable{call("save",new Class<?>[]{session},value);}
    @SuppressWarnings("unchecked") private List<Object> read()throws Throwable{
        return (List<Object>)call("readJournal",new Class<?>[]{});
    }
    @Test public void keepsIndependentOriginalsForMainAndChildOfSameApp()throws Throwable{
        Object main=create(97,1.1f),child=create(98,1.3f);save(main);save(child);
        List<Object> records=read();assertEquals(2,records.size());
        assertEquals(97,get(records.get(0),"taskId"));assertEquals(98,get(records.get(1),"taskId"));
        assertEquals(1.1f,((Configuration)get(records.get(0),"originalConfig")).fontScale,0.001f);
        assertEquals(1.3f,((Configuration)get(records.get(1),"originalConfig")).fontScale,0.001f);
        assertEquals(token,get(records.get(1),"token"));
    }
    @Test public void parkedMainKeepsOwnershipAndCanvasWhileChildIsSelected()throws Throwable{
        Object main=create(97,1),child=create(98,1);save(main);save(child);
        set(engine,"active",child);set(main,"parked",true);
        assertTrue(engine.owns(get(main,"task")));assertTrue(engine.owns(get(child,"task")));
        assertFalse(engine.owns(new Object()));
        Rect bounds=new Rect(0,0,1200,2600);engine.bounds(get(main,"task"),bounds);
        assertEquals(new Rect(0,0,900,1600),bounds);
        set(main,"restoring",true);assertFalse(engine.owns(get(main,"task")));
    }
    @Test public void retiringChildDoesNotDiscardMainRecoveryRecord()throws Throwable{
        Object main=create(97,1),child=create(98,1);save(main);save(child);
        call("forget",new Class<?>[]{session},child);
        List<Object> records=read();assertEquals(1,records.size());assertEquals(97,get(records.get(0),"taskId"));
        assertTrue(engine.owns(get(main,"task")));assertFalse(engine.owns(get(child,"task")));
        call("forget",new Class<?>[]{session},main);assertFalse(journal.getBaseFile().exists());
    }
    @Test public void acceptsPreviousSingleTaskJournal()throws Throwable{
        JSONObject legacy=(JSONObject)call("encode",new Class<?>[]{session},create(96,1.2f));
        Files.writeString(journal.getBaseFile().toPath(),legacy.toString());
        List<Object> records=read();assertEquals(1,records.size());assertEquals(96,get(records.get(0),"taskId"));
    }
    @Test public void rejectsDuplicateEntriesAndKeepsIndependentSlotRecords()throws Throwable{
        JSONObject first=(JSONObject)call("encode",new Class<?>[]{session},create(97,1));
        JSONArray entries=new JSONArray().put(first).put(new JSONObject(first.toString()));
        Files.writeString(journal.getBaseFile().toPath(),new JSONObject().put("version",5).put("sessions",entries).toString());
        assertThrows(IOException.class,()->{try{read();}catch(Throwable error){if(error instanceof IOException io)throw io;throw new AssertionError(error);}});
        JSONObject foreign=(JSONObject)call("encode",new Class<?>[]{session},create(98,1));foreign.put("token",Wire.token());
        Files.writeString(journal.getBaseFile().toPath(),new JSONObject().put("version",5).put("sessions",new JSONArray().put(first).put(foreign)).toString());
        List<Object> restored=read();assertEquals(2,restored.size());
        assertNotEquals(get(restored.get(0),"token"),get(restored.get(1),"token"));
    }
    @Test public void routesEachSlotByTaskAndTokenAndKeepsOtherRecoveryRecord()throws Throwable{
        Object normal=create(97,1),mini=create(98,1);
        set(mini,"token",Wire.token());set(mini,"frame","mini");save(normal);save(mini);
        set(engine,"active",normal);set(engine,"pinned",mini);
        android.content.Intent request=new android.content.Intent().putExtra("taskId",98).putExtra("token",(String)get(mini,"token"));
        assertSame(mini,call("controlled",new Class<?>[]{android.content.Intent.class},request));
        request.putExtra("token",token);assertNull(call("controlled",new Class<?>[]{android.content.Intent.class},request));
        android.content.Intent report=new android.content.Intent();call("fillVisibleFrames",new Class<?>[]{android.content.Intent.class},report);
        assertTrue(report.getBooleanExtra("normalOccupied",false));
        assertEquals(98,report.getParcelableExtra("pinnedFrame",android.content.Intent.class).getIntExtra("taskId",-1));
        call("forget",new Class<?>[]{session},normal);
        assertEquals(98,get(read().get(0),"taskId"));assertTrue(engine.owns(get(mini,"task")));
    }
    @Test public void minimizingReplacesMiniWithoutExpandingDisplacedTask()throws Throwable{
        Object normal=create(97,1),mini=create(98,1);
        set(mini,"token",Wire.token());set(mini,"frame","mini");save(normal);save(mini);
        set(engine,"active",normal);set(engine,"pinned",mini);
        assertSame(mini,call("occupySlot",new Class<?>[]{session,String.class},normal,"mini"));
        assertSame(normal,get(engine,"active"));assertNull(get(engine,"pinned"));
        assertEquals("mini",get(mini,"frame"));assertEquals(2,read().size());
    }
    @Test public void expandingReplacesNormalWithoutMinimizingDisplacedTask()throws Throwable{
        Object normal=create(97,1),mini=create(98,1);
        set(normal,"frame","normal");set(mini,"frame","mini");
        set(engine,"active",normal);set(engine,"pinned",mini);
        assertSame(normal,call("occupySlot",new Class<?>[]{session,String.class},mini,"normal"));
        assertSame(mini,get(engine,"active"));assertNull(get(engine,"pinned"));
        assertEquals("normal",get(normal,"frame"));
    }
    @Test public void emptyDestinationKeepsOnlyTheRequestedWindow()throws Throwable{
        Object mini=create(97,1);set(mini,"frame","mini");set(engine,"active",mini);
        assertNull(call("occupySlot",new Class<?>[]{session,String.class},mini,"normal"));
        assertSame(mini,get(engine,"active"));assertNull(get(engine,"pinned"));
        assertNull(call("occupySlot",new Class<?>[]{session,String.class},mini,"mini"));
    }
    @Test public void entryKeepsTouchGuardAndRetainsFinalResizeCommit()throws Throwable{
        Object normal=create(97,1);save(normal);set(engine,"active",normal);set(normal,"entering",true);
        android.content.Intent sample=new android.content.Intent().putExtra("op","RESIZE").putExtra("taskId",97).putExtra("token",token).putExtra("scale",.5f);
        call("control",new Class<?>[]{android.content.Intent.class},sample);
        sample.putExtra("op","END_RESIZE").putExtra("scale",.28f);
        call("control",new Class<?>[]{android.content.Intent.class},sample);
        android.content.Intent pending=(android.content.Intent)get(normal,"entryControl");
        assertEquals("END_RESIZE",pending.getStringExtra("op"));assertEquals(.28f,pending.getFloatExtra("scale",0),.001f);
        android.content.Intent report=new android.content.Intent();call("fillFrame",new Class<?>[]{android.content.Intent.class,session},report,normal);
        assertTrue(report.getBooleanExtra("inputPending",false));assertFalse(report.getBooleanExtra("presented",true));
        set(normal,"entering",false);assertEquals(false,call("entryInputPending",new Class<?>[]{session},normal));
    }
    @Test public void mappedImeDoesNotResizeAgainWhenWindowAlreadyFitsAboveKeyboard()throws Throwable{
        Geometry.Placement p=new Geometry.Placement(1200,2133,.75f,150,55);
        Rect mapped=(Rect)call("logicalInsets",new Class<?>[]{Rect.class,Geometry.Placement.class},new Rect(0,1655,1200,2670),p);
        assertEquals(2133,mapped.top);
        assertFalse(Rect.intersects(mapped,new Rect(0,0,1200,2133)));
        Rect tallerKeyboard=(Rect)call("logicalInsets",new Class<?>[]{Rect.class,Geometry.Placement.class},new Rect(0,1400,1200,2670),p);
        assertTrue(Rect.intersects(tallerKeyboard,new Rect(0,0,1200,2133)));
    }
    @Test public void openFailureReportsOnceAndDoesNotBecomeSuccess()throws Throwable{
        java.util.ArrayList<Integer> results=new java.util.ArrayList<>();
        android.os.ResultReceiver receiver=new android.os.ResultReceiver(null){
            @Override protected void onReceiveResult(int code,android.os.Bundle data){results.add(code);}
        };
        android.content.Intent request=new android.content.Intent().putExtra("openReply",receiver);
        call("completeOpen",new Class<?>[]{android.content.Intent.class,boolean.class},request,false);
        call("completeOpen",new Class<?>[]{android.content.Intent.class,boolean.class},request,true);
        assertEquals(List.of(0),results);
    }
}
