package dev.local.sunwindowdemo.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.features.floatingwindow.ui.*

/** Product navigation only; all window settings and behavior use the same implementation. */
@Composable
fun ModuleApp(controller: WindowController, page: Page, navigate: (Page) -> Unit, back: () -> Unit,
              appPackage: String? = null, openIconApp: (String) -> Unit = {}, openBackgroundApp: (String) -> Unit = {}) {
    val root = page == Page.Home || page == Page.Window
    SettingsPage(title = if (root) "类原生小窗" else page.title, onBack = if (root) null else back) { bottomInset ->
        if (page == Page.Blocked) AppPickerScreen(controller, bottomInset = bottomInset)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
            when (page) {
                Page.Home, Page.Window -> {
                    WindowScreen(controller.preferences, navigate, controller::toggleControl,
                        controller.enablePrompt, controller.enableChecking, controller::dismissEnablePrompt, controller::confirmEnablePrompt)
                }
                Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean, controller::number)
                Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                Page.Diagnostics -> DiagnosticsScreen(controller)
                Page.Tools -> SettingsGroup {
                    SettingRow("小窗诊断与恢复", onClick = { navigate(Page.Diagnostics) })
                }
                Page.Logs -> dev.local.sunwindowdemo.features.diagnostics.DiagnosticsScreen(bottomInset)
                else -> Text("此独立版本只包含小窗设置。")
            }
        }
    }
}
