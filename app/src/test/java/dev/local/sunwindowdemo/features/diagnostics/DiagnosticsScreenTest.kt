package dev.local.sunwindowdemo.features.diagnostics

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.createRobolectricComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class DiagnosticsScreenTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun startRequiresPrivacyConfirmationAndCancelDoesNotRecord() {
        DiagnosticsStore.clear()
        compose.setContent {
            ComplementsTheme(dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { DiagnosticsScreen() }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isEnabled() and hasText("开始记录")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("开始记录").performScrollTo().performClick()
        compose.onNodeWithText("开始记录系统日志？").assertIsDisplayed()
        compose.onNodeWithText("日志可能含消息、号码等隐私", substring = true).assertIsDisplayed()
        assertFalse(DiagnosticsStore.state().getBoolean("recording"))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("开始记录系统日志？").assertDoesNotExist()
        compose.onNodeWithText("已观察模块进程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("不扫描 LSPosed", substring = true).assertDoesNotExist()
        assertFalse(DiagnosticsStore.state().getBoolean("starting"))
        assertFalse(DiagnosticsStore.state().getBoolean("recording"))
    }

    @Test fun rejectedRootNeverStartsRecordingAndNextClickCanRetry() = runBlocking {
        var starts = 0
        assertEquals(ROOT_REQUIRED_MESSAGE, requestDiagnosticsStart({ false }) { starts++ })
        assertEquals(0, starts)
        assertTrue(requestDiagnosticsStart({ true }) { starts++ }.contains("正在启动"))
        assertEquals(1, starts)
        assertEquals(ROOT_REQUIRED_MESSAGE, requestDiagnosticsStart({ false }) { starts++ })
        assertEquals(1, starts)
    }

    @Test fun cancelledPermissionRequestCannotStartRecordingAfterLeavingPage() = runBlocking {
        var started = false
        try {
            requestDiagnosticsStart({ throw CancellationException("page left") }) { started = true }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(started)
    }

    @Test fun stateExplainsLimitsAndFailuresWithoutShowingRawOutput() {
        val state = Bundle().apply {
            putString("status", "stopped"); putString("reason", "size_limit")
            putLong("bytes", 64L * 1024 * 1024); putLong("elapsedMs", 61_000)
        }
        val limit = diagnosticsStatusText(state)
        assertTrue(limit.contains("1 分 1 秒"))
        assertTrue(limit.contains("67108864 字节"))
        assertTrue(limit.contains("日志已截断"))
        state.putString("status", "error"); state.putString("reason", "capture_failed")
        assertTrue(diagnosticsStatusText(state).contains("日志不完整"))
        state.putString("status", "secret su stderr"); state.putString("reason", "private logcat content")
        assertFalse(diagnosticsStatusText(state).contains("secret"))
        assertFalse(diagnosticsStatusText(state).contains("private"))
    }
}
