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
    @Test public void learnedRulesAreFixedOnlyForTheirVersionAndSeedOtherwise() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("enabled", true);
        snapshot.putString("templates", """
            {"packages":{"app":{"enabled":true,"rules":{
            "app.Home":{"mode":2,"source":"学习规则","base_only":true,"learned":true,"versionCode":7,"samples":5,"agreement":1.0}}}}}
            """);
        snapshot.putString("learned", "{\"other/other.Page\":1}");
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals(NavbarStore.FLOATING, rules.resolve("app", "app.Home", 7).mode());
        assertEquals("学习规则", rules.resolve("app", "app.Home", 7).source());
        assertEquals(NavbarStore.AUTO, rules.resolve("app", "app.Home", 8).mode());
        assertEquals(Integer.valueOf(NavbarStore.FLOATING), rules.seed("app", "app.Home"));
        assertEquals(Integer.valueOf(NavbarStore.COLOR), rules.seed("other", "other.Page"));
        assertNull(rules.seed("other", "other.Unknown"));
    }
    @Test public void learnedVotesNeedThreeAndNinetyPercent() {
        assertEquals(-2, NavbarStore.learnedMode(1, 1));
        assertEquals(NavbarStore.COLOR, NavbarStore.learnedMode(9, 1));
        assertEquals(NavbarStore.FLOATING, NavbarStore.learnedMode(0, 3));
        assertEquals(-1, NavbarStore.learnedMode(6, 4));
    }
}
