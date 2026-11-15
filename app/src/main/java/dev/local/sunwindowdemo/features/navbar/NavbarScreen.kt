package dev.local.sunwindowdemo.features.navbar

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val modes = listOf("保留系统原有", "取色", "悬浮", "自动")
private fun modeLabel(mode: Int) = modes.getOrElse(mode) { modes[0] }

internal fun navbarSearchMatches(query: String, label: String, packageName: String, activity: String = ""): Boolean {
    val term = query.trim()
    val fullActivity = if (activity.startsWith('.')) packageName + activity else activity
    return term.isEmpty() || listOf(label, packageName, fullActivity, "$packageName/$fullActivity",
        "$packageName/${fullActivity.removePrefix(packageName)}").any { it.contains(term, ignoreCase = true) }
}

private data class NavbarActivity(val packageName: String, val label: String, val name: String, val version: Long)

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
    val templates = remember(settings) {
        JSONObject(settings.getString("templates", "{}")).optJSONObject("packages")
    }
    val installed = remember(apps, context.packageName) {
        apps.filter { it.packageName != context.packageName && it.packageName != "com.android.systemui"
                && it.packageName != "com.google.android.apps.nexuslauncher" }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<NavbarActivity?>(null) }
    val activityIndex by produceState<List<NavbarActivity>?>(null, installed) {
        value = null
        value = withContext(Dispatchers.IO) {
            installed.flatMap { app ->
                val info = try { context.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES) }
                    catch (_: PackageManager.NameNotFoundException) { null }
                    catch (_: SecurityException) { null }
                info?.activities.orEmpty().map { NavbarActivity(app.packageName, app.label, it.name, info?.longVersionCode ?: 0) }
            }.distinctBy { "${it.packageName}/${it.name}" }
        }
    }
    val matchingApps = remember(installed, query) { installed.filter { navbarSearchMatches(query, it.label, it.packageName) } }
    val matchingActivities = remember(activityIndex, query) {
        if (query.isBlank()) emptyList() else activityIndex.orEmpty().filter {
            navbarSearchMatches(query, it.label, it.packageName, it.name)
        }
    }
    val rules = remember(settings) { NavbarRules(Bundle(settings).apply { putBoolean("enabled", true) }) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp + bottomInset)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("搜索应用、包名或 Activity") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        if (query.isBlank()) item {
            SettingsGroup {
                ToggleRow("启用导航栏适配", value = settings.getBoolean("enabled", false)) {
                    NavbarStore.setEnabled(context, it)
                }
                ToggleRow("横屏导航条透明", "移除手势导航条背后的系统阴影", settings.getBoolean("landscape_clear", true)) {
                    NavbarStore.setLandscapeClear(context, it)
                }
            }
            Hint("竖屏按应用或 Activity 选择模式，横屏可保持导航条透明。推荐作用域不等于已授权：请在 LSPosed 中确认系统框架、SystemUI、Pixel 桌面及需要适配的推荐应用作用域，变更后手动重启生效；规则由本页总开关统一控制。")
            GroupTitle("逐应用设置")
        }
        items(matchingApps, key = { "app:${it.packageName}" }) { app ->
            val packageMode = settings.getInt("${app.packageName}/*", -1)
            val activityRules = settings.keySet().count { it.startsWith("${app.packageName}/") && it != "${app.packageName}/*" }
            val source = when {
                packageMode >= 0 -> "应用默认 · ${modeLabel(packageMode)}"
                app.packageName.startsWith("com.google.android.") -> "白名单 · 保留系统原有"
                templates?.optJSONObject(app.packageName) != null -> "内置名单"
                else -> "自动"
            }
            SettingsGroup {
                SettingRow(app.label, source + if (activityRules > 0) " · $activityRules 条独立规则" else "",
                    onClick = { onApp(app.packageName) }, leading = { NavbarAppIcon(app) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
            Spacer(Modifier.height(2.dp))
        }
        items(matchingActivities, key = { "activity:${it.packageName}/${it.name}" }) { activity ->
            val resolved = rules.resolve(activity.packageName, activity.name, activity.version)
            SettingsGroup {
                SettingRow(activity.name.substringAfterLast('.'),
                    "${activity.label} · ${activity.packageName}/${activity.name}\n${resolved.source()} · ${modeLabel(resolved.mode())}",
                    onClick = { selected = activity },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
            Spacer(Modifier.height(2.dp))
        }
        item {
            if (!loaded || (query.isNotBlank() && activityIndex == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (matchingApps.isEmpty() && matchingActivities.isEmpty()) Hint(if (query.isBlank()) "暂无可显示的应用" else "未找到匹配的应用或 Activity")
        }
    }
    selected?.let { activity ->
        NavbarActivitySheet(activity.packageName, activity.name, settings) { selected = null }
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
            catch (_: SecurityException) { null }
        }
        version = info?.longVersionCode ?: 0
        activities = info?.activities.orEmpty().map { it.name }.distinct().sorted()
        if (info == null) error = "应用信息读取失败"
    }
    val prefix = "${app.packageName}/"
    val rules = remember(settings) { NavbarRules(Bundle(settings).apply { putBoolean("enabled", true) }) }
    var query by rememberSaveable(app.packageName) { mutableStateOf("") }
    val allActivities = remember(activities, settings, prefix) {
        (activities.orEmpty() + settings.keySet().filter { it.startsWith(prefix) && it != "$prefix*" }
            .map { it.removePrefix(prefix) }).distinct().sorted()
    }
    var status by rememberSaveable(app.packageName) { mutableStateOf("全部") }
    val statusOf = { activity: String ->
        val resolved = rules.resolve(app.packageName, activity, version)
        val own = settings.containsKey(prefix + activity)
        listOf(resolved.source(), modeLabel(resolved.mode()), if (own) "独立规则" else "",
            if (resolved.mode() == NavbarStore.COLOR) "延伸" else "").joinToString(" ")
    }
    val matchingActivities = remember(allActivities, query, status, settings, app.packageName) {
        allActivities.filter { activity ->
            val text = statusOf(activity)
            (status == "全部" || text.contains(status))
                    && (navbarSearchMatches(query, app.label, app.packageName, activity) || (query.isNotBlank() && text.contains(query.trim())))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp + bottomInset)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("筛选 Activity、模式或来源") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in listOf("全部", "独立规则", "自动", "取色", "悬浮", "保留")) {
                    FilterChip(selected = status == option, onClick = { status = option }, label = { Text(option) })
                }
            }
        }
        if (query.isBlank() && status == "全部") item {
            SettingsGroup { SettingRow(app.label, app.packageName, leading = { NavbarAppIcon(app) }) }
            if (!settings.getBoolean("enabled", false)) Hint("导航栏适配当前关闭，规则将在开启后生效。")
            GroupTitle("应用默认模式")
            NavbarModeRows(settings.getInt("$prefix*", -1), "跟随白名单与自动") { mode ->
                if (mode == -1) NavbarStore.clearMode(context, app.packageName, "*")
                else NavbarStore.setMode(context, app.packageName, "*", mode)
            }
            GroupTitle("Activity 独立规则")
        }
        items(matchingActivities, key = { it }) { activity ->
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
            else if (matchingActivities.isEmpty()) Hint(if (query.isBlank()) "未找到可配置的 Activity" else "未找到匹配的 Activity")
            error?.let { Hint(it) }
        }
    }
    selected?.let { activity ->
        NavbarActivitySheet(app.packageName, activity, settings) { selected = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavbarActivitySheet(packageName: String, activity: String, settings: Bundle, dismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(activity.substringAfterLast('.'), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 14.dp))
            NavbarModeRows(settings.getInt("$packageName/$activity", -1), "跟随应用设置") { mode ->
                if (mode == -1) NavbarStore.clearMode(context, packageName, activity)
                else NavbarStore.setMode(context, packageName, activity, mode)
                dismiss()
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
    val settings = rememberNavbarSettings()
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportLearned = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(NavbarStore.exportLearned(context).toByteArray(Charsets.UTF_8))
                    }
                }
            }
            Toast.makeText(context, if (result.isSuccess) "学习结果已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    Column {
        SettingsGroup {
            SettingRow("系统宿主", snapshot["host"] as? String ?: "系统宿主尚未连接")
            SettingRow("最近运行状态", snapshot["runtime"] as? String ?: "等待应用窗口")
        }
        GroupTitle("标注与学习")
        SettingsGroup {
            ToggleRow("标注模式", "屏幕上常驻一个可拖动的面板，显示当前页面的模式；点模式即写入该 Activity 的规则，可撤销",
                settings?.getBoolean("label_mode", false) ?: false) { NavbarStore.setLabelMode(context, it) }
            SettingRow("导出学习结果", "自动模式记下的判断与手动规则，可合并进内置规则",
                onClick = { exportLearned.launch("navbar-learned-${System.currentTimeMillis() / 1000}.json") })
            SettingRow("清除学习结果", onClick = { confirmClear = true })
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清除学习结果？") },
        text = { Text("已记下的页面判断会全部删除，重启后自动模式从头学习。") },
        confirmButton = { TextButton(onClick = {
            NavbarStore.clearLearned(context); confirmClear = false
            Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
        }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
