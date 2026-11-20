package dev.local.sunwindowdemo.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingScreen
import dev.local.sunwindowdemo.features.callblocking.CallBlockingStore
import dev.local.sunwindowdemo.features.darkaod.DarkAodStore
import dev.local.sunwindowdemo.features.edgetouch.EdgeTouchScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.*
import dev.local.sunwindowdemo.features.barrage.BarrageHistoryScreen
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
import dev.local.sunwindowdemo.features.gamemode.GameModeScreen
import dev.local.sunwindowdemo.features.gamemode.GameQuickScreen
import dev.local.sunwindowdemo.features.gamemode.GameCornerScreen
import dev.local.sunwindowdemo.features.iconlayers.IconDiagnosticsScreen
import dev.local.sunwindowdemo.features.iconlayers.IconLayerScreen
import dev.local.sunwindowdemo.features.iconlayers.IconBatchDialog
import dev.local.sunwindowdemo.features.motion.MotionScreen
import dev.local.sunwindowdemo.features.motion.MotionStore
import dev.local.sunwindowdemo.features.suggestions.SuggestionScreen
import dev.local.sunwindowdemo.features.suggestions.SuggestionAppsScreen
import dev.local.sunwindowdemo.features.shade.ShadeScreen
import dev.local.sunwindowdemo.features.shade.ShadeStore
import dev.local.sunwindowdemo.home.AboutScreen
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.home.LicensesScreen

