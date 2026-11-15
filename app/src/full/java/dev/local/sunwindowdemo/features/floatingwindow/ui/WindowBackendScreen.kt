package dev.local.sunwindowdemo.features.floatingwindow.ui

import android.os.Process
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.ModuleIdentity
import dev.local.sunwindowdemo.core.WindowBackendSelection
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pending startup choice, never a live ownership transfer or a mirror of corner.enabled. */
@Composable
fun WindowBackendScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(runCatching { WindowBackendSelection.read(context) }.getOrNull()) }
    var dialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val mainUser = Process.myUid() / 100000 == 0
    SettingsGroup {
        SettingRow("小窗后端", message ?: when (selected) {
            ModuleIdentity.WINDOW_PACKAGE -> "已选择独立小窗，下次完整重启生效；主版保留 Alive 等功能"
            ModuleIdentity.FULL_PACKAGE -> "已选择主版，下次完整重启生效"
            "" -> "尚未明确选择，沿用启动时先到先接管；可选择独立版与 Alive 共存"
            else -> "无法读取已保存选择，未修改"
        }, onClick = if (busy || !mainUser) null else ({ dialog = true }))
        if (!mainUser) SettingRow("仅主用户可选择系统小窗后端")
    }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("选择小窗后端") },
        text = { Text("只在下次完整重启时生效，不会在当前启动中抢占或转移窗口。选择独立版前请同时升级两包，并在 LSPosed 启用独立版及原有系统作用域。两版设置和恢复记录不迁移，重启前请先恢复已有小窗。主版的 Alive 等独立功能仍保留；需要主版小窗后端执行的快捷建议小窗启动、后台唤起及验证码输入不会跨版转交，选择独立版时这些动作暂不可用。") },
        confirmButton = { TextButton(onClick = {
            dialog = false; busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { WindowBackendSelection.save(context, ModuleIdentity.WINDOW_PACKAGE) } }
                busy = false
                if (result.isSuccess) { selected = ModuleIdentity.WINDOW_PACKAGE; message = null }
                else message = "保存失败，未确认切换：${result.exceptionOrNull()?.message}"
            }
        }) { Text("使用独立小窗") } },
        dismissButton = { TextButton(onClick = {
            dialog = false; busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { WindowBackendSelection.save(context, ModuleIdentity.FULL_PACKAGE) } }
                busy = false
                if (result.isSuccess) { selected = ModuleIdentity.FULL_PACKAGE; message = null }
                else message = "保存失败，未确认切换：${result.exceptionOrNull()?.message}"
            }
        }) { Text("使用主版小窗") } },
    )
}
