package dev.local.sunwindowdemo.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.features.iconlayers.IconAppScreen
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore
import dev.local.sunwindowdemo.features.iconlayers.IconLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.features.callblocking.CallBlockingScreen
import dev.local.sunwindowdemo.features.callblocking.CallBlockingStore
import dev.local.sunwindowdemo.features.edgetouch.EdgeTouchScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.*
import dev.local.sunwindowdemo.features.barrage.BarrageHistoryScreen
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
import dev.local.sunwindowdemo.features.iconlayers.IconDiagnosticsScreen
import dev.local.sunwindowdemo.features.iconlayers.IconLayerScreen
import dev.local.sunwindowdemo.features.motion.MotionScreen
import dev.local.sunwindowdemo.features.motion.MotionStore
import dev.local.sunwindowdemo.features.suggestions.SuggestionScreen
import dev.local.sunwindowdemo.features.shade.ShadeScreen
import dev.local.sunwindowdemo.home.AboutScreen
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.home.LicensesScreen

enum class Page(val title: String) {
    Home("类原生补全计划"), Window("小窗设置"), Shade("通知与控制中心"), Barrage("弹幕通知"), BarrageHistory("弹幕记录"), Suggestions("快捷建议"),
    IconLayers("图标适配"), IconApp("图标设置"), Motion("加速度传感器控制"), EdgeTouch("边缘防误触"), Calls("来电拦截"), Records("拦截记录"),
    Gestures("手势与菜单"), Behavior("窗口与多任务"), Prevention("小窗触发限制"), Blocked("禁用应用"),
    About("关于"), Logs("诊断日志"), Licenses("开源许可"), Tools("开发者工具"), Diagnostics("小窗诊断与恢复"), IconDiagnostics("图标实验与诊断"),
}

@Composable
fun ModuleApp(
    controller: WindowController,
    page: Page,
    navigate: (Page) -> Unit,
    back: () -> Unit,
    appPackage: String? = null,
    openIconApp: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val iconApp = if (page == Page.IconApp) controller.apps.firstOrNull { it.packageName == appPackage } else null
    // Standard Activity navigation leaves predictive Back and its animation with Android.
    SettingsPage(
        title = if (page == Page.IconApp) iconApp?.label ?: "图标设置" else page.title,
        onBack = if (page == Page.Home) null else back,
        actions = {
            if (page == Page.Motion) MotionActions()
            if (page == Page.IconLayers) IconLayerActions()
        },
    ) { bottomInset ->
        when (page) {
            Page.Calls, Page.Records -> CallBlockingScreen(page == Page.Records, bottomInset, onRecords = { navigate(Page.Records) })
            Page.EdgeTouch -> EdgeTouchScreen(bottomInset)
            Page.Shade -> ShadeScreen(bottomInset)
            Page.Motion -> MotionScreen(controller.apps, controller.catalogLoaded, bottomInset)
            Page.Barrage -> BarrageScreen(controller.apps, controller.catalogLoaded, bottomInset, onHistory = { navigate(Page.BarrageHistory) })
            Page.BarrageHistory -> BarrageHistoryScreen(controller.apps, bottomInset)
            Page.IconLayers -> IconLayerScreen(controller.apps, bottomInset, openIconApp)
            Page.IconApp -> IconAppScreen(iconApp, bottomInset)
            Page.Blocked -> AppPickerScreen(controller, bottomInset = bottomInset)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
                when (page) {
                    Page.Home -> HomeScreen(
                        onWindow = { navigate(Page.Window) }, onAbout = { navigate(Page.About) },
                        onCalls = { navigate(Page.Calls) }, onEdgeTouch = { navigate(Page.EdgeTouch) },
                        onSuggestions = { navigate(Page.Suggestions) }, onMotion = { navigate(Page.Motion) },
                        onIcons = { navigate(Page.IconLayers) }, onBarrage = { navigate(Page.Barrage) },
                        onShade = { navigate(Page.Shade) },
                    )
                    Page.Window -> WindowScreen(controller.preferences, navigate, controller::toggleControl)
                    Page.Suggestions -> SuggestionScreen(controller)
                    Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                    Page.About -> AboutScreen(onLicenses = { navigate(Page.Licenses) }, onTools = { navigate(Page.Tools) }, onLogs = { navigate(Page.Logs) })
                    Page.Logs -> {
                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                val c = context.createDeviceProtectedStorageContext()
                                val keys = listOf("floating_window" to "corner", "barrage" to "barrage", "call_blocking" to "call_blocking", "edge_touch" to "edge_touch", "suggestions" to "suggestions", "motion" to "motion_access", "icon_layers" to "icon_layers", "shade" to "landscape_shade")
                                keys.forEach { (name, prefs) ->
                                    val storeContext = if (name == "floating_window") context else c
                                    val snapshot = storeContext.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
                                    dev.local.sunwindowdemo.features.diagnostics.DiagnosticsStore.setting(name, snapshot.getBoolean("enabled", name == "floating_window"))
                                }
                            }
                        }
                        dev.local.sunwindowdemo.features.diagnostics.DiagnosticsScreen(bottomInset)
                    }
                    Page.Licenses -> LicensesScreen()
                    Page.Tools -> DeveloperTools(onWindow = { navigate(Page.Diagnostics) }, onIcons = { navigate(Page.IconDiagnostics) })
                    Page.Diagnostics -> DiagnosticsScreen(controller)
                    Page.IconDiagnostics -> IconDiagnosticsScreen()
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun MotionActions() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "批量设置") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("全部允许使用", "全部前 7 秒限制", "全部始终限制").forEachIndexed { mode, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { MotionStore.all(context, mode); expanded = false })
            }
        }
    }
}