@Composable
fun ModuleApp(
    controller: WindowController,
    page: Page,
    navigate: (Page) -> Unit,
    back: () -> Unit,
    appPackage: String? = null,
    openIconApp: (String) -> Unit = {},
) {
    if (page == Page.Alive) {
        dev.local.sunwindowdemo.features.alive.AliveScreen(onBack = back)
        return
    }
    val context = LocalContext.current
    var iconBatch by rememberSaveable { mutableStateOf(false) }
    var iconSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showIconBatch by rememberSaveable { mutableStateOf(false) }
    val selectingIcons = page == Page.IconLayers && iconBatch
    val selectedIcons = iconSelection.toSet().intersect(controller.apps.map { it.packageName }.toSet())
    fun cancelIconBatch() { iconBatch = false; showIconBatch = false; iconSelection = emptyList() }
    BackHandler(selectingIcons && !showIconBatch) { cancelIconBatch() }
    val iconApp = if (page == Page.IconApp) controller.apps.firstOrNull { it.packageName == appPackage } else null
    var windowExclusions by rememberSaveable(page) { mutableStateOf(false) }
    BackHandler(windowExclusions) { windowExclusions = false }
    var query by rememberSaveable(page) { mutableStateOf("") }
    var searching by rememberSaveable(page) { mutableStateOf(false) }
    var appsTab by rememberSaveable { mutableStateOf(true) }
    val searchable = windowExclusions || when (page) {
        Page.GameMode, Page.Motion, Page.IconLayers, Page.Blocked -> true
        Page.GameQuick -> appsTab
        else -> false
    }
    fun closeSearch() { searching = false; query = "" }
    val searchOpen = searchable && searching
    BackHandler(searchOpen && !showIconBatch) { closeSearch() }
    // Standard Activity navigation leaves predictive Back and its animation with Android.
    SettingsPage(
        title = if (windowExclusions) "不以小窗打开的应用" else when (page) {
            Page.IconLayers -> if (selectingIcons) "已选择 ${selectedIcons.size} 个应用" else page.title
            Page.IconApp -> iconApp?.label ?: "图标设置"
            else -> page.title
        },
        onBack = if (searchOpen) ::closeSearch else if (windowExclusions) ({ windowExclusions = false }) else if (page == Page.Home) null else if (selectingIcons) ({ cancelIconBatch() }) else back,
        expandedTitle = !selectingIcons && !searchOpen,
        titleContent = if (searchOpen) ({ AppQueryField(query) { query = it } }) else null,
        actions = {
            if (searchable && !searchOpen) IconButton(onClick = { searching = true }) {
                Icon(Icons.Default.Search, contentDescription = "搜索应用")
            }
            if (!searchOpen) {
            if (page == Page.Motion) MotionActions()
            if (page == Page.Shade) IconButton(onClick = {
                ShadeStore.setIconScale(context, true, 100)
                ShadeStore.setIconScale(context, false, 100)
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "仅将通知图标和系统状态图标大小恢复为 100%，不更改其他设置")
            }
            if (selectingIcons) {
                TextButton(onClick = {
                    iconSelection = if (selectedIcons.size == controller.apps.size) emptyList()
                        else controller.apps.map { it.packageName }
                }, enabled = controller.catalogLoaded) { Text(if (selectedIcons.size == controller.apps.size) "全不选" else "全选") }
                TextButton(onClick = { showIconBatch = true }, enabled = selectedIcons.isNotEmpty()) { Text("下一步") }
            } else if (page == Page.IconLayers) IconLayerActions(onBatch = { iconBatch = true })
            }
        },
    ) { bottomInset ->
        CompositionLocalProvider(LocalAppQuery provides (if (searchOpen) query else "")) {
        if (windowExclusions) AppPickerScreen(controller, bottomInset = bottomInset, windowExclusions = true)
        else when (page) {
            Page.Calls, Page.Records -> CallBlockingScreen(page == Page.Records, bottomInset, onRecords = { navigate(Page.Records) })
            Page.Sms, Page.SmsRecords -> SmsBlockingScreen(page == Page.SmsRecords, bottomInset, onRecords = { navigate(Page.SmsRecords) })
            Page.EdgeTouch -> EdgeTouchScreen(bottomInset)
            Page.Shade -> ShadeScreen(bottomInset)
            Page.Motion -> MotionScreen(controller.apps, controller.catalogLoaded, bottomInset)
            Page.GameMode -> GameModeScreen(controller.apps, controller.catalogLoaded, bottomInset,
                onQuick = { navigate(Page.GameQuick) }, onBarrage = { navigate(Page.Barrage) }, onCorner = { navigate(Page.GameCorner) })
            Page.GameCorner -> GameCornerScreen(bottomInset)
            Page.GameQuick -> GameQuickScreen(controller.apps, controller.catalogLoaded, bottomInset, onAppTabChanged = {
                appsTab = it
                if (!it) closeSearch()
            })
            Page.Barrage -> BarrageScreen(bottomInset, onHistory = { navigate(Page.BarrageHistory) })
            Page.BarrageHistory -> BarrageHistoryScreen(controller.apps, bottomInset)
            Page.IconLayers -> IconLayerScreen(controller.apps, bottomInset, openIconApp,
                selection = if (selectingIcons) selectedIcons else null, onSelect = { pkg ->
                    iconSelection = if (pkg in selectedIcons) iconSelection - pkg else iconSelection + pkg
                })
            Page.IconApp -> IconAppScreen(iconApp, bottomInset)
            Page.Blocked -> AppPickerScreen(controller, bottomInset = bottomInset)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
                when (page) {
                    Page.Home -> HomeScreen(
                        onWindow = { navigate(Page.Window) }, onAbout = { navigate(Page.About) },
                        onHarassment = { navigate(Page.Harassment) },
                        onEdgeTouch = { navigate(Page.EdgeTouch) },
                        onSuggestions = { navigate(Page.Suggestions) }, onMotion = { navigate(Page.Motion) },
                        onIcons = { navigate(Page.IconLayers) }, onGameMode = { navigate(Page.GameMode) },
                        onShade = { navigate(Page.Shade) }, onAlive = { navigate(Page.Alive) },
                    )
                    Page.Harassment -> {
                        SettingsGroup {
                            SettingRow("来电拦截", onClick = { navigate(Page.Calls) }, leading = { Glyph("phone") })
                            SettingRow("短信拦截", onClick = { navigate(Page.Sms) }, leading = { Glyph("sms") })
                        }
                        GroupTitle("拦截记录")
                        SettingsGroup {
                            SettingRow("来电记录", onClick = { navigate(Page.Records) })
                            SettingRow("短信记录", onClick = { navigate(Page.SmsRecords) })
                        }
                    }
                    Page.Window -> {
                        WindowScreen(controller.preferences, navigate, controller::toggleControl,
                            enablePrompt = controller.enablePrompt, enableChecking = controller.enableChecking,
                            dismissEnablePrompt = controller::dismissEnablePrompt, confirmEnablePrompt = controller::confirmEnablePrompt)
                    }
                    Page.Suggestions -> SuggestionScreen(controller, onSupportedApps = { navigate(Page.SuggestionApps) })
                    Page.SuggestionApps -> SuggestionAppsScreen()
                    Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) },
                        windowExclusions = { windowExclusions = true })
                    Page.About -> AboutScreen(onLicenses = { navigate(Page.Licenses) }, onTools = { navigate(Page.Tools) }, onLogs = { navigate(Page.Logs) })
                    Page.Logs -> {
                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                val c = context.createDeviceProtectedStorageContext()
                                val keys = listOf("floating_window" to "corner", "barrage" to "barrage", "call_blocking" to "call_blocking", "sms_blocking" to "sms_blocking", "edge_touch" to "edge_touch", "suggestions" to "suggestions", "motion" to "motion_access", "icon_layers" to "icon_layers", "shade" to "landscape_shade")
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
    if (selectingIcons && showIconBatch) IconBatchDialog(selectedIcons,
        onDismiss = { showIconBatch = false }, onApplied = {
            cancelIconBatch()
            Toast.makeText(context, "批量设置已保存", Toast.LENGTH_SHORT).show()
        })
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
private fun IconLayerActions(onBatch: () -> Unit) {
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
            DropdownMenuItem(text = { Text("批量设置") }, onClick = { menu = false; onBatch() })
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
    var gameRegions by remember { mutableStateOf(dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_show_regions")) }
    DisposableEffect(context) {
        val prefs = dev.local.sunwindowdemo.features.gamemode.GameModeStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            gameRegions = dev.local.sunwindowdemo.features.gamemode.GameModeStore.read(context).getBoolean("corner_show_regions")
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
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
        ToggleRow("显示游戏防误触范围", value = gameRegions) {
            dev.local.sunwindowdemo.features.gamemode.GameModeStore.showCornerRegions(context, it)
        }
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
    AodWallpaperToggle()
}

@Composable
private fun AodWallpaperToggle() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AodWallpaperControl.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("状态未知") }
    fun operate(target: Boolean? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                state = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    if (target == null) AodWallpaperControl.read(context) else AodWallpaperControl.set(context, target)
                }
                message = if (target == null) {
                    if (state?.wallpaper == true && state?.capability == false)
                        "壁纸设置已开，系统能力未启用"
                    else "已读取系统实际状态"
                } else "已${if (target) "开启" else "关闭"}并读回确认"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state = null
                message = failure.message ?: "无法读取系统状态，请检查 root 授权后重试"
            } finally {
                busy = false
            }
        }
    }
    GroupTitle("实验")
    SettingsGroup {
        ToggleRow("息屏显示壁纸（全景 AOD）",
            message,
            state?.wallpaper == true, enabled = state != null && !busy) { operate(it) }
        if (state?.wallpaper == true && state?.capability == false) {
            SettingRow("重试开启系统壁纸能力", onClick = if (busy) null else ({ operate(true) }))
        }
        SettingRow(if (busy) "正在处理…" else if (state == null) "授权 root 并读取状态" else "重新读取状态",
            onClick = if (busy) null else ({ operate() }))
        DarkAodControls()
    }
}

@Composable
private fun DarkAodControls() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(DarkAodStore.systemSnapshot(context)) }
    ToggleRow("暗处暂停 AOD", value = config.getBoolean("enabled")) {
        DarkAodStore.setEnabled(context, it)
        config = DarkAodStore.systemSnapshot(context)
    }
    SliderRow("暗处光照阈值", "亮处恢复阈值：${config.getFloat("bright_lux").toInt()} lux",
        value = config.getFloat("dark_lux").toInt(), range = 0..5, unit = " lux", steps = 4,
        onCommit = {
            DarkAodStore.setDarkLux(context, it.toFloat())
            config = DarkAodStore.systemSnapshot(context)
        })
}


