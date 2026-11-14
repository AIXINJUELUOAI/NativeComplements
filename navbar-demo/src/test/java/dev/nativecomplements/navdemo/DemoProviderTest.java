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
        for (String method : new String[]{"config","sample","control","request","latest","annotate","clear"}) {
            ShadowBinder.setCallingUid(54321);
            assertThrows(SecurityException.class, () -> provider.call(method,null,new Bundle()));
        }
        for (String method : new String[]{"control","request","latest","annotate","clear"}) {
            ShadowBinder.setCallingUid(1000);
            assertThrows(SecurityException.class, () -> provider.call(method,null,new Bundle()));
        }
        owner(); assertThrows(SecurityException.class, () -> provider.call("sample",null,new Bundle()));
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
