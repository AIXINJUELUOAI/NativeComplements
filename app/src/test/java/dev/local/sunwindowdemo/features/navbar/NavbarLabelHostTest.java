package dev.local.sunwindowdemo.features.navbar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class NavbarLabelHostTest {
    @Test public void expandedPanelHasOnlyContainerAndDecisionLines() {
        String details = "固定操作区 · PrivateEditor · [0,100][400,200]\n检查：3.20ms · 64 节点 · 私有原因";
        String text = NavbarLabelHost.formatStatus(details, NavbarStore.COLOR, NavbarStore.COLOR, "自动", true, false);
        assertEquals("容器：固定操作区\n判断：取色 · 当前：取色", text);
        assertEquals(2, text.split("\n").length);
        for (String hidden : new String[]{"PrivateEditor", "[0,100]", "3.20", "64", "私有原因"})
            assertFalse(text.contains(hidden));
        assertEquals("容器：滚动内容\n判断：悬浮 · 当前：悬浮",
                NavbarLabelHost.formatStatus("滚动内容区 · RecyclerView", 2, 2, "自动", true, false));
    }

    @Test public void overridesAreShortAndKeepSuggestionSeparateFromActual() {
        assertEquals("容器：滚动内容\n判断：悬浮 · 当前：取色（手动）",
                NavbarLabelHost.formatStatus("滚动内容区", 2, 1, "页面规则", true, false));
        assertEquals("容器：固定操作区\n判断：取色 · 当前：保留（手动）",
                NavbarLabelHost.formatStatus("固定操作区", 1, 0, "用户规则", true, false));
        assertEquals("容器：固定操作区\n判断：取色 · 当前：保留（规则）",
                NavbarLabelHost.formatStatus("固定操作区", 1, 0, "白名单", true, false));
    }

    @Test public void missingUnknownAndIdleReportsCannotExposeDetailsOrStaleModes() {
        for (String details : new String[]{null, "", "未确定 · SecretClass\n达到预算", "应用自绘导航区", "无结构信息"})
            assertEquals("容器：未识别\n判断：保留 · 当前：保留",
                    NavbarLabelHost.formatStatus(details, 0, 0, null, true, false));
        assertEquals("容器：未识别\n判断：保留 · 当前：保留",
                NavbarLabelHost.formatStatus("固定操作区", 1, 2, "页面规则", false, false));
    }

    @Test public void foldedPanelIsOneShortLineWithoutRawDiagnostics() {
        assertEquals("滚动内容 · 当前：悬浮",
                NavbarLabelHost.formatStatus("滚动内容区 · RecyclerView\n检查：1ms", 2, 2, "自动", true, true));
        assertEquals("固定操作区 · 当前：悬浮（手动）",
                NavbarLabelHost.formatStatus("固定操作区", 1, 2, "用户规则", true, true));
        assertEquals("未识别 · 当前：保留",
                NavbarLabelHost.formatStatus("固定操作区", 1, 2, "页面规则", false, true));
    }
}
