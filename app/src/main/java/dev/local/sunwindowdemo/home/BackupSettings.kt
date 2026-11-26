package dev.local.sunwindowdemo.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.backup.SettingsBackup
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local, user-selected documents only; no storage permission or automatic restore. */
@Composable
internal fun BackupSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<SettingsBackup.Snapshot?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) busy = false else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = SettingsBackup.export(context)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入文档" }.use { it.write(bytes) }
                }
                message = "备份已导出。文件为明文，请妥善保管。"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                message = "导出失败，文档可能不完整，请重新选择位置。"
            } finally { busy = false }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false else scope.launch {
            try {
                pending = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文档" }.use(SettingsBackup::read)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                message = "无法恢复：文件损坏、版本不支持或不是有效的设置备份。现有设置未改变。"
            } finally { busy = false }
        }
    }
    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("恢复备份？") },
            text = { Text("已校验 ${backup.groupCount()} 组、${backup.settingCount()} 项设置。恢复将覆盖备份包含的设置组，组内未保存的选项恢复默认；当前未备份的数据保持不变。\n\n功能开关及来电、短信拦截规则可能立即生效。小窗总开关、系统权限、LSPosed 作用域、壁纸及图标素材不恢复。建议先导出当前设置。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            // Finish a confirmed write/rollback even if the About page leaves composition.
                            withContext(NonCancellable + Dispatchers.IO) { SettingsBackup.restore(context, backup) }
                            message = "设置已恢复。若宿主尚未刷新，可重新打开对应功能；小窗总开关保持不变。"
                            pending = null
                        } catch (_: SettingsBackup.NotificationException) {
                            message = "设置已保存，但部分宿主刷新未完成。请重新打开对应功能或稍后重启宿主。"
                            pending = null
                        } catch (_: Exception) {
                            message = "恢复失败，请检查当前设置后重试；如果曾开始写入，已尝试回滚。"
                            pending = null
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "正在恢复…" else "覆盖并恢复") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } },
        )
    }
    GroupTitle("备份与恢复")
    SettingsGroup {
        SettingRow("导出备份", "选择保存位置，导出用户设置为 JSON 文件", onClick = if (busy || pending != null) null else ({
            busy = true
            message = ""
            try { export.launch("NativeComplements-settings-${java.time.LocalDate.now()}.json") }
            catch (_: Exception) { busy = false; message = "无法打开系统文件选择器。" }
        }))
        SettingRow("恢复备份", "选择备份文件，校验后确认覆盖", onClick = if (busy || pending != null) null else ({
            busy = true
            message = ""
            try { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            catch (_: Exception) { busy = false; message = "无法打开系统文件选择器。" }
        }))
    }
    Text("仅备份支持的用户设置，不含日志、拦截记录、临时运行状态、壁纸或图标素材。明文文件可能含应用列表、号码规则及关键词，请勿公开分享。",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (message.isNotEmpty()) Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
}
