package dev.local.sunwindowdemo.features.smsblocking

import androidx.compose.ui.test.*
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.createRobolectricComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class SmsRecordScreenTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun clickingRecordShowsMetadataAndBodyThenDismisses() {
        val body = "已保存的短信正文，可长按选择复制。"
        records(record("测试发送方").put("body", body))
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithText("测试发送方").performClick()
        compose.onNodeWithText("短信详情").assertIsDisplayed()
        compose.onAllNodesWithText("测试发送方").assertCountEquals(2)
        compose.onAllNodesWithText("测试卡 · 命中关键词 测试", substring = true).assertCountEquals(2)
        // Robolectric's Magnifier popup has no Surface; real long-press/copy needs device acceptance.
        // This test verifies the detail content/lifetime, not platform text-selection behavior.
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("短信详情").assertDoesNotExist()
        compose.onNodeWithText(body).assertDoesNotExist()
    }

    @Test fun oldMissingAndEmptyBodiesShowOnlyNeutralMissingMessage() {
        records(record("旧记录"), record("空正文").put("body", ""))
        for (sender in listOf("旧记录", "空正文")) {
            compose.onNodeWithText(sender).performClick()
            compose.onNodeWithText("未记录到正文").assertIsDisplayed()
            compose.onNodeWithText("正文超过700字符，仅显示前700字符").assertDoesNotExist()
            compose.onNodeWithText("解码失败", substring = true).assertDoesNotExist()
            compose.onNodeWithText("关闭").performClick()
        }
    }

    @Test fun truncatedBodyHasNoticeAndClearingClosesOpenPlaintextDetails() {
        val body = "保留的正文片段"
        records(record("截断记录").put("body", body).put("body_truncated", true))
        compose.onNodeWithText("截断记录").performClick()
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText("正文超过700字符，仅显示前700字符").assertIsDisplayed()
        compose.runOnIdle { SmsBlockingStore.clearRecords(compose.activity) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("短信详情").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithText("暂无拦截记录").assertIsDisplayed()
        compose.runOnIdle {
            SmsBlockingStore.preferences(compose.activity).edit()
                .putString("records", JSONArray().put(record("再次记录").put("body", body)).toString()).commit()
        }
        compose.onNodeWithText("清空记录").performClick()
        compose.onNodeWithText("清空拦截记录？").assertIsDisplayed()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("暂无拦截记录").assertIsDisplayed()
        compose.runOnIdle { assertTrue(SmsBlockingStore.readRecords(compose.activity).isEmpty()) }
    }

    private fun record(sender: String) = JSONObject().put("sender", sender).put("slot_index", 0)
        .put("sim_label", "测试卡").put("reason", "keyword").put("matched_pattern", "测试")
        .put("time", 1_790_000_000_000L)

    private fun records(vararg records: JSONObject) {
        val values = JSONArray()
        records.forEach(values::put)
        SmsBlockingStore.preferences(compose.activity).edit().clear().putString("records", values.toString()).commit()
        compose.setContent { ComplementsTheme(dynamic = false) { SmsBlockingScreen(showRecords = true) } }
    }
}
