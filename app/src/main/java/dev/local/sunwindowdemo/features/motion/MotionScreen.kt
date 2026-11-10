package dev.local.sunwindowdemo.features.motion

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun MotionScreen(apps: List<LaunchableApp>, loaded: Boolean) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(MotionStore.read(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<LaunchableApp?>(null) }
    var batchMenu by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val prefs = MotionStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = MotionStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val packages = config.getStringArray("packages") ?: emptyArray()
    val modes = config.getIntArray("modes") ?: intArrayOf()
    val selected = packages.mapIndexed { i, p -> p to modes.getOrElse(i) { 0 } }.toMap()
    val defaultMode = config.getInt("default_mode", 0)
    val enabled = config.getBoolean("enabled")
    val visible = remember(apps, query, selected, defaultMode) {
        apps.filter { it.packageName !in setOf("dev.local.sunwindowdemo", "com.android.systemui", "com.google.android.apps.nexuslauncher") &&
            (it.label.contains(query, true) || it.packageName.contains(query, true)) }
            .sortedByDescending { selected[it.packageName] ?: defaultMode }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("防摇一摇", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                Box {
                    TextButton(onClick = { batchMenu = true }) { Text("全部设置") }
                    DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                        listOf("全部允许使用", "全部前 7 秒限制", "全部始终限制").forEachIndexed { mode, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                MotionStore.all(context, mode); batchMenu = false
                            })
                        }
                    }
                }
            } }
            item { SettingsGroup { ToggleRow("防摇一摇广告", "默认：${modeLabel(defaultMode)}", enabled) { MotionStore.enabled(context, it) } } }
            item {
                val seen = config.getLong("backend_seen")
                val error = config.getString("backend_error", "") ?: ""
                val connected = config.getString("backend_version") == BuildConfig.VERSION_NAME && seen > 0 &&
                    SystemClock.elapsedRealtime() - seen in 0..90_000L
                Hint(when { error.isNotBlank() -> error; connected -> "系统传感器服务已连接"; else -> "等待系统后端连接；更新模块后需重启生效" })
            }
            item { Hint("进入前台的前 7 秒限制，或始终限制。此版本使用系统按应用暂停传感器的接口，会同时影响重力、陀螺仪等其他传感器；依赖体感操作的游戏可保持允许。系统自动旋转不受影响。共享 UID 的应用使用更严格的规则。") }
            item { Hint("全部设置会覆盖当前单独规则，新安装应用继承该设置；之后可逐个调整。总开关开启后生效。") }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true,
                placeholder = { Text("搜索应用或包名") }) }
            if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (loaded && visible.isEmpty()) item { Hint("没有匹配的应用") }
            items(visible, key = { it.packageName }) { app ->
                SettingRow(app.label, modeLabel(selected[app.packageName] ?: defaultMode), onClick = { editing = app }, leading = { MotionAppIcon(app) })
            }
            item { Hint("更改后自动保存。7 秒结束或关闭总开关会恢复应用的原生传感器状态。限制期间依赖传感器的功能也会暂停。") }
        }
    }
    editing?.let { app ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(app.label) }, text = {
            Column {
                for (mode in 0..2) Row(Modifier.fillMaxWidth().clickable {
                    MotionStore.mode(context, app.packageName, mode); editing = null
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton((selected[app.packageName] ?: defaultMode) == mode, onClick = null)
                    Text(modeLabel(mode), Modifier.padding(start = 12.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
}
private fun modeLabel(mode: Int) = when (mode) { 1 -> "进入前台前 7 秒限制"; 2 -> "始终限制"; else -> "允许使用" }
@Composable
private fun MotionAppIcon(app: LaunchableApp) {
    val bitmap = remember(app.packageName, app.icon) { app.icon?.let { drawable ->
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            val old = android.graphics.Rect(drawable.bounds)
            drawable.setBounds(0, 0, 96, 96); drawable.draw(Canvas(it)); drawable.bounds = old
        }.asImageBitmap()
    } }
    if (bitmap == null) Glyph("gesture") else Image(bitmap, null, Modifier.size(42.dp))
}
