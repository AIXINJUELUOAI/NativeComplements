package dev.local.sunwindowdemo.features.background

import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.AppListIcon
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.Hint
import dev.local.sunwindowdemo.core.ui.IconToggleRow
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.SliderRow
import dev.local.sunwindowdemo.core.ui.ToggleRow
import dev.local.sunwindowdemo.features.background.BackgroundPolicy.*
import dev.local.sunwindowdemo.features.gamemode.AppListPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun <T> backgroundSettings(read: (android.content.Context) -> T): T {
    val context = LocalContext.current
    val reader by rememberUpdatedState(read)
    var settings by remember(context) { mutableStateOf(read(context)) }
    DisposableEffect(context) {
        val prefs = BackgroundStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            settings = reader(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        settings = reader(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

@Composable
fun BackgroundWakeLockScreen(apps: List<LaunchableApp>, loaded: Boolean, bottom: Dp) {
    val context = LocalContext.current
    val rules = backgroundSettings { BackgroundStore.wakeLockSettings(it) }
    val eligible by appCatalog(apps, manageable = true)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    AppListPicker(eligible.orEmpty(), "第三方应用", bottom, loaded = loaded && eligible != null,
        modifier = Modifier.widthIn(max = 680.dp).padding(horizontal = 16.dp), header = {
            SettingsGroup {
                ToggleRow("限制后台唤醒锁", "适用于所有受支持的第三方应用", rules.enabled()) {
                    BackgroundStore.wakeLocksEnabled(context, it)
                }
            }
        }
    ) { app ->
        val rule = rules.rule(app.packageName)
        val open = expanded == app.packageName
        fun save(rule: BackgroundStore.WakeLockRule?) = BackgroundStore.wakeLockRule(context, app.packageName, rule)
        SettingsGroup {
            val status = when {
                rule == null -> "已豁免"
                rules.overrides().containsKey(app.packageName) -> "自定义"
                else -> "默认规则"
            }
            SettingRow(app.label, status, onClick = { expanded = if (open) null else app.packageName },
                leading = { AppListIcon(app) })
            if (open) {
                ToggleRow("限制此应用", value = rule != null) {
                    save(if (it) BackgroundStore.DEFAULT_WAKE_LOCK_RULE else null)
                }
            }
            if (open && rule != null) {
                SliderRow("退到后台后等待", value = rule.delaySeconds(), range = 0..120, unit = " 秒", steps = 23) {
                    save(BackgroundStore.WakeLockRule(it, rule.budgetSeconds(), rule.periodSeconds()))
                }
                val maxBudget = minOf(60, rule.periodSeconds() - 1)
                SliderRow("每周期可保持清醒", value = rule.budgetSeconds(), range = 1..maxBudget, unit = " 秒",
                    steps = maxBudget - 2) {
                    save(BackgroundStore.WakeLockRule(rule.delaySeconds(), it, rule.periodSeconds()))
                }
                SliderRow("周期长度", value = rule.periodSeconds(), range = 30..300, unit = " 秒", steps = 8) {
                    save(BackgroundStore.WakeLockRule(rule.delaySeconds(), minOf(rule.budgetSeconds(), it - 1), it))
                }
            }
        }
    }
}

@Composable
fun BackgroundAppActions(pkg: String) {
    val context = LocalContext.current
    var shown by remember(pkg) { mutableStateOf(false) }
    TextButton(onClick = { BackgroundStore.batch(context, pkg, null) }) {
        Text("恢复默认")
    }
    Box {
        IconButton(onClick = { shown = true }) {
            Icon(Icons.Default.MoreVert, "批量设置进程")
        }
        DropdownMenu(shown, onDismissRequest = { shown = false }) {
            DropdownMenuItem(text = { Text("全部放行") }, onClick = {
                shown = false
                BackgroundStore.batch(context, pkg, Action.ALLOW)
            })
            DropdownMenuItem(text = { Text("全部冻结") }, onClick = {
                shown = false
                BackgroundStore.batch(context, pkg, Action.FREEZE)
            })
        }
    }
}

@Composable
private fun appCatalog(apps: List<LaunchableApp>, manageable: Boolean): State<List<LaunchableApp>?> {
    val context = LocalContext.current
    return produceState<List<LaunchableApp>?>(null, apps, context, manageable) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val registered = apps.associateBy { it.packageName }
            val installed = pm.getInstalledApplications(0)
            val candidates = if (manageable) installed.groupBy { it.uid }.values.mapNotNull { it.singleOrNull() } else installed
            candidates.filter { if (manageable) BackgroundStore.eligible(it) else BackgroundStore.userInstalled(it) }.map { info ->
                registered[info.packageName] ?: LaunchableApp(info.packageName,
                    runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    null)
            }
        }
    }
}

@Composable
fun BackgroundList(apps: List<LaunchableApp>, loaded: Boolean, bottom: Dp, openApp: (String) -> Unit) {
    val context = LocalContext.current
    val settings = backgroundSettings { BackgroundStore.settings(it) }
    val eligible by appCatalog(apps, manageable = true)
    AppListPicker(eligible.orEmpty(), "第三方应用", bottom, loaded = loaded && eligible != null,
        modifier = Modifier.widthIn(max = 680.dp).padding(horizontal = 16.dp), header = {
        SettingsGroup {
            ToggleRow("启用墓碑", value = settings.enabled()) {
                BackgroundStore.enabled(context, it)
            }
        }
    }) { app ->
        val mode = settings.rule(app.packageName).mode()
        SettingsGroup {
            SettingRow(app.label, leading = { AppListIcon(app) }, onClick = { openApp(app.packageName) }, trailing = {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { choosing = true }) {
                        Text(modeLabel(mode))
                        Icon(Icons.Default.ArrowDropDown, "${app.label}的后台模式")
                    }
                    DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                        Mode.entries.forEach { value ->
                            DropdownMenuItem(text = { Text(modeLabel(value)) }, onClick = {
                                BackgroundStore.mode(context, app.packageName, value)
                                choosing = false
                            })
                        }
                    }
                }
            })
        }
    }
}