@Composable
private fun IconLayerActions() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    IconButton(onClick = {
        IconLayerStore.refreshAll(context)
        Toast.makeText(context, "已请求刷新图标", Toast.LENGTH_SHORT).show()
    }) { Icon(Icons.Default.Refresh, contentDescription = "刷新图标") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("移除图标库") }, onClick = { menu = false; remove = true })
        }
    }
    if (remove) AlertDialog(
        onDismissRequest = { remove = false },
        title = { Text("移除图标库？") },
        confirmButton = {
            TextButton(onClick = {
                remove = false
                scope.launch {
                    withContext(Dispatchers.IO) { IconLibrary.remove(context) }
                    Toast.makeText(context, "图标库已移除", Toast.LENGTH_SHORT).show()
                }
            }) { Text("移除") }
        },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } },
    )
}

@Composable
private fun DeveloperTools(onWindow: () -> Unit, onIcons: () -> Unit) {
    val context = LocalContext.current
    var calls by remember { mutableStateOf(CallBlockingStore.readConfig(context)) }
    var motion by remember { mutableStateOf(MotionStore.read(context)) }
    fun rotationStatus(): String {
        val p = context.createDeviceProtectedStorageContext().getSharedPreferences("rotation_status", 0)
        return listOf("version", "hook", "launcher_hook", "request", "host", "settings", "window", "click").joinToString("\n") {
            "$it: ${p.getString(it, "尚无记录") }"
        }
    }
    var rotation by remember { mutableStateOf(rotationStatus()) }
    SettingsGroup {
        SettingRow("小窗诊断与恢复", onClick = onWindow)
        SettingRow("图标实验与诊断", onClick = onIcons)
    }
    GroupTitle("其他服务")
    SettingsGroup {
        SettingRow("来电拦截", calls.getString("backend_error").orEmpty().ifBlank {
            if (calls.getLong("backend_seen_at") == 0L) "尚未连接" else "最近运行版本：${calls.getString("backend_version")}"
        })
        SettingRow("加速度传感器控制", motion.getString("backend_error").orEmpty().ifBlank {
            if (motion.getLong("backend_seen") == 0L) "尚未连接" else "最近运行版本：${motion.getString("backend_version")}"
        })
        SettingRow("旋转建议诊断", rotation)
        SettingRow("刷新状态", onClick = { calls = CallBlockingStore.readConfig(context); motion = MotionStore.read(context); rotation = rotationStatus() })
    }
}


