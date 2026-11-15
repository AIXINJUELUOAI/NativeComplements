package dev.local.sunwindowdemo.features.navbar;

import android.content.pm.ProviderInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarProviderTest {
    @org.junit.After public void resetCallingUidAndPreferences() {
        ShadowBinder.setCallingUid(android.os.Process.myUid());
        NavbarStore.preferences(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }
    private void caller(String packageName, int uid) {
        var info = new android.content.pm.PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new android.content.pm.ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = uid;
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(info);
        ShadowBinder.setCallingUid(uid);
    }
    private NavbarProvider provider() {
        NavbarProvider provider = new NavbarProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = NavbarStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        return provider;
    }
    @Test(expected = SecurityException.class) public void ordinaryUidCannotReadSystemSnapshot() {
        ShadowBinder.setCallingUid(99999);
        provider().call("system", null, null);
    }
    @Test public void systemSnapshotDoesNotMigrateOrOverwriteSavedSettings() {
        var context = RuntimeEnvironment.getApplication();
        var preferences = NavbarStore.preferences(context);
        preferences.edit().clear().putBoolean("enabled", true).putInt("default_mode", 0)
                .putInt("com.tencent.mm/*", 2)
                .putInt("com.tencent.mm/com.tencent.mm.framework.app.UIPageFragmentActivity", 0).commit();
        var before = new java.util.HashMap<>(preferences.getAll());
        try {
            ShadowBinder.setCallingUid(1000);
            var snapshot = provider().call("system", null, null);
            assertEquals(2, snapshot.getInt("com.tencent.mm/*"));
            assertEquals(0, snapshot.getInt("com.tencent.mm/com.tencent.mm.framework.app.UIPageFragmentActivity"));
            assertEquals(before, preferences.getAll());
        } finally { preferences.edit().clear().commit(); }
    }
    @Test public void ownReadIsRulesCompatibleAndDoesNotExposeOtherPackages() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var preferences = NavbarStore.preferences(context);
        preferences.edit().putBoolean("enabled", true).putInt("default_mode", 0)
                .putInt("com.tencent.mm/*", 2)
                .putInt("com.tencent.mm/com.tencent.mm.framework.app.UIPageFragmentActivity", 1)
                .putInt("com.tencent.mobileqq/secret.Activity", 2)
                .putInt("com.tencent.mm.other/prefixCollision", 2)
                .putBoolean("landscape_clear", false).putBoolean("label_mode", true).commit();
        var before = new java.util.HashMap<>(preferences.getAll());
        caller("com.tencent.mm", 12345);
        var extras = new android.os.Bundle();
        extras.putBoolean("enabled", false);
        var snapshot = provider().call("read", "com.tencent.mm", extras);
        assertTrue(snapshot.getBoolean("enabled"));
        assertTrue(snapshot.getBoolean("label_mode"));
        assertFalse(snapshot.containsKey("learned"));
        assertFalse(snapshot.containsKey("default_mode"));
        assertEquals(2, snapshot.getInt("com.tencent.mm/*"));
        assertEquals(2, snapshot.getInt("*")); // Legacy package override remains effective.
        assertEquals(1, snapshot.getInt("com.tencent.mm.framework.app.UIPageFragmentActivity"));
        assertFalse(snapshot.containsKey("com.tencent.mobileqq/secret.Activity"));
        assertFalse(snapshot.containsKey("secret.Activity"));
        assertFalse(snapshot.containsKey("com.tencent.mm.other/prefixCollision"));
        assertFalse(snapshot.containsKey("landscape_clear"));
        var packages = new org.json.JSONObject(snapshot.getString("templates")).getJSONObject("packages");
        assertEquals(0, packages.length());
        assertFalse(packages.has("com.tencent.mobileqq"));
        var rules = new NavbarRules(snapshot);
        assertEquals(1, rules.resolve("com.tencent.mm", "com.tencent.mm.framework.app.UIPageFragmentActivity", 1).mode());
        assertEquals(2, rules.resolve("com.tencent.mm", "other.Activity", 1).mode());
        assertEquals(before, preferences.getAll());
    }
    @Test public void ownReadIncludesBuiltinWhitelistWithoutUserOverrides() throws Exception {
        NavbarStore.preferences(RuntimeEnvironment.getApplication()).edit().putBoolean("enabled", true).commit();
        caller("org.telegram.messenger", 12345);
        var snapshot = provider().call("read", "org.telegram.messenger", null);
        var resolved = new NavbarRules(snapshot).resolve("org.telegram.messenger", "org.telegram.ui.LaunchActivity", 1);
        assertEquals(NavbarStore.ORIGINAL, resolved.mode());
        assertEquals("白名单", resolved.source());
    }
    @Test public void unknownOwnPackageGetsDefaultAndEmptyTemplates() throws Exception {
        caller("sample.unknown", 12345);
        var snapshot = provider().call("read", "sample.unknown", null);
        assertFalse(snapshot.getBoolean("enabled"));
        assertEquals(NavbarStore.AUTO, snapshot.getInt("*"));
        assertEquals(0, new org.json.JSONObject(snapshot.getString("templates")).getJSONObject("packages").length());
    }
    @Test(expected = SecurityException.class) public void ownUidCannotRequestAnotherPackage() {
        caller("sample.own", 12345);
        provider().call("read", "com.tencent.mm", null);
    }
    @Test(expected = SecurityException.class) public void unknownUidCannotReadPackage() {
        ShadowBinder.setCallingUid(99999);
        provider().call("read", "com.tencent.mm", null);
    }
    @Test(expected = SecurityException.class) public void ordinaryUidCannotWriteStatus() {
        caller("sample.own", 12345);
        provider().call("status", null, new android.os.Bundle());
    }
    @Test public void retiredLearnMethodIsRejectedEvenForSystemUid() {
        var extras = new android.os.Bundle();
        extras.putString("component", "sample/sample.Page");
        extras.putLong("version", 7);
        extras.putInt("mode", NavbarStore.COLOR);
        var provider = provider();
        for (int uid : new int[]{1000, 12345}) {
            ShadowBinder.setCallingUid(uid);
            assertThrows(IllegalArgumentException.class, () -> provider.call("learn", null, extras));
        }
        assertTrue(NavbarStore.preferences(RuntimeEnvironment.getApplication()).getAll().isEmpty());
    }
    @Test(expected = SecurityException.class) public void ordinaryUidCannotLabelPages() {
        caller("com.android.systemui", 10001);
        caller("sample.own", 12345);
        provider().call("label", null, new android.os.Bundle());
    }
    @Test public void systemUiAndManagerCanLabelQueryAndUndoPageRules() {
        var preferences = NavbarStore.preferences(RuntimeEnvironment.getApplication());
        preferences.edit().clear().commit();
        caller("com.android.systemui", 10001);
        var provider = provider();
        var extras = new android.os.Bundle();
        extras.putString("package", "sample");
        extras.putString("activity", "sample.Page#beef");
        extras.putInt("mode", NavbarStore.FLOATING);
        assertEquals(-1, provider.call("label", null, extras).getInt("previous"));
        extras.putInt("mode", -3);
        assertEquals(NavbarStore.FLOATING, provider.call("label", null, extras).getInt("previous"));
        assertEquals(NavbarStore.FLOATING, preferences.getInt("sample/sample.Page#beef", -1));
        ShadowBinder.setCallingUid(RuntimeEnvironment.getApplication().getApplicationInfo().uid);
        extras.putInt("mode", NavbarStore.AUTO);
        assertEquals(NavbarStore.FLOATING, provider.call("label", null, extras).getInt("previous"));
        assertEquals(NavbarStore.AUTO, preferences.getInt("sample/sample.Page#beef", -1));
        extras.putInt("mode", -1);
        assertEquals(NavbarStore.AUTO, provider.call("label", null, extras).getInt("previous"));
        assertFalse(preferences.contains("sample/sample.Page#beef"));
    }
    @Test(expected = IllegalArgumentException.class) public void nullPackageCannotRead() {
        provider().call("read", null, null);
    }
    @Test(expected = IllegalArgumentException.class) public void noConfigurationWriteMethodExists() {
        caller("sample.own", 12345);
        provider().call("write", "sample.own", new android.os.Bundle());
    }
    @Test(expected = UnsupportedOperationException.class) public void insertRemainsUnsupported() {
        provider().insert(android.net.Uri.parse("content://" + NavbarStore.AUTHORITY), new android.content.ContentValues());
    }
    @Test(expected = UnsupportedOperationException.class) public void updateRemainsUnsupported() {
        provider().update(android.net.Uri.parse("content://" + NavbarStore.AUTHORITY), new android.content.ContentValues(), null, null);
    }
    @Test(expected = UnsupportedOperationException.class) public void deleteRemainsUnsupported() {
        provider().delete(android.net.Uri.parse("content://" + NavbarStore.AUTHORITY), null, null);
    }
    @Test public void systemUidCanReadTemplateSnapshot() {
        ShadowBinder.setCallingUid(1000);
        var snapshot = provider().call("system", null, null);
        assertTrue(snapshot.getString("templates").contains("类原生补全计划导航条白名单"));
        assertFalse(snapshot.getString("templates").contains("ColorOS"));
    }
}
