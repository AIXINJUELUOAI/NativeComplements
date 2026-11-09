package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ShareWindowTest {
    @Test public void sharesAndChooserContentAreEligibleButSystemActionsAreNot(){
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_SEND),false));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_SEND_MULTIPLE),false));
        Intent view=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.test/item"));
        assertTrue(ShareWindow.eligibleIntent(view,true));assertFalse(ShareWindow.eligibleIntent(view,false));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("content://example.test/item")),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:123")),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("tel:123")),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_MAIN),true));
        assertFalse(ShareWindow.eligibleIntent(null,true));
    }
    @Test public void earlyRoutingCopiesOptionsWithoutChangingTheOriginal()throws Exception{
        ActivityOptions original=ActivityOptions.makeBasic().setLaunchBounds(new Rect(10,20,110,220));
        Rect target=new Rect(0,0,1200,2133);ActivityOptions next=ShareWindow.launchOptions(original,target);
        assertNotSame(original,next);assertEquals(new Rect(10,20,110,220),original.getLaunchBounds());
        assertEquals(target,next.getLaunchBounds());target.setEmpty();assertFalse(next.getLaunchBounds().isEmpty());
        assertEquals(5,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(next));
        assertEquals(0,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(original));
    }
}
