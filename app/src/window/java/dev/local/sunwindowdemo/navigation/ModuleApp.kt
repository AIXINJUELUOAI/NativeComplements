package dev.local.sunwindowdemo.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.features.floatingwindow.ui.*
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore
import dev.local.sunwindowdemo.features.shade.ShadeStore
import dev.local.sunwindowdemo.home.LicensesScreen

/** Product navigation only; all window settings and behavior use the same implementation. */
@Composable
fun ModuleApp(controller: WindowController, page: Page, navigate: (Page) -> Unit, back: () -> Unit,
              appPackage: String? = null, openIconApp: (String) -> Unit = {}) {
    val root = page == Page.Home || page == Page.Window
    SettingsPage(title = if (root) "类原生小窗" else page.title, onBack = if (root) null else back) { bottomInset ->
        if (page == Page.Blocked) AppPickerScreen(controller, bottomInset = bottomInset)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
            when (page) {
                Page.Home, Page.Window -> {
                    WindowScreen(controller.preferences, navigate, controller::toggleControl,
                        controller.enablePrompt, controller.enableChecking, controller::dismissEnablePrompt)
                    WindowMonetSettings()
                    SettingsGroup {
                        SettingRow("关于", onClick = { navigate(Page.About) })
                    }
                }
                Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean, controller::number)
                Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                Page.Diagnostics -> DiagnosticsScreen(controller)
                Page.Tools -> SettingsGroup {
                    SettingRow("小窗诊断与恢复", onClick = { navigate(Page.Diagnostics) })
                }
                Page.About -> {
                    dev.local.sunwindowdemo.home.AboutScreen(
                        onLicenses = { navigate(Page.Licenses) }, onTools = { navigate(Page.Tools) },
                        onLogs = { navigate(Page.Logs) })
                    GroupTitle("独立版本")
                    SettingsGroup {
                        SettingRow("系统作用域", "仅系统框架、SystemUI 和已适配桌面；不依赖无障碍。")
                        SettingRow("与主模块共存", "请在 LSPosed 仅启用一个小窗后端。接管权在启动时确定，切换后需重启。")
                    }
                }
                Page.Logs -> dev.local.sunwindowdemo.features.diagnostics.DiagnosticsScreen(bottomInset)
                Page.Licenses -> LicensesScreen()
                else -> Text("此独立版本只包含小窗设置。")
            }
        }
    }
}

@Composable
private fun WindowMonetSettings() {
    val context = LocalContext.current
    var themed by remember { mutableStateOf(IconLayerStore.systemThemed(context)) }
    var cover by remember { mutableStateOf(ShadeStore.coverMonet(context)) }
    var window by remember { mutableStateOf(ShadeStore.windowMonet(context)) }
    DisposableEffect(context) {
        val prefs = IconLayerStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> themed = IconLayerStore.systemThemed(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    GroupTitle("莫奈图标")
    SettingsGroup {
        if (!themed) SettingRow("需先启用系统主题图标", "由已适配桌面上报“壁纸与样式 → 主题图标”状态；不强制修改系统主题。")
        ToggleRow("遮罩莫奈图标", "仅小窗启动与变换遮罩", value = themed && cover, enabled = themed) {
            cover = it; ShadeStore.setCoverMonet(context, it)
        }
        ToggleRow("小窗莫奈图标", "悬浮菜单与扇区", value = themed && window, enabled = themed) {
            window = it; ShadeStore.setWindowMonet(context, it)
        }
    }
}
