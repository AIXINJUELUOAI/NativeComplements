package dev.local.sunwindowdemo.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.createRobolectricComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class BackupSettingsUiTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var request = -1
    private var intent: Intent? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            intent = contract.createIntent(context, input)
        }
    }
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                ComplementsTheme(dynamic = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { BackupSettings() }
                }
            }
        }
    }
    private fun select(json: String) {
        val uri = Uri.parse("content://backup-test/settings.json")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(json.toByteArray()))
        compose.runOnIdle { registry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(uri)) }
    }

    @Test fun exportUsesSystemDocumentPickerAndCancellationAllowsRetry() {
        show()
        compose.onNodeWithText("导出备份").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent?.action)
            assertEquals("application/json", intent?.type)
            assertTrue(intent!!.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".json"))
            registry.dispatchResult(request, Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithText("恢复备份").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent?.action)
            registry.dispatchResult(request, Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithText("明文文件可能含", substring = true).assertDoesNotExist()
    }

    @Test fun importedSettingsRequireConfirmationAndCancelOrInvalidInputNeverWrites() {
        val prefs = context.getSharedPreferences("corner", 0)
        prefs.edit().putInt("heat", 36).commit()
        show()
        compose.onNodeWithText("恢复备份").performScrollTo().performClick()
        select("""{"version":1,"module":"dev.nativecomplements.module","groups":{"corner":{"heat":48}}}""")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("恢复备份？").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已校验 1 组、1 项设置", substring = true).assertIsDisplayed()
        assertEquals(36, prefs.getInt("heat", 0))
        compose.onNodeWithText("取消").performClick()
        assertEquals(36, prefs.getInt("heat", 0))
        compose.onNodeWithText("恢复备份").performScrollTo().performClick()
        select("""{"version":999,"module":"dev.nativecomplements.module","groups":{}}""")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("无法恢复：", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("恢复备份？").assertDoesNotExist()
        assertEquals(36, prefs.getInt("heat", 0))
    }
}
