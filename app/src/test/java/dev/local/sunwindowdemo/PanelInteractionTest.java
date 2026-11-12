package dev.local.sunwindowdemo;

import android.content.Context;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
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
    @Test public void quickTabIsAlwaysVisibleAndBackReturnsToApplicationsWithoutLosingEdits()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        View menu=AppList.create(context,List.of(alpha),List.of(ShortcutTarget.app(context,alpha)),target->{},
            (targets,done)->done.accept(true),List.of(),id->{});
        View quick=(View)field(menu,"quick");
        View apps=(View)field(menu,"apps");
        assertEquals(View.VISIBLE,apps.getVisibility());assertEquals(View.GONE,quick.getVisibility());
        assertEquals(View.VISIBLE,((View)field(menu,"quickTab")).getVisibility());
        assertFalse((Boolean)field(apps,"editing"));
        ((View)field(menu,"quickTab")).performClick();
        assertEquals(View.GONE,apps.getVisibility());assertEquals(View.VISIBLE,quick.getVisibility());
        assertTrue(AppList.back(menu));assertEquals(View.VISIBLE,apps.getVisibility());assertEquals(View.GONE,quick.getVisibility());
        assertFalse(AppList.back(menu));assertEquals(1,((List<?>)field(apps,"chosen")).size());
        invoke(apps,"toggleEditing",new Class<?>[]{});
        ((View)field(menu,"quickTab")).performClick();((View)field(menu,"appTab")).performClick();
        assertTrue((Boolean)field(apps,"editing"));
        AppList.blur(menu,true);assertNotNull(menu.getBackground());
    }
    @SuppressWarnings("unchecked")
    @Test public void iconReloadPreservesUnsavedOrderAndCurrentQuickTab()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        AppCatalog.App beta=new AppCatalog.App("example.beta","Beta",new ColorDrawable(Color.BLUE));
        View menu=AppList.create(context,List.of(alpha,beta),List.of(ShortcutTarget.app(context,alpha)),target->{},(targets,done)->done.accept(true),List.of(),id->{});
        View quick=(View)field(menu,"quick");
        Object panel=field(menu,"apps");invoke(panel,"toggleEditing",new Class<?>[]{});
        invoke(panel,"toggle",new Class<?>[]{ShortcutTarget.class},ShortcutTarget.app(context,beta));
        ((View)field(menu,"quickTab")).performClick();
        AppCatalog.App updated=new AppCatalog.App(alpha.pkg,alpha.label,new ColorDrawable(Color.GREEN));
        AppList.updateApps(menu,List.of(updated,beta));
        assertTrue((Boolean)field(panel,"editing"));assertEquals(View.VISIBLE,quick.getVisibility());
        List<ShortcutTarget> chosen=(List<ShortcutTarget>)field(panel,"chosen");
        assertEquals(List.of(alpha.pkg,beta.pkg),chosen.stream().map(t->t.pkg).toList());
        android.widget.LinearLayout rows=(android.widget.LinearLayout)field(panel,"selectedRows");
        View cell=((android.view.ViewGroup)rows.getChildAt(0)).getChildAt(0);
        assertEquals(Color.GREEN,((ColorDrawable)((ImageView)field(cell,"image")).getDrawable()).getColor());
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
    @Test public void mixedSelectionCapsAtTwelveAndThreeRows() throws Exception {
        List<AppCatalog.App> apps=new ArrayList<>();
        List<ShortcutTarget> selected=new ArrayList<>();
        for(int i=0;i<13;i++){
            AppCatalog.App app=new AppCatalog.App("example.app"+i,"App "+i,new ColorDrawable(Color.rgb(i*10,20,30)));
            apps.add(app);selected.add(ShortcutTarget.app(context,app));
        }
        View menu=AppList.create(context,apps,selected,target->{},(targets,done)->done.accept(true),List.of(),id->{});
        Object panel=field(menu,"apps");
        assertEquals(12,((List<?>)field(panel,"chosen")).size());
        android.widget.LinearLayout rows=(android.widget.LinearLayout)field(panel,"selectedRows");
        assertEquals(3,rows.getChildCount());
        assertEquals(4,((ViewGroup)rows.getChildAt(0)).getChildCount());
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
    @Test public void pageUsesLargeIconsWithoutCardOrHeaderAndSavesFromToolbar()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        AppCatalog.App beta=new AppCatalog.App("example.beta","Beta",new ColorDrawable(Color.BLUE));
        ShortcutTarget first=ShortcutTarget.app(context,alpha),second=ShortcutTarget.app(context,beta);
        List<String> editing=new ArrayList<>();
        View page=AppList.createPage(context,List.of(alpha,beta),List.of(first,second),target->{},
            (targets,done)->{new dev.local.sunwindowdemo.Config(context).targets(targets.stream().map(ShortcutTarget::key).toList());done.accept(true);},
            new int[]{0xffeeeeee,0xff111111,0xff555555,0xff336699},(edit,saving)->editing.add(edit+":"+saving));
        ListView list=(ListView)field(page,"list");assertTrue(list.isNestedScrollingEnabled());
        assertNull(page.getBackground());assertNull(((View)field(page,"manage")).getParent());
        assertFalse(page.getClipToOutline());assertEquals(0,page.getPaddingTop());assertEquals(0,page.getPaddingBottom());
        assertFalse(list.isVerticalFadingEdgeEnabled());
        View tile=((android.widget.LinearLayout)((android.widget.LinearLayout)field(page,"selectedRows")).getChildAt(0)).getChildAt(0);
        ImageView image=(ImageView)field(tile,"image");
        assertEquals(Math.round(52*context.getResources().getDisplayMetrics().density),image.getLayoutParams().width);
        AppList.bottomInset(page,100);assertTrue(list.getPaddingBottom()>=100);
        AppList.edit(page);
        invoke(page,"toggle",new Class<?>[]{ShortcutTarget.class},first);
        invoke(page,"toggle",new Class<?>[]{ShortcutTarget.class},first);
        AppList.edit(page);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(List.of("example.beta","example.alpha"),new dev.local.sunwindowdemo.Config(context).targets());
        assertFalse((Boolean)field(page,"editing"));
        assertTrue(editing.contains("true:false"));assertTrue(editing.contains("true:true"));
        assertEquals("false:false",editing.get(editing.size()-1));
        AppList.blur(page,true);
        assertNull(page.getBackground());
        AppList.blur(page,false);
        assertNull(page.getBackground());
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
    @Test public void recoveryWaitCancelsByExactTokenAndIgnoresLateFrames()throws Exception{
        var constructor=SystemUiHost.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        SystemUiHost host=constructor.newInstance(context);
        org.robolectric.util.ReflectionHelpers.setField(host,"settingsReady",true);
        org.robolectric.util.ReflectionHelpers.setField(host,"config",new dev.local.sunwindowdemo.Config(context));
        org.robolectric.util.ReflectionHelpers.setField(host,"wm",context.getSystemService(WindowManager.class));
        String token=Wire.token();android.content.Intent waiting=new android.content.Intent().putExtra("waitingOpen",true).putExtra("waitingToken",token).putExtra("waitingPackage","example.alpha");
        try{
            invoke(host,"updateOpenWait",new Class<?>[]{android.content.Intent.class},waiting);
            View overlay=(View)field(host,"openWait");assertNotNull(overlay);
            invoke(host,"updateOpenWait",new Class<?>[]{android.content.Intent.class},waiting);assertSame(overlay,field(host,"openWait"));
            overlay.performClick();assertNull(field(host,"openWait"));
            var sent=org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
            android.content.Intent cancel=sent.stream().filter(i->"CANCEL_OPEN".equals(i.getStringExtra("op"))).reduce((a,b)->b).orElseThrow();
            assertEquals(token,cancel.getStringExtra("token"));assertEquals("example.alpha",cancel.getStringExtra("package"));
            invoke(host,"updateOpenWait",new Class<?>[]{android.content.Intent.class},waiting);assertNull(field(host,"openWait"));
            waiting.putExtra("waitingToken",Wire.token());invoke(host,"updateOpenWait",new Class<?>[]{android.content.Intent.class},waiting);assertNotNull(field(host,"openWait"));
            invoke(host,"updateOpenWait",new Class<?>[]{android.content.Intent.class},new android.content.Intent().putExtra("waitingOpen",false));
            assertNull(field(host,"openWait"));
        }finally{
            invoke(host,"hideOpenWait",new Class<?>[]{});
            ((java.util.concurrent.ExecutorService)field(host,"settingsWorker")).shutdownNow();
        }
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
