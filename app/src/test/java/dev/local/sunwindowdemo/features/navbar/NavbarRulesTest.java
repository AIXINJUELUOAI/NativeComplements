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
        snapshot.putInt("default_mode", 1);
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
    @Test public void disabledFeatureTemplateSwitchAndVersionThreshold() {
        Bundle snapshot = settings();
        assertEquals(1, new NavbarRules(snapshot).resolve("sample", "sample.Other", 10).mode());
        snapshot.putBoolean("templates_enabled", false);
        assertEquals(1, new NavbarRules(snapshot).resolve("sample", "sample.Other", 11).mode());
        snapshot.putBoolean("enabled", false);
        assertEquals(0, new NavbarRules(snapshot).resolve("sample", "sample.Other", 11).mode());
    }
    @Test public void wildcardTreatsPunctuationLiterally() {
        assertTrue(NavbarRules.matches("sample.*.Page*", "sample.one.PageTwo"));
        assertFalse(NavbarRules.matches("sample.Page", "sampleXPage"));
        assertTrue(NavbarRules.matches("*", "sample.Other"));
    }
    @Test public void bundledCommunityRulesCoverKnownPagesAndAllowUserOverride() throws Exception {
        Bundle snapshot = settings();
        try (var input = RuntimeEnvironment.getApplication().getAssets().open("navbar/templates.json")) {
            snapshot.putString("templates", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        NavbarRules rules = new NavbarRules(snapshot);
        assertEquals(1, rules.resolve("com.taobao.taobao", "com.taobao.tao.welcome.Welcome", Long.MAX_VALUE).mode());
        assertEquals(2, rules.resolve("com.taobao.taobao", "com.taobao.android.detail2.core.framework.NewDetailActivity", Long.MAX_VALUE).mode());
        assertEquals(1, rules.resolve("com.sankuai.meituan", "com.meituan.android.pt.homepage.activity.MainActivity", Long.MAX_VALUE).mode());
        snapshot.putInt("com.taobao.taobao/com.taobao.android.detail2.core.framework.NewDetailActivity", 0);
        assertEquals(0, new NavbarRules(snapshot).resolve("com.taobao.taobao", "com.taobao.android.detail2.core.framework.NewDetailActivity", Long.MAX_VALUE).mode());
    }
}