@Composable
fun BackgroundAppScreen(pkg: String?) {
    val context = LocalContext.current
    if (pkg == null) return
    val rule = backgroundSettings { BackgroundStore.settings(it) }.rule(pkg)
    var observed by remember(context, pkg) { mutableStateOf(BackgroundStore.observedProcesses(context, pkg)) }
    DisposableEffect(context, pkg) {
        val prefs = BackgroundStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BackgroundStore.OBSERVED + pkg) observed = BackgroundStore.observedProcesses(context, pkg)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val processes by produceState<Pair<String, List<String>>?>(null, pkg, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
                val app = info.applicationInfo ?: return@runCatching null
                if (!BackgroundStore.eligible(app)) return@runCatching null
                val main = app.processName ?: pkg
                main to (listOf(main) + info.activities.orEmpty().mapNotNull { it.processName } +
                    info.services.orEmpty().mapNotNull { it.processName } + info.receivers.orEmpty().mapNotNull { it.processName } +
                    info.providers.orEmpty().mapNotNull { it.processName }).distinct()
            }.getOrNull() ?: ("" to emptyList())
        }
    }
    val details = processes
    if (details == null) {
        LinearProgressIndicator()
        return
    }
    val (main, declared) = details
    if (declared.isEmpty()) {
        Hint("未找到可配置的应用进程。")
        return
    }
    SettingsGroup {
        val description = if (rule.keepActive() && BackgroundPolicy.action(rule, pkg, main, main) == Action.FREEZE)
            "主进程当前设为冻结。放行后才能保持后台活跃。"
        else "主进程默认放行，放行的进程尽量保持后台活跃。"
        ToggleRow("不进缓存", description, value = rule.keepActive()) {
            BackgroundStore.keepActive(context, pkg, it)
        }
        if (rule.mode() == Mode.STRICT && BackgroundPolicy.supportsPlaybackOverride(pkg)) {
            ToggleRow("后台播放保护", "开启后，抖音在后台播放时可暂缓冻结。", value = rule.playbackProtected()) {
                BackgroundStore.protectPlayback(context, pkg, it)
            }
        }
    }
    GroupTitle("进程操作")
    SettingsGroup {
        val names = listOf(main) + (declared + observed + rule.actions().keys).toSortedSet().filter { it != main }
        for (name in names) {
            val choices = listOf(null, Action.ALLOW, Action.FREEZE) +
                if (name == main) emptyList() else listOf(Action.RECLAIM, Action.TRIM)
            choiceRow(name, actionLabel(BackgroundPolicy.action(rule, pkg, name, main)), choices, ::actionLabel) {
                BackgroundStore.process(context, pkg, name, it)
            }
        }
    }
    Hint(when (rule.mode()) {
        Mode.STRICT -> if (rule.playbackProtected())
            "退到后台约 5 秒后尝试冻结。放行进程、小窗、媒体播放和 GPS 受保护。"
        else
            "离开前台或关闭小窗约 5 秒后尝试冻结。放行进程和 GPS 受保护。"
        Mode.DEFAULT -> "进程符合系统的后台冻结条件后，再等待约 5 秒。"
        Mode.SYSTEM -> "冻结和裁切在进程符合系统后台条件时执行。"
    })
    if (rule.actions().containsValue(Action.RECLAIM)) {
        Hint("低内存时裁切：平时先冻结，手机内存持续紧张时才尝试结束该进程。")
    }
}

@Composable
private fun <T> choiceRow(title: String, current: String, choices: List<T>, label: (T) -> String,
                          save: (T) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, current, onClick = { shown = true })
        DropdownMenu(shown, onDismissRequest = { shown = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(label(choice)) }, onClick = { shown = false; save(choice) }) }
        }
    }
}

private fun modeLabel(mode: Mode) = when (mode) {
    Mode.SYSTEM -> "系统"
    Mode.DEFAULT -> "默认"
    Mode.STRICT -> "严格"
}

private fun actionLabel(action: Action?) = when (action) {
    null -> "跟随应用设置"
    Action.ALLOW -> "放行"
    Action.FREEZE -> "冻结"
    Action.RECLAIM -> "低内存时裁切"
    Action.TRIM -> "结束（裁切）"
}
