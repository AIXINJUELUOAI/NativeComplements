package dev.local.sunwindowdemo.features.alive;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Small full-only whitelist check; no official binary, large fixture or native execution. */
public class AliveAssetsTest {
    @Test public void pinnedWhitelistContainsEveryFrameAndNoCodeOtherThanTheSingleLibrary()throws Exception{
        // Main-source tests also compile in window; avoid creating a dependency on full-only classes there.
        Class<?> type;
        try{type=Class.forName("dev.local.sunwindowdemo.features.alive.AliveAssets");}
        catch(ClassNotFoundException windowFlavor){return;}
        Method method=type.getDeclaredMethod("requiredFiles");method.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Long> files=(Map<String,Long>)method.invoke(null);
        assertEquals(252,files.size());
        assertEquals(29_539_884L,files.values().stream().mapToLong(Long::longValue).sum());
        String[] names={"leave_light","moon","door","mountain","galaxy"};int[] frames={61,32,41,43,56};
        for(int i=0;i<names.length;i++){
            String prefix="assets/masks/"+names[i]+"/";
            assertTrue(files.containsKey(prefix+"start.rgbz"));assertTrue(files.containsKey(prefix+"end.rgbz"));
            assertTrue(files.containsKey(prefix+"manifest.json"));
            for(int frame=0;frame<frames[i];frame++)assertTrue(files.containsKey(prefix+String.format(java.util.Locale.ROOT,"%03d.rgbz",frame)));
        }
        assertEquals(Long.valueOf(467592),files.get("lib/arm64-v8a/libalive_clean.so"));
        Method allowed=type.getDeclaredMethod("allowed",String.class);allowed.setAccessible(true);
        for(String bad:new String[]{"classes.dex","assets/../classes.dex","assets/masks/door/999.rgbz","lib/x86_64/libalive_clean.so","/assets/masks/door/start.rgbz"})
            assertFalse((Boolean)allowed.invoke(null,bad));
    }
}
