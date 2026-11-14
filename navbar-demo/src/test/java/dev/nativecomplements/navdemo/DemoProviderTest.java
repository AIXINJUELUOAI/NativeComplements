package dev.nativecomplements.navdemo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36, manifest=Config.NONE)
public class DemoProviderTest {
    private DemoProvider provider;
    private void owner() { ShadowBinder.setCallingUid(RuntimeEnvironment.getApplication().getApplicationInfo().uid); }
    @Before public void setup() throws Exception {
        provider = new DemoProvider(); ProviderInfo info = new ProviderInfo(); info.authority="dev.nativecomplements.navdemo.data";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info); owner(); DemoStore.clear(RuntimeEnvironment.getApplication());
    }
    @After public void reset() { ShadowBinder.setCallingUid(android.os.Process.myUid()); }
    @Test(expected=SecurityException.class) public void foreignCannotRead() { ShadowBinder.setCallingUid(54321); provider.call("latest",null,null); }
    @Test(expected=SecurityException.class) public void systemCannotControl() { ShadowBinder.setCallingUid(1000); provider.call("control",null,new Bundle()); }
    @Test public void uidMatrixRejectsForeignControlAndSystemReads() {
        for (String method : new String[]{"config","sample","control","request","latest","annotate","clear","screenshot","screenshot_status"}) {
            ShadowBinder.setCallingUid(54321);
            assertThrows(SecurityException.class, () -> provider.call(method,null,new Bundle()));
        }
        for (String method : new String[]{"control","request","latest","annotate","clear","screenshot_status"}) {
            ShadowBinder.setCallingUid(1000);
            assertThrows(SecurityException.class, () -> provider.call(method,null,new Bundle()));
        }
        owner(); assertThrows(SecurityException.class, () -> provider.call("sample",null,new Bundle()));
    }
    private Bundle requestImage() {
        owner(); Bundle enable=new Bundle(); enable.putBoolean("enabled",true); provider.call("control",null,enable);
        Bundle sample=new Bundle(); sample.putLong("id",22); sample.putLong("generation",provider.call("config",null,null).getLong("generation"));
        sample.putLong("capturedAt",android.os.SystemClock.elapsedRealtime()); sample.putBoolean("valid",true); sample.putString("component","test/.Page"); sample.putString("decision","EXTEND");
        ShadowBinder.setCallingUid(1000); provider.call("sample",null,sample); owner();
        Bundle args=new Bundle(); args.putBundle("sample",sample); args.putString("expected","UNKNOWN");
        assertFalse(provider.call("annotate",null,args).containsKey("error"));
        return provider.call("config",null,null).getBundle("pending");
    }
    @Test public void pendingRejectsUnsolicitedStaleAndExpiredScreenshots() {
        Bundle request=requestImage(), result=new Bundle(); result.putString("token","wrong"); result.putLong("generation",request.getLong("generation"));
        owner(); assertThrows(SecurityException.class, () -> provider.call("screenshot",null,result));
        ShadowBinder.setCallingUid(1000); assertTrue(provider.call("screenshot",null,result).containsKey("error"));
        result.putString("token",request.getString("token"));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11));
        assertTrue(provider.call("screenshot",null,result).containsKey("error"));
        owner(); assertEquals("failed",provider.call("screenshot_status",null,null).getString("status"));
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void requestedScreenshotSavesOnceAndClearRevokesToken() throws Exception {
        Bundle pending=requestImage(); Bundle result=new Bundle(); result.putString("token",pending.getString("token")); result.putLong("generation",pending.getLong("generation"));
        result.putString("status","saved"); result.putBoolean("contentMatched",true); result.putLong("screenshotAt",android.os.SystemClock.elapsedRealtime());
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(12,20,android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,bytes); bitmap.recycle();
        result.putByteArray("bytes",bytes.toByteArray());
        ShadowBinder.setCallingUid(1000); Bundle accepted=provider.call("screenshot",null,result);
        assertEquals("saved",accepted.getString("status"));
        assertTrue(provider.call("screenshot",null,result).containsKey("error"));
        owner(); assertFalse(provider.call("config",null,null).containsKey("pending"));
        java.io.ByteArrayOutputStream logs=new java.io.ByteArrayOutputStream(); DemoStore.export(RuntimeEnvironment.getApplication(),logs);
        assertTrue(logs.toString(java.nio.charset.StandardCharsets.UTF_8).contains("bottomStripMatched"));
        assertTrue(logs.toString(java.nio.charset.StandardCharsets.UTF_8).contains("unverified"));
        pending=requestImage(); result.putString("token",pending.getString("token")); result.putLong("generation",pending.getLong("generation"));
        provider.call("clear",null,null); ShadowBinder.setCallingUid(1000);
        assertTrue(provider.call("screenshot",null,result).containsKey("error"));
    }
    @Test public void pendingIsNotScoredAndImageZipKeepsMetadataAfterLogRotation() throws Exception {
        Bundle request=requestImage();
        java.io.ByteArrayOutputStream raw=new java.io.ByteArrayOutputStream();
        DemoStore.export(RuntimeEnvironment.getApplication(),raw);
        for (String line:raw.toString(java.nio.charset.StandardCharsets.UTF_8).split("\\n")) {
            org.json.JSONObject json=new org.json.JSONObject(line);
            if ("annotation".equals(json.getString("type"))) assertFalse(json.has("matches"));
        }
        Bundle metadata=request.getBundle("sample"); metadata.putString("screenshotToken",request.getString("token"));
        DemoStore.saveScreenshot(RuntimeEnvironment.getApplication(),request.getString("token"),new byte[]{1,2,3},metadata);
        java.io.ByteArrayOutputStream zipped=new java.io.ByteArrayOutputStream(); DemoStore.exportZip(RuntimeEnvironment.getApplication(),zipped);
        java.util.Set<String> names=new java.util.HashSet<>();
        try (java.util.zip.ZipInputStream input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipped.toByteArray()))) {
            java.util.zip.ZipEntry entry; while ((entry=input.getNextEntry())!=null) { names.add(entry.getName()); input.readAllBytes(); }
        }
        assertTrue(names.contains("observations.ndjson")); assertTrue(names.contains("pending/"+request.getString("token")+".jpg"));
        assertTrue(names.contains("pending/"+request.getString("token")+".json"));
        DemoStore.clear(RuntimeEnvironment.getApplication());
        assertEquals(0,new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(),"pending-images").listFiles().length);
    }
    @Test public void expiredLeaseAndOldGenerationAreRejected() {
        Bundle control=new Bundle(); control.putBoolean("enabled",true); provider.call("control",null,control);
        long generation=provider.call("config",null,null).getLong("generation");
        Bundle sample=new Bundle(); sample.putLong("id",1); sample.putLong("generation",generation); sample.putBoolean("valid",true);
        ShadowBinder.setCallingUid(1000); provider.call("sample",null,sample); owner();
        Bundle annotation=new Bundle(); annotation.putBundle("sample",sample); annotation.putString("expected","UNKNOWN");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(16));
        assertFalse(provider.call("config",null,null).getBoolean("enabled"));
        assertTrue(provider.call("annotate",null,annotation).containsKey("error"));
        provider.call("control",null,control);
        ShadowBinder.setCallingUid(1000); assertTrue(provider.call("sample",null,sample).containsKey("error"));
        owner(); assertFalse(provider.call("latest",null,null).containsKey("id"));
    }
    @Test public void reviewDiagnosticsSurviveStoredIdentityAndExport() throws Exception {
        Bundle enable=new Bundle(); enable.putBoolean("enabled",true); provider.call("control",null,enable);
        Bundle sample=new Bundle(); sample.putLong("id",91); sample.putLong("generation",provider.call("config",null,null).getLong("generation"));
        sample.putBoolean("valid",true); sample.putString("component","test/.Page"); sample.putString("decision","NATIVE");
        sample.putString("initialDecision","EXTEND"); sample.putString("reviewTrigger","near_threshold");
        sample.putString("reviewStatus","fresh"); sample.putString("reviewDecision","NATIVE"); sample.putString("reviewReason","repeated_structure");
        sample.putString("structureAlgorithm","edge-layout-v1"); sample.putString("reviewCrop","surface=Rect");
        sample.putDouble("structureScore",0.88); sample.putDouble("structureTexture",0.12);
        sample.putInt("reviewHeightDp",320); sample.putInt("structureOffsetDp",100); sample.putLong("reviewAt",100); sample.putLong("reviewMs",15); sample.putLong("reviewCacheAgeMs",0);
        sample.putByteArray("structurePixels",new byte[]{1});
        ShadowBinder.setCallingUid(1000); provider.call("sample",null,sample); owner();
        Bundle latest=provider.call("latest",null,null);
        assertEquals("EXTEND",latest.getString("initialDecision")); assertEquals(0.88,latest.getDouble("structureScore"),0.0001);
        assertEquals(320,latest.getInt("reviewHeightDp")); assertEquals(15,latest.getLong("reviewMs"));
        assertFalse(latest.containsKey("structurePixels"));
        Bundle annotation=new Bundle(); annotation.putBundle("sample",latest); annotation.putString("expected","NATIVE");
        assertFalse(provider.call("annotate",null,annotation).containsKey("error"));
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); DemoStore.export(RuntimeEnvironment.getApplication(),out);
        String text=out.toString(java.nio.charset.StandardCharsets.UTF_8); assertTrue(text.contains("repeated_structure")); assertFalse(text.contains("structurePixels"));
    }
    @Test public void sampleAnnotationUsesStoredIdentityAndExportsNoPixels() throws Exception {
        Bundle control=new Bundle(); control.putBoolean("enabled",true); provider.call("control",null,control);
        Bundle sample=new Bundle(); sample.putLong("id",17); sample.putLong("generation",provider.call("config",null,null).getLong("generation")); sample.putBoolean("valid",true); sample.putString("component","sample/.Page"); sample.putString("decision","EXTEND"); sample.putByteArray("pixels", new byte[]{1,2});
        ShadowBinder.setCallingUid(1000); provider.call("sample",null,sample);
        owner(); Bundle shown=provider.call("latest",null,null); assertFalse(shown.containsKey("pixels"));
        shown.putString("component","forged/.Page"); Bundle annotation=new Bundle(); annotation.putBundle("sample",shown); annotation.putString("expected","NATIVE");
        assertFalse(provider.call("annotate",null,annotation).containsKey("error"));
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); DemoStore.export(RuntimeEnvironment.getApplication(),out);
        String text=out.toString(java.nio.charset.StandardCharsets.UTF_8); assertTrue(text.contains("annotation")); assertFalse(text.contains("forged")); assertFalse(text.contains("pixels"));
        provider.call("control",null,new Bundle()); assertFalse(provider.call("config",null,null).getBoolean("enabled"));
        assertTrue(provider.call("annotate",null,annotation).containsKey("error"));
    }
}
