package dev.local.sunwindowdemo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import dev.local.sunwindowdemo.core.ui.ComplementsTheme
import dev.local.sunwindowdemo.home.LicensesScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-420dpi")
class LicensesScreenTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun projectNamesAndLinksRemainWithoutExplanatorySubtitles() {
        compose.setContent {
            ComplementsTheme(dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { LicensesScreen() }
            }
        }
        compose.onNodeWithText("类原生补全计划").assertExists()
        compose.onNodeWithText("OpenAlive").assertDoesNotExist()
        compose.onNodeWithText("SunOS android_sun-sdk").assertHasClickAction()
        compose.onNodeWithText("完美图标计划").assertExists()
        compose.onNodeWithText("Jetpack Compose").assertHasClickAction()
        for (removed in listOf("版本 ", "本模块以", "社区图标项目", "Apache-2.0", "素材权属归原作者")) {
            compose.onNodeWithText(removed, substring = true).assertDoesNotExist()
        }
    }
}
