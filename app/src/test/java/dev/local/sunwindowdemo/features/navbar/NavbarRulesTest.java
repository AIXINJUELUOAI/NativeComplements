package dev.local.sunwindowdemo.features.navbar;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarRulesTest {
    private Bundle settings() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("enabled", true);
        snapshot.putString("templates", """
            {"packages":{"sample":{"enabled":true,"disableVersionCode":10,"rules":{
            "*":{"mode":2,"source":"base"},
            "sample.Detail*":{"mode":0,"source":"specific"},
            "sample.Details":{"mode":1,"color":-1,"source":"exact"}
            }}}}
            """);
        return snapshot;
    }
    @Test public void exactThenSpecificWildcardThenDefault() {
        NavbarRules rules = new NavbarRules(settings());
        assertEquals("exact", rules.resolve("sample", "sample.Details", 11).source());
        assertEquals("specific", rules.resolve("sample", "sample.DetailOther", 11).source());
        assertEquals(2, rules.resolve("sample", "sample.Other", 11).mode());
        assertEquals(NavbarStore.AUTO, rules.resolve("sample", "sample.Other", 10).mode());
        assertEquals(Integer.valueOf(-1), rules.resolve("sample", "sample.Details", 11).color());
    }
    @Test public void explicitOriginalOverridesTemplateAndAppRule() {
        Bundle snapshot = settings();
        snapshot.putInt("sample/*", 1);
        snapshot.putInt("sample/sample.Details", 0);
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals(0, rules.resolve("sample", "sample.Details", 11).mode());
        assertEquals(1, rules.resolve("sample", "sample.Other", 11).mode());
    }
    @Test public void templatesFollowMasterSwitchIncludingOldDisabledTemplatePreference() {
        Bundle snapshot = settings();
        assertEquals(NavbarStore.AUTO, new NavbarRules(snapshot).resolve("sample", "sample.Other", 10).mode());
        snapshot.putBoolean("templates_enabled", false);
        assertEquals(2, new NavbarRules(snapshot).resolve("sample", "sample.Other", 11).mode());
        snapshot.putBoolean("enabled", false);
        assertEquals(0, new NavbarRules(snapshot).resolve("sample", "sample.Other", 11).mode());
    }
    @Test public void wildcardTreatsPunctuationLiterally() {
        assertTrue(NavbarRules.matches("sample.*.Page*", "sample.one.PageTwo"));
        assertFalse(NavbarRules.matches("sample.Page", "sampleXPage"));
        assertTrue(NavbarRules.matches("*", "sample.Other"));
    }
    private Bundle bundledSettings() throws Exception {
        Bundle snapshot = settings();
        try (var input = RuntimeEnvironment.getApplication().getAssets().open("navbar/templates.json")) {
            snapshot.putString("templates", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return snapshot;
    }
    @Test public void whitelistKeepsOriginalAndEverythingElseIsAuto() throws Exception {
        NavbarRules rules = new NavbarRules(bundledSettings());
        assertEquals(NavbarStore.ORIGINAL, rules.resolve("com.openai.chatgpt", "any.Page", 1, false).mode());
        assertEquals(NavbarStore.ORIGINAL, rules.resolve("com.google.android.dialer", "any.Page", 1).mode());
        assertEquals(NavbarStore.ORIGINAL, rules.resolve("com.android.settings", "any.Page", 1).mode());
        assertEquals(NavbarStore.AUTO, rules.resolve("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 1).mode());
        assertEquals(NavbarStore.AUTO, rules.resolve("com.jingdong.app.mall", "new.UnknownActivity", 1).mode());
        assertEquals(NavbarStore.FLOATING, rules.resolve("com.max.xiaoheihe",
                "com.max.xiaoheihe.module.story.StoryActivity", 1).mode());
        assertEquals(NavbarStore.AUTO, rules.resolve("com.max.xiaoheihe", "com.max.xiaoheihe.MainActivity", 1).mode());
    }
    @Test public void bundledTransparencyKeepsPolicyBitsAndUserOverrides() throws Exception {
        Bundle snapshot = bundledSettings();
        // A literal fixture isolates bit semantics from real Activity naming.
        snapshot.putString("templates", """
            {"packages":{"sample":{"enabled":true,"translucent_rules":{
            "sample.Transparent":{"policy_flag":4,"base_only":true},
            "sample.Consume":{"policy_flag":6,"base_only":true}}}}}
            """);
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals(2, rules.resolve("sample", "sample.Transparent", 1, true).mode());
        assertFalse(rules.resolve("sample", "sample.Transparent", 1, true).consumesNavigationInsets());
        assertTrue(rules.resolve("sample", "sample.Consume", 1, true).consumesNavigationInsets());
        assertEquals(NavbarStore.AUTO, rules.resolve("sample", "sample.Transparent", 1, false).mode());
        snapshot.putInt("sample/*", 0);
        assertEquals(0, new NavbarRules(snapshot).resolve("sample", "sample.Transparent", 1).mode());
    }
    @Test public void savedUserRulesBeatWhitelistAndAuto() throws Exception {
        Bundle snapshot = bundledSettings();
        snapshot.putInt("com.openai.chatgpt/*", 2);
        assertEquals(2, new NavbarRules(snapshot).resolve("com.openai.chatgpt", "any.Page", 1).mode());
        snapshot.putInt("com.tencent.mm/com.tencent.mm.ui.LauncherUI", 1);
        assertEquals(1, new NavbarRules(snapshot).resolve("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 1).mode());
        snapshot.putInt("default_mode", 0);   // retired preference is ignored
        assertEquals(NavbarStore.AUTO, new NavbarRules(snapshot).resolve("unknown", "unknown.Page", 1).mode());
    }
    @Test public void learnedTemplatesAndLegacySnapshotAreIgnoredAtEveryVersion() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("enabled", true);
        snapshot.putString("templates", """
            {"packages":{"app":{"enabled":true,"rules":{
            "app.Home":{"mode":2,"learned":true,"versionCode":7},
            "app.Home#beef":{"mode":1,"learned":true,"versionCode":7},
            "app.Manual":{"mode":2,"source":"人工确认","manual":true}}}}}
            """);
        snapshot.putString("learned", "{\"other/other.Page\":1,\"app/app.Home#cafe\":2}");
        NavbarRules rules = new NavbarRules(snapshot);
        for (long version : new long[]{7, 8}) {
            assertEquals(NavbarStore.AUTO, rules.resolve("app", "app.Home", version).mode());
            assertEquals("自动", rules.resolve("app", "app.Home", version).source());
            assertNull(rules.page("app", "app.Home#beef", version));
            assertNull(rules.page("app", "app.Home#cafe", version));
            assertEquals(NavbarStore.AUTO, rules.resolve("other", "other.Page", version).mode());
            assertEquals(NavbarStore.FLOATING, rules.resolve("app", "app.Manual", version).mode());
        }
        snapshot.putInt("app/app.Home", NavbarStore.COLOR);
        snapshot.putInt("app/app.Home#beef", NavbarStore.ORIGINAL);
        rules = new NavbarRules(snapshot);
        assertEquals(NavbarStore.COLOR, rules.resolve("app", "app.Home", 7).mode());
        assertEquals(NavbarStore.ORIGINAL, rules.page("app", "app.Home#beef", 7).mode());
    }
    @Test public void persistedLegacyHostSnapshotCannotRestoreLearnedModes() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var directory = dev.local.sunwindowdemo.core.HostSnapshot.directory(context);
        java.nio.file.Files.createDirectories(directory.toPath());
        var file = new java.io.File(directory, "navbar_legacy_learning_test.json");
        var values = new org.json.JSONObject()
                .put("enabled", new org.json.JSONArray().put("b").put(true))
                .put("learned", new org.json.JSONArray().put("s").put("{\"app/app.Home\":2}"))
                .put("templates", new org.json.JSONArray().put("s").put("""
                    {"packages":{"app":{"enabled":true,"rules":{
                    "app.Home":{"mode":2,"learned":true,"versionCode":7}}}}}
                    """));
        try {
            java.nio.file.Files.writeString(file.toPath(), new org.json.JSONObject()
                    .put("format", 1).put("feature", "navbar_legacy_learning_test").put("values", values).toString());
            Bundle cached = dev.local.sunwindowdemo.core.HostSnapshot.open(context, "navbar_legacy_learning_test")
                    .read(() -> null).config();
            assertNotNull(cached);
            assertTrue(cached.containsKey("learned"));
            NavbarRules.Choice choice = new NavbarRules(cached).resolve("app", "app.Home", 7);
            assertEquals(NavbarStore.AUTO, choice.mode());
            assertEquals("自动", choice.source());
        } finally { java.nio.file.Files.deleteIfExists(file.toPath()); }
    }
    @Test public void ignoredLearnedMatchDoesNotHideManualWildcard() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("enabled", true);
        snapshot.putString("templates", """
            {"packages":{"app":{"enabled":true,"rules":{
            "app.Home":{"mode":1,"learned":true},
            "app.Detail*":{"mode":1,"learned":true},
            "*":{"mode":2,"source":"手写规则"}}}}}
            """);
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals("手写规则", rules.resolve("app", "app.Home", 7).source());
        assertEquals("手写规则", rules.resolve("app", "app.Details", 7).source());
    }
    @Test public void learnedTransparencyIsIgnoredButManualPageRuleRemains() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("enabled", true);
        snapshot.putString("templates", """
            {"packages":{"app":{"enabled":true,"translucent_rules":{
            "app.Home":{"policy_flag":6,"learned":true}},"rules":{
            "app.Home#beef":{"mode":2,"source":"人工确认","manual":true}}}}}
            """);
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals(NavbarStore.AUTO, rules.resolve("app", "app.Home", 7).mode());
        assertEquals("人工确认", rules.page("app", "app.Home#beef", 7).source());
    }
    @Test public void labelWritesLatestModeAndReturnsPreviousForUndo() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        NavbarStore.preferences(context).edit().clear().commit();
        assertEquals(-1, NavbarStore.label(context, "app", "app.Page", NavbarStore.FLOATING));
        assertEquals(NavbarStore.FLOATING, NavbarStore.label(context, "app", "app.Page", NavbarStore.COLOR));
        assertEquals(NavbarStore.COLOR, NavbarStore.preferences(context).getInt("app/app.Page", -1));
        Bundle snapshot = NavbarStore.systemSnapshot(context);
        snapshot.putBoolean("enabled", true);
        assertEquals("用户规则", new NavbarRules(snapshot).resolve("app", "app.Page", 1).source());
        assertEquals(NavbarStore.COLOR, NavbarStore.label(context, "app", "app.Page", -1));
        assertFalse(NavbarStore.preferences(context).contains("app/app.Page"));
    }
    @Test public void pageRuleWinsInsideItsActivityOnly() {
        Bundle settings = new Bundle();
        settings.putBoolean("enabled", true);
        settings.putInt("app/app.Main#0000beef", NavbarStore.FLOATING);
        NavbarRules rules = new NavbarRules(settings);
        assertEquals("页面规则", rules.page("app", "app.Main#0000beef", 1).source());
        assertEquals(NavbarStore.FLOATING, rules.page("app", "app.Main#0000beef", 1).mode());
        assertNull(rules.page("app", "app.Main#0000cafe", 1));
        assertEquals(NavbarStore.AUTO, rules.resolve("app", "app.Main", 1).mode());
    }
}
