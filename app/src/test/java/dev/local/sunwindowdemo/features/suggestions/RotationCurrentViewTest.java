package dev.local.sunwindowdemo;

import android.view.View;
import org.junit.Test;
import static org.junit.Assert.*;

public class RotationCurrentViewTest {
    public interface Button {default View getCurrentView(){return null;}}
    public static class Concrete implements Button {}
    public static class Unknown {public Object getCurrentView(){return new Object();}}
    public static class StaticButton {public static View getCurrentView(){return null;}}
    @Test public void interfaceDefaultViewWorksWithoutConcreteFieldGuessing()throws Exception{
        assertEquals(Button.class,RotationSuggestion.currentView(Button.class).getDeclaringClass());
        assertNull(RotationSuggestion.currentView(Concrete.class).invoke(new Concrete()));
        assertThrows(NoSuchMethodException.class,()->RotationSuggestion.currentView(Unknown.class));
        assertThrows(NoSuchMethodException.class,()->RotationSuggestion.currentView(StaticButton.class));
    }
}
