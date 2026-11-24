package dev.local.sunwindowdemo

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.features.background.BackgroundAppScreen
import dev.local.sunwindowdemo.features.background.BackgroundList
import dev.local.sunwindowdemo.features.background.BackgroundPolicy
import dev.local.sunwindowdemo.features.background.BackgroundStore
import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingScreen
import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class ExplanatoryCopyTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun backgroundKeepsModesSwitchesAndProcessActionsWithoutExplanations() {
        val context = compose.activity
        val pkg = "com.ss.android.ugc.aweme"
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; processName = pkg; uid = 12005 }
        })
        BackgroundStore.prefs(context).edit().clear().commit()
        BackgroundStore.mode(context, pkg, BackgroundPolicy.Mode.STRICT)
        BackgroundStore.keepActive(context, pkg, true)
        BackgroundStore.protectPlayback(context, pkg, true)
        BackgroundStore.process(context, pkg, pkg, BackgroundPolicy.Action.FREEZE)
        BackgroundStore.process(context, pkg, "$pkg:worker", BackgroundPolicy.Action.RECLAIM)
        var details by mutableStateOf(false)
        compose.setContent {
            ComplementsTheme(dynamic = false) {
                if (details) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BackgroundAppScreen(pkg)
                } else BackgroundList(listOf(LaunchableApp(pkg, "后台测试应用")), true, 0.dp, { details = true })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("后台测试应用").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("启用墓碑").assertIsOff()
        compose.onNodeWithText("严格").assertExists()
        compose.onNodeWithText("后台测试应用").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("后台播放保护").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("不进缓存").assertIsOn()
        compose.onNodeWithText("后台播放保护").assertIsOn()
        compose.onNodeWithText("冻结").assertExists()
        compose.onNodeWithText("低内存时裁切").assertExists()
        for (mode in BackgroundPolicy.Mode.entries) {
            compose.runOnIdle { BackgroundStore.mode(context, pkg, mode) }
            listOf("主进程当前设为冻结", "主进程默认放行", "开启后，抖音", "约 5 秒", "冻结和裁切在进程", "低内存时裁切：").forEach {
                compose.onNodeWithText(it, substring = true).assertDoesNotExist()
            }
            compose.onNodeWithText("进程操作").assertExists()
        }
    }

    @Test fun smsKeepsPermissionSimStateCountsAndRejectAllConfirmation() {
        val context = compose.activity
        Shadows.shadowOf(context.application).denyPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS)
        SmsBlockingStore.preferences(context).edit().clear().commit()
        SmsBlockingStore.setEnabled(context, true)
        SmsBlockingStore.setFollowSim1(context, true)
        compose.setContent { ComplementsTheme(dynamic = false) { SmsBlockingScreen() } }
        compose.onNodeWithText("启用短信拦截").assertIsOn()
        compose.onNodeWithText("识别 SIM 卡").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("允许读取系统通讯录").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("等待电话权限", substring = true).assertCountEquals(2)
        compose.onNodeWithText("当前生效设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("跟随 SIM 1：0 条号码规则、0 条关键词", substring = true).assertExists()
        compose.onNodeWithText("0 条").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("拦截在系统电话进程中判定", substring = true).assertDoesNotExist()
        compose.onNodeWithText("拦截该卡全部短信").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("拦截 SIM 1 的全部短信？").assertIsDisplayed()
        compose.onNodeWithText("该卡所有短信将被拦截，包括验证码、银行和运营商通知。").assertIsDisplayed()
        compose.onNodeWithText("原生永久删除", substring = true).assertDoesNotExist()
        compose.onNodeWithText("拦截全部").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(SmsBlockingStore.readConfig(context).getBoolean("sim1_reject_all")) }
        compose.onNodeWithText("拦截该卡全部短信").performScrollTo().performClick()
        compose.onNodeWithText("拦截全部").performClick()
        compose.runOnIdle { assertTrue(SmsBlockingStore.readConfig(context).getBoolean("sim1_reject_all")) }
        compose.onNodeWithText("拦截该卡全部短信").assertIsOn()
    }

    @Test fun smsRecordsKeepContentEmptyStateAndClearConfirmationWithoutTechnicalHint() {
        val context = compose.activity
        SmsBlockingStore.preferences(context).edit().clear().commit()
        SmsBlockingStore.recordBlocked(context, Bundle().apply {
            putString("sender", "测试发送方")
            putInt("slot_index", 0)
            putString("reason", "all_sms")
        })
        compose.setContent { ComplementsTheme(dynamic = false) { SmsBlockingScreen(showRecords = true) } }
        compose.onNodeWithText("仅记录已同步确认关联短信", substring = true).assertDoesNotExist()
        compose.onNodeWithText("测试发送方").assertIsDisplayed()
        compose.onNodeWithText("SIM 1 · 拦截该卡全部短信", substring = true).assertExists()
        compose.onNodeWithText("清空记录").performScrollTo().performClick()
        compose.onNodeWithText("清空拦截记录？").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("暂无拦截记录").assertIsDisplayed()
        compose.onNodeWithText("仅记录已同步确认关联短信", substring = true).assertDoesNotExist()
    }
}
