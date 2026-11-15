package dev.local.sunwindowdemo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModuleIdentityTest {
    @Test public void fullIdentityPreservesEveryInstalledName(){
        ModuleIdentity identity=ModuleIdentity.forPackage("dev.nativecomplements.module");
        assertFalse(identity.standalone());
        assertEquals("dev.nativecomplements.module",identity.packageName());
        assertEquals("dev.nativecomplements.module.COMMAND",identity.action("COMMAND"));
        assertEquals("dev.nativecomplements.module.GUARD_STATE",identity.action("GUARD_STATE"));
        assertEquals("dev.nativecomplements.module.CONTROL",identity.controlPermission());
        assertEquals("dev.nativecomplements.module.settings",identity.authority("settings"));
        assertEquals("dev.nativecomplements.module.iconlayers",identity.authority("iconlayers"));
        assertEquals("nativecomplements_corner",identity.hostPreferences());
        assertEquals("nativecomplements",identity.hostStorageName());
        for(String name:new String[]{"sunwindow-demo5.json","nativecomplements-window-orientation.json","sunwindow-hidden-tasks.json"})
            assertEquals("/data/system/"+name,identity.systemFile(name).getPath().replace('\\','/'));
    }
    @Test public void windowIdentitySeparatesMessagesPreferencesAndRecoveryFiles(){
        ModuleIdentity identity=ModuleIdentity.forPackage("dev.nativecomplements.window");
        assertTrue(identity.standalone());
        assertEquals("dev.nativecomplements.window.COMMAND",identity.action("COMMAND"));
        assertEquals("dev.nativecomplements.window.CONTROL",identity.controlPermission());
        assertEquals("dev.nativecomplements.window.settings",identity.authority("settings"));
        assertEquals("nativecomplements_window_corner",identity.hostPreferences());
        assertEquals("nativecomplements-window",identity.hostStorageName());
        assertEquals("/data/system/nativecomplements-window/sunwindow-demo5.json",
            identity.systemFile("sunwindow-demo5.json").getPath().replace('\\','/'));
        assertEquals(dev.local.sunwindowdemo.BuildConfig.APPLICATION_ID,ModuleIdentity.CURRENT.packageName());
    }
    @Test public void unknownProductAndPathTraversalAreRejected(){
        for(String pkg:new String[]{"dev.nativecomplements.other","",null}){
            try{ModuleIdentity.forPackage(pkg);fail("unrecognized product: "+pkg);}catch(IllegalArgumentException expected){}
        }
        ModuleIdentity identity=ModuleIdentity.forPackage(ModuleIdentity.WINDOW_PACKAGE);
        for(String path:new String[]{"../sunwindow-demo5.json","/tmp/file","a/b","a\\b",".","..","",null}){
            try{identity.systemFile(path);fail("invalid system filename: "+path);}catch(IllegalArgumentException expected){}
        }
    }
}
