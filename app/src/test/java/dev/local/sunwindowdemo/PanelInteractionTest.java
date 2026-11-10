package dev.local.sunwindowdemo;

import android.content.Context;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ListView;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class PanelInteractionTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private static Object field(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void invoke(Object object,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(object,args);
    }
    @SuppressWarnings("unchecked")
    @Test public void chosenTargetLeavesCandidatesAndReturnsToItsLetterWhenRemoved()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        AppCatalog.App beta=new AppCatalog.App("example.beta","Beta",new ColorDrawable(Color.BLUE));
        ShortcutTarget selected=ShortcutTarget.app(context,alpha);
        View panel=AppList.create(context,List.of(alpha,beta),List.of(selected),target->{},(targets,done)->done.accept(true),()->{});
        List<ShortcutTarget> candidates=(List<ShortcutTarget>)field(panel,"visible");
        assertEquals(List.of("example.beta"),candidates.stream().map(ShortcutTarget::key).toList());
        int[] sections=(int[])field(panel,"sectionPositions");assertEquals(-1,sections[0]);assertTrue(sections[1]>=1);
        invoke(panel,"toggle",new Class<?>[]{ShortcutTarget.class},selected);
        assertEquals(List.of("example.alpha","example.beta"),candidates.stream().map(ShortcutTarget::key).toList());
        assertTrue(sections[0]>=1);
        ListView list=(ListView)field(panel,"list");assertEquals(0,list.getPaddingRight());
    }
    @SuppressWarnings("unchecked")
    @Test public void thirdPartyShortcutsAreDiscardedAndDoneKeepsThePanel()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        View panel=AppList.create(context,List.of(alpha),List.of(ShortcutTarget.app(context,alpha)),target->{},(targets,done)->done.accept(true),()->{throw new AssertionError("unexpected close");});
        assertNull(((View)field(panel,"manage")).getBackground());
        assertNull(ShortcutTarget.fromKey(context,"shortcut:v1:0:example.alpha:scan",List.of(alpha)));
        invoke(panel,"toggleEditing",new Class<?>[]{});
        invoke(panel,"toggleEditing",new Class<?>[]{});
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse((Boolean)field(panel,"editing"));
        assertEquals(1,((List<?>)field(panel,"chosen")).size());
        assertNull(((View)field(panel,"manage")).getBackground());
    }
    @Test public void suggestionDefaultsRightButRetainsAnExplicitLeftChoice(){
        SuggestionStore.preferences(context).edit().clear().commit();
        assertTrue(SuggestionStore.read(context).getBoolean("right"));
        SuggestionStore.preferences(context).edit().putBoolean("right",false).commit();
        assertFalse(SuggestionStore.read(context).getBoolean("right"));
    }
    @Test public void suggestionDisplaySettingsPersistAndClamp(){
        SuggestionStore.preferences(context).edit().clear().commit();
        assertEquals(60,SuggestionStore.read(context).getInt("duration"));
        assertEquals(48,SuggestionStore.read(context).getInt("size"));
        SuggestionStore.setDuration(context,120);SuggestionStore.setSize(context,64);
        assertEquals(120,SuggestionStore.read(context).getInt("duration"));
        assertEquals(64,SuggestionStore.read(context).getInt("size"));
        SuggestionStore.setDuration(context,Integer.MAX_VALUE);SuggestionStore.setSize(context,0);
        assertEquals(300,SuggestionStore.read(context).getInt("duration"));
        assertEquals(32,SuggestionStore.read(context).getInt("size"));
        SuggestionStore.preferences(context).edit().putInt("duration",-1).putInt("size",999).commit();
        assertEquals(5,SuggestionStore.read(context).getInt("duration"));
        assertEquals(80,SuggestionStore.read(context).getInt("size"));
    }
    private static void touch(View view,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(0,0,action,x,y,0);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    @Test public void suggestionDragDismissesOnlyOnReleaseInsideXAndRestoresOnCancel()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            Activity activity=controller.get();ImageView button=new ImageView(activity);
            button.setImageDrawable(new ColorDrawable(Color.BLUE));button.setAlpha(.8f);button.setClickable(true);
            activity.setContentView(button);button.layout(0,0,80,80);
            int[] dismissed={0},clicked={0};button.setOnClickListener(v->clicked[0]++);
            SuggestionDrag drag=new SuggestionDrag(activity,activity.getSystemService(WindowManager.class));
            drag.attach(button,()->dismissed[0]++);
            try{
                touch(button,MotionEvent.ACTION_DOWN,20,20);assertTrue(button.performLongClick());
                assertEquals(0,button.getAlpha(),0);
                View layer=(View)field(drag,"layer");WindowManager.LayoutParams p=(WindowManager.LayoutParams)layer.getLayoutParams();
                assertTrue((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
                RectF x=new RectF((RectF)field(drag,"dismissBounds"));
                touch(button,MotionEvent.ACTION_MOVE,x.centerX(),x.centerY());
                touch(button,MotionEvent.ACTION_CANCEL,x.centerX(),x.centerY());
                assertEquals(0,dismissed[0]);assertEquals(.8f,button.getAlpha(),0);assertNull(field(drag,"layer"));
                touch(button,MotionEvent.ACTION_DOWN,20,20);assertTrue(button.performLongClick());
                touch(button,MotionEvent.ACTION_UP,0,0);assertEquals(0,dismissed[0]);
                touch(button,MotionEvent.ACTION_DOWN,20,20);assertTrue(button.performLongClick());
                touch(button,MotionEvent.ACTION_MOVE,x.centerX(),x.centerY());
                assertEquals(0,dismissed[0]);touch(button,MotionEvent.ACTION_UP,x.centerX(),x.centerY());
                assertEquals(1,dismissed[0]);assertEquals(0,clicked[0]);assertEquals(.8f,button.getAlpha(),0);
                assertNull(field(drag,"layer"));
                touch(button,MotionEvent.ACTION_DOWN,20,20);touch(button,MotionEvent.ACTION_UP,20,20);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertEquals(1,clicked[0]);
            }finally{drag.cancel();}
        }
    }
}
