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
        Intent apk=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files.test/update.apk"),"application/vnd.android.package-archive");
        assertFalse(ShareWindow.eligibleIntent(apk,false));assertFalse(ShareWindow.eligibleIntent(apk,true));
        assertFalse(ShareWindow.eligibleIntent(new Intent("android.intent.action.INSTALL_PACKAGE"),false));
        assertTrue(ShareWindow.eligibleIntent(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files.test/document.pdf"),"application/pdf"),false));
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
    @Test public void systemInteractionsRequireNativeFullscreenButPackagePrefixesDoNot(){
        assertTrue(ShareWindow.nativeFullscreenIntent(new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION")));
        assertTrue(ShareWindow.nativeFullscreenIntent(new Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL")));
        assertTrue(ShareWindow.nativeFullscreenIntent(new Intent(Intent.ACTION_OPEN_DOCUMENT)));
        assertFalse(ShareWindow.nativeFullscreenIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)));
        assertFalse(ShareWindow.nativeFullscreenIntent(new Intent(Intent.ACTION_CHOOSER)));
        assertFalse(ShareWindow.nativeFullscreenIntent(new Intent("sample.app.PAY").setPackage("com.example.payment")));
        assertTrue(ShareWindow.eligibleIntent(new Intent("sample.app.PAY").setPackage("org.example.payment"),false));
        assertFalse(ShareWindow.eligibleIntent(new Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL"),false));
    }
    @Test public void earlyRoutingCopiesOptionsWithoutChangingTheOriginal()throws Exception{
        ActivityOptions original=ActivityOptions.makeBasic().setLaunchBounds(new Rect(10,20,110,220));
        Rect target=new Rect(0,0,1200,2133);ActivityOptions next=ShareWindow.launchOptions(original,target);
        assertNotSame(original,next);assertEquals(new Rect(10,20,110,220),original.getLaunchBounds());
        assertEquals(target,next.getLaunchBounds());target.setEmpty();assertFalse(next.getLaunchBounds().isEmpty());
        assertEquals(5,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(next));
        assertEquals(0,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(original));
    }
    @Test public void automaticSharingNeverOverridesAnExplicitTaskDestination()throws Exception{
        assertFalse(ShareWindow.explicitTask(null));
        ActivityOptions options=ActivityOptions.makeBasic();assertFalse(ShareWindow.explicitTask(options));
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(options,42);
        assertTrue(ShareWindow.explicitTask(options));
        assertEquals(42,ActivityOptions.class.getDeclaredMethod("getLaunchTaskId").invoke(options));
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(options,-1);
        assertFalse(ShareWindow.explicitTask(options));
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskFragmentToken",android.os.IBinder.class).invoke(options,new android.os.Binder());
        assertTrue(ShareWindow.explicitTask(options));
    }
    @Test public void installerUsesAnIndependentFullscreenContainerWithoutMutatingSourceOptionsOrIntent()throws Exception{
        Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files.test/update.apk"),"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("request",73);
        Intent before=new Intent(install);
        assertTrue(ShareWindow.installerIntent(install));
        assertTrue(ShareWindow.installerIntent(new Intent("android.intent.action.INSTALL_PACKAGE")));
        assertTrue(ShareWindow.installerIntent(new Intent("android.content.pm.action.CONFIRM_INSTALL")));
        assertFalse(ShareWindow.eligibleIntent(new Intent("android.content.pm.action.CONFIRM_INSTALL"),false));
        assertFalse(ShareWindow.installerIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.test"))));
        ActivityOptions original=ActivityOptions.makeBasic().setLaunchBounds(new Rect(10,20,110,220));
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(original,5);
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(original,42);
        ActivityOptions next=ShareWindow.installerOptions(original);
        assertNull(next.getLaunchBounds());
        assertEquals(1,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(next));
        assertEquals(-1,ActivityOptions.class.getDeclaredMethod("getLaunchTaskId").invoke(next));
        assertNull(ActivityOptions.class.getDeclaredMethod("getLaunchRootTask").invoke(next));
        assertNull(ActivityOptions.class.getDeclaredMethod("getLaunchTaskFragmentToken").invoke(next));
        assertEquals(5,ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode").invoke(original));
        assertEquals(42,ActivityOptions.class.getDeclaredMethod("getLaunchTaskId").invoke(original));
        assertEquals(new Rect(10,20,110,220),original.getLaunchBounds());
        assertTrue(before.filterEquals(install));assertEquals(before.getFlags(),install.getFlags());
        assertEquals(73,install.getIntExtra("request",0));
    }
}
