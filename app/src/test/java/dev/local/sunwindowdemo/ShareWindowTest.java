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
    @Test public void ordinaryCrossAppIntentsAreEligibleButSystemInteractionsAreNot(){
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_SEND),false));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_SEND_MULTIPLE),false));
        Intent view=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.test/item"));
        assertTrue(ShareWindow.eligibleIntent(view,true));assertTrue(ShareWindow.eligibleIntent(view,false));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("content://example.test/item")),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:123")),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("tel:123")),true));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),false));
        assertTrue(ShareWindow.eligibleIntent(new Intent("sample.app.OPEN_FILE").putExtra("caller",42),false));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),false));
        assertFalse(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_CHOOSER),true));
        assertFalse(ShareWindow.eligibleIntent(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS"),false));
        assertFalse(ShareWindow.eligibleIntent(new Intent("android.content.pm.action.REQUEST_PERMISSIONS"),false));
        for(String picker:new String[]{Intent.ACTION_OPEN_DOCUMENT,Intent.ACTION_OPEN_DOCUMENT_TREE,
            Intent.ACTION_CREATE_DOCUMENT,Intent.ACTION_GET_CONTENT,Intent.ACTION_PICK_ACTIVITY})
            assertFalse(ShareWindow.eligibleIntent(new Intent(picker),false));
        assertFalse(ShareWindow.eligibleIntent(null,true));
        Intent content=new Intent(Intent.ACTION_VIEW,Uri.parse("content://sample/doc/1"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("original",73);
        Intent saved=new Intent(content);assertTrue(ShareWindow.eligibleIntent(content,false));
        assertTrue(content.filterEquals(saved));assertEquals(saved.getFlags(),content.getFlags());
        assertEquals(73,content.getIntExtra("original",0));
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
