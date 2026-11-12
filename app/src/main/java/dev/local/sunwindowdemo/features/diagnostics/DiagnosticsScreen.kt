package dev.local.sunwindowdemo.features.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Content only: parent owns SettingsPage title, navigation and system insets. */
@Composable
fun DiagnosticsScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    LaunchedEffect(context) { DiagnosticsStore.connect(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DiagnosticsStore.state()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) { DiagnosticsStore.export(context, uri) }
                    "已导出到所选文档；请自行决定是否分享。"
                } catch (_: Exception) {
                    "导出失败（文档可能不完整），请重新选择位置。"
                } finally { busy = false }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) { state = DiagnosticsStore.state(); delay(500) }
    }
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模块诊断 · 本地记录")
        Text("开始记录 → 复现问题 → 停止 → 导出 ZIP。仅记录接入本功能的模块事件和安全状态，不是完整系统日志；未采集 logcat、LSPosed 系统范围日志，不请求 su，不自动上传。")
        Text("不收集通知/消息正文、电话号码、剪贴板、SSID、原始异常或完整设置。导出包含模块版本、SDK、机型、已观察到的模块进程及明确接入的设置布尔值。")
        Text("最多 10 分钟 / 256 KiB，达到任一上限自动停止。仅内存保留，停止后最多 24 小时；模块应用进程退出即丢失。再次开始会覆盖旧记录。已导出的文件由你管理，不受自动清理控制。")
        Text(if (state.getBoolean("recording")) "正在记录 · ${state.getInt("bytes")} 字节" else "已停止 · ${state.getInt("bytes")} 字节")
        Text("已观察模块进程：${state.getString("seen").orEmpty().ifEmpty { "未知（尚未接入/未上报不代表未加载）" }}")
        Button(enabled = !busy && !state.getBoolean("recording"), onClick = {
            DiagnosticsStore.start()
            Diagnostics.event(context, Diagnostics.APP, "process_loaded")
            state = DiagnosticsStore.state(); message = "请等相关模块进程出现在已观察列表后复现问题，再停止记录。"
        }) { Text("开始记录（覆盖旧记录）") }
        Button(enabled = !busy && state.getBoolean("recording"), onClick = {
            DiagnosticsStore.stop(); state = DiagnosticsStore.state()
        }) { Text("停止") }
        OutlinedButton(enabled = !busy && !state.getBoolean("recording") && state.getInt("bytes") > 0,
            onClick = { export.launch("native-complements-diagnostics.zip") }) { Text("选择位置并导出 ZIP") }
        OutlinedButton(enabled = !busy, onClick = {
            DiagnosticsStore.clear(); state = DiagnosticsStore.state(); message = "本地记录已删除。"
        }) { Text("删除本地记录") }
        if (message.isNotEmpty()) Text(message)
    }
}
