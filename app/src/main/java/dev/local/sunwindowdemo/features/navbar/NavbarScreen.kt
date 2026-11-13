package dev.local.sunwindowdemo.features.navbar

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val modes = listOf("保留系统原有", "取色", "悬浮")
private fun modeLabel(mode: Int) = modes.getOrElse(mode) { modes[0] }

@Composable
private fun rememberNavbarSettings(): Bundle? {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val preferences = NavbarStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return produceState<Bundle?>(null, context, revision) {
        value = withContext(Dispatchers.IO) { NavbarStore.systemSnapshot(context) }
    }.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavbarScreen(apps: List<LaunchableApp>, loaded: Boolean, bottomInset: Dp, onApp: (String) -> Unit) {
    val context = LocalContext.current
    val settings = rememberNavbarSettings()
    if (settings == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        return
    }
    var showDefault by remember { mutableStateOf(false) }
    val templates = remember(settings) {
        JSONObject(settings.getString("templates", "{}")).optJSONObject("packages")
    }
    val installed = remember(apps, context.packageName) {
        apps.filter { it.packageName != context.packageName && it.packageName != "com.android.systemui"
                && it.packageName != "com.google.android.apps.nexuslauncher" }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp + bottomInset)) {
        item {
            SettingsGroup {
                ToggleRow("启用导航栏适配", value = settings.getBoolean("enabled", false)) {
                    NavbarStore.setEnabled(context, it)
                }
                ToggleRow("横屏导航条透明", "移除手势导航条背后的系统阴影", settings.getBoolean("landscape_clear", true)) {
                    NavbarStore.setLandscapeClear(context, it)
                }
                SettingRow("通用默认模式", modeLabel(settings.getInt("default_mode", 0)), onClick = { showDefault = true })
            }
            Hint("竖屏按应用或 Activity 选择模式，横屏可保持导航条透明。首次加载或升级后，保留系统框架、SystemUI 和 Pixel 桌面三个作用域并手动重启。普通应用直接选择模式即可生效。")
            GroupTitle("逐应用设置")
        }
        items(installed, key = { it.packageName }) { app ->
            val packageMode = settings.getInt("${app.packageName}/*", -1)
            val activityRules = settings.keySet().count { it.startsWith("${app.packageName}/") && it != "${app.packageName}/*" }
            val source = when {
                packageMode >= 0 -> "应用默认 · ${modeLabel(packageMode)}"
                templates?.optJSONObject(app.packageName) != null -> "内置模板"
                else -> "通用 · ${modeLabel(settings.getInt("default_mode", 0))}"
            }
            SettingsGroup {
                SettingRow(app.label, source + if (activityRules > 0) " · $activityRules 条独立规则" else "",
                    onClick = { onApp(app.packageName) }, leading = { NavbarAppIcon(app) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
            Spacer(Modifier.height(2.dp))
        }
        item {
            if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (installed.isEmpty()) Hint("暂无可显示的应用")
        }
    }
    if (showDefault) ModalBottomSheet(onDismissRequest = { showDefault = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("通用默认模式", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 14.dp))
            NavbarModeRows(settings.getInt("default_mode", 0), null) {
                NavbarStore.setDefaultMode(context, it)
                showDefault = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavbarAppScreen(app: LaunchableApp?, loaded: Boolean, bottomInset: Dp) {
    val context = LocalContext.current
    val settings = rememberNavbarSettings()
    if (!loaded || settings == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        return
    }
    if (app == null) {
        Hint("应用不可用")
        return
    }
    var activities by remember(app.packageName) { mutableStateOf<List<String>?>(null) }
    var version by remember(app.packageName) { mutableLongStateOf(0) }
    var error by remember(app.packageName) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(app.packageName) { mutableStateOf<String?>(null) }
    LaunchedEffect(app.packageName) {
        val info = withContext(Dispatchers.IO) {
            try { context.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES) }
            catch (_: PackageManager.NameNotFoundException) { null }
        }
        version = info?.longVersionCode ?: 0
        activities = info?.activities.orEmpty().map { it.name }.distinct().sorted()
        if (info == null) error = "应用信息读取失败"
    }
    val prefix = "${app.packageName}/"
    val rules = remember(settings) { NavbarRules(Bundle(settings).apply { putBoolean("enabled", true) }) }
    val allActivities = remember(activities, settings, prefix) {
        (activities.orEmpty() + settings.keySet().filter { it.startsWith(prefix) && it != "$prefix*" }
            .map { it.removePrefix(prefix) }).distinct().sorted()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp + bottomInset)) {
        item {
            SettingsGroup { SettingRow(app.label, app.packageName, leading = { NavbarAppIcon(app) }) }
            if (!settings.getBoolean("enabled", false)) Hint("导航栏适配当前关闭，规则将在开启后生效。")
            GroupTitle("应用默认模式")
            NavbarModeRows(settings.getInt("$prefix*", -1), "跟随模板与通用设置") { mode ->
                if (mode == -1) NavbarStore.clearMode(context, app.packageName, "*")
                else NavbarStore.setMode(context, app.packageName, "*", mode)
            }
            GroupTitle("Activity 独立规则")
        }
        items(allActivities, key = { it }) { activity ->
            val resolved = rules.resolve(app.packageName, activity, version)
            SettingsGroup {
                SettingRow(activity.substringAfterLast('.'), "$activity\n${resolved.source()} · ${modeLabel(resolved.mode())}",
                    onClick = { selected = activity },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
            Spacer(Modifier.height(2.dp))
        }
        item {
            if (activities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (allActivities.isEmpty()) Hint("未找到可配置的 Activity")
            error?.let { Hint(it) }
        }
    }
    selected?.let { activity ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(activity.substringAfterLast('.'), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 14.dp))
                NavbarModeRows(settings.getInt(prefix + activity, -1), "跟随应用与模板") { mode ->
                    if (mode == -1) NavbarStore.clearMode(context, app.packageName, activity)
                    else NavbarStore.setMode(context, app.packageName, activity, mode)
                    selected = null
                }
            }
        }
    }
}

@Composable
private fun NavbarModeRows(selected: Int, inherit: String?, choose: (Int) -> Unit) {
    SettingsGroup {
        if (inherit != null) SettingRow(inherit, onClick = { choose(-1) },
            trailing = { RadioButton(selected == -1, null) })
        modes.forEachIndexed { mode, label ->
            SettingRow(label, onClick = { choose(mode) }, trailing = { RadioButton(selected == mode, null) })
        }
    }
}

@Composable
private fun NavbarAppIcon(app: LaunchableApp) {
    val bitmap = remember(app.packageName, app.icon) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}

@Composable
fun NavbarDiagnosticsScreen() {
    val context = LocalContext.current
    val status = remember { context.createDeviceProtectedStorageContext().getSharedPreferences("navbar_status", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(status) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        status.registerOnSharedPreferenceChangeListener(listener)
        onDispose { status.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val snapshot = remember(revision) { status.all }
    SettingsGroup {
        SettingRow("系统宿主", snapshot["host"] as? String ?: "系统宿主尚未连接")
        SettingRow("最近运行状态", snapshot["runtime"] as? String ?: "等待应用窗口")
    }
}
