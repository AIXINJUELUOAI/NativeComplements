package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.ListView;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
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
    @Test public void selectingAnAppDoesNotHideItsDistinctShortcuts()throws Exception{
        AppCatalog.App alpha=new AppCatalog.App("example.alpha","Alpha",new ColorDrawable(Color.RED));
        View panel=AppList.create(context,List.of(alpha),List.of(ShortcutTarget.app(context,alpha)),target->{},(targets,done)->{},()->{});
        ShortcutTarget shortcut=new ShortcutTarget(alpha.pkg,"scan",android.os.Process.myUserHandle(),0,"Scan","Alpha",alpha.icon,"A");
        ((List<ShortcutTarget>)field(panel,"shortcuts")).add(shortcut);
        invoke(panel,"setTab",new Class<?>[]{int.class},1);
        List<ShortcutTarget> candidates=(List<ShortcutTarget>)field(panel,"visible");
        assertEquals(1,candidates.size());assertEquals(shortcut.key(),candidates.get(0).key());
    }
    @Test public void suggestionDefaultsRightButRetainsAnExplicitLeftChoice(){
        SuggestionStore.preferences(context).edit().clear().commit();
        assertTrue(SuggestionStore.read(context).getBoolean("right"));
        SuggestionStore.preferences(context).edit().putBoolean("right",false).commit();
        assertFalse(SuggestionStore.read(context).getBoolean("right"));
    }
}
