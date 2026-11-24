package dev.local.sunwindowdemo.features.diagnostics

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.RootCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ROOT_REQUIRED_MESSAGE = "未获 Root 授权，无法记录。请在 Root 管理器中允许本应用后重试。"

/** Shared by the confirmation action and its permission-gating regression test. */
internal suspend fun requestDiagnosticsStart(requestRoot: suspend () -> Boolean, start: suspend () -> Unit): String {
    if (!requestRoot()) return ROOT_REQUIRED_MESSAGE
    start()
    return "正在启动记录…"
}

internal fun diagnosticsStatusText(state: Bundle): String {
    val status = when (state.getString("status")) {
        "starting" -> "正在启动"
        "recording" -> "正在记录"
        "stopping" -> "正在停止，请稍候"
        "stopped" -> "已停止"
        "interrupted" -> "记录已中断，日志不完整"
        "error" -> "记录失败，日志可能不完整"
        "expired" -> "本地记录已过期"
        else -> "尚未记录"
    }
    val reason = when (state.getString("reason")) {
        "user_stop" -> "手动停止"
        "duration_limit" -> "已达 10 分钟上限，日志已截断"
        "size_limit" -> "已达 64 MiB 上限，日志已截断"
        "root_denied_or_unavailable" -> "Root 不可用或授权已失效，未获得完整日志"
        "capture_failed" -> "日志进程异常，日志不完整"
        "storage_failed" -> "本地保存失败，日志可能不完整"
        "service_destroyed", "process_interrupted" -> "记录服务或进程中断，日志不完整"
        "start_failed" -> "启动失败，未获得完整日志"
        "local_expired" -> "本地保留时间已到"
        else -> ""
    }
    val seconds = state.getLong("elapsedMs").coerceAtLeast(0) / 1000
    val bytes = state.getLong("bytes").coerceAtLeast(0)
    return "$status · ${seconds / 60} 分 ${seconds % 60} 秒 · $bytes 字节 / 64 MiB" +
        if (reason.isEmpty()) "" else "\n$reason"
}

/** Content only: parent owns SettingsPage title, navigation and system insets. */
@Composable
fun DiagnosticsScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val application = context.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Bundle()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val active = state.getBoolean("starting") || state.getBoolean("recording") || state.getBoolean("stopping")
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) busy = false else scope.launch {
            message = try {
                withContext(Dispatchers.IO) { DiagnosticsStore.export(application, uri) }
                "已导出 ZIP，分享前请检查隐私信息。"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                "导出失败（文档可能不完整），请重新选择位置。"
            } finally { busy = false }
        }
    }
    LaunchedEffect(application) {
        withContext(Dispatchers.IO) { DiagnosticsStore.connect(application) }
        while (true) {
            state = withContext(Dispatchers.IO) { DiagnosticsStore.state() }
            loaded = state.getBoolean("loaded")
            delay(500)
        }
    }
    if (confirmStart) AlertDialog(
        onDismissRequest = { confirmStart = false },
        title = { Text("开始记录系统日志？") },
        text = { Text("需要 Root，将记录开始后的全部可读系统日志，并覆盖上次记录。日志可能含消息、号码等隐私，未经脱敏；仅本地保存，不自动上传，分享前请检查。") },
        confirmButton = {
            TextButton(onClick = {
                confirmStart = false
                busy = true
                message = "正在等待 Root 授权…"
                scope.launch {
                    message = try {
                        requestDiagnosticsStart({ RootCommand.requestRoot(application) }) {
                            withContext(Dispatchers.IO) { DiagnosticsStore.start(application) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        "启动失败，请检查 Root 授权后重试。"
                    } finally {
                        state = withContext(Dispatchers.IO) { DiagnosticsStore.state() }
                        busy = false
                    }
                }
            }) { Text("开始记录") }
        },
        dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("取消") } }
    )
    Column(Modifier.fillMaxWidth().padding(bottom = bottomInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("开始记录 → 复现问题 → 停止 → 导出 ZIP")
        Text("需 Root；后台可继续记录，最多 10 分钟 / 64 MiB。")
        Text("记录停止 24 小时后过期，应用下次运行时清理；导出文件需自行删除。")
        Text(diagnosticsStatusText(state))
        if (!active && state.getBoolean("canExport") && state.getLong("bytes") == 0L) {
            Text("未取得系统日志正文，请检查导出包中的错误说明。")
        }
        Button(enabled = loaded && !busy && !active, onClick = { confirmStart = true }) { Text("开始记录") }
        Button(enabled = loaded && !busy && !state.getBoolean("stopping") && (state.getBoolean("recording") || state.getBoolean("starting")), onClick = {
            busy = true
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) { DiagnosticsStore.stop() }
                    "正在停止并保存…"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { "停止请求失败，请重试或使用通知中的停止按钮。"
                } finally {
                    state = withContext(Dispatchers.IO) { DiagnosticsStore.state() }
                    busy = false
                }
            }
        }) { Text("停止") }
        OutlinedButton(enabled = loaded && !busy && !active && state.getBoolean("canExport"), onClick = {
            busy = true
            try { export.launch("native-complements-diagnostics.zip") }
            catch (_: Exception) { busy = false; message = "无法打开文件选择器，请稍后重试。" }
        }) { Text("导出 ZIP") }
        OutlinedButton(enabled = loaded && !busy && !active, onClick = {
            busy = true
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) { DiagnosticsStore.clear() }
                    "本地记录已删除，已导出文件不受影响。"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { "删除失败，请稍后重试。"
                } finally {
                    state = withContext(Dispatchers.IO) { DiagnosticsStore.state() }
                    busy = false
                }
            }
        }) { Text("删除本地记录") }
        if (busy) Text("正在处理，请稍候…")
        if (message.isNotEmpty()) Text(message)
    }
}
