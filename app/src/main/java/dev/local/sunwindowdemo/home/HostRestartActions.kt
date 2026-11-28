package dev.local.sunwindowdemo.home

import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.LauncherTargets
import dev.local.sunwindowdemo.core.RootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HostRestartActions() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var launcher by rememberSaveable { mutableStateOf(false) }
    var systemUi by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = !busy, onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "重启菜单")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("重启桌面") },
                leadingIcon = { Checkbox(launcher, onCheckedChange = null) },
                onClick = { launcher = !launcher })
            DropdownMenuItem(text = { Text("重启 SystemUI") },
                leadingIcon = { Checkbox(systemUi, onCheckedChange = null) },
                onClick = { systemUi = !systemUi })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("执行所选") }, enabled = launcher || systemUi, onClick = {
                val targets = buildList {
                    if (launcher) add("桌面" to true)
                    if (systemUi) add("SystemUI" to false)
                }
                expanded = false
                busy = true
                scope.launch {
                    try {
                        val messages = withContext(Dispatchers.IO) {
                            if (!RootCommand.requestRoot(context)) return@withContext "未获得 Root 权限"
                            targets.joinToString("\n") { (label, isLauncher) ->
                                try {
                                    val pkg = if (isLauncher) checkNotNull(LauncherTargets.home(context)) {
                                        "未找到支持的系统桌面"
                                    } else "com.android.systemui"
                                    val app = context.packageManager.getApplicationInfo(pkg, 0)
                                    check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                                        "目标不是系统宿主"
                                    }
                                    val result = RootCommand.run(context, hostRestartScript(pkg, app.uid)).trim()
                                    "$label：${if (result == "stopped") "已发送重启请求" else if (result == "idle") "当前未运行" else "未确认执行结果"}"
                                } catch (failed: Exception) {
                                    "$label：${failed.message ?: "执行失败"}"
                                }
                            }
                        }
                        Toast.makeText(context, messages, Toast.LENGTH_LONG).show()
                    } finally { busy = false }
                }
            })
        }
    }
}

/** Fixed host names and the resolved same-user UID; never force-stop a package. */
internal fun hostRestartScript(pkg: String, uid: Int): String {
    require(pkg in setOf(LauncherTargets.PIXEL, LauncherTargets.LINEAGE, "com.android.systemui"))
    require(uid >= 0)
    return """
        pids=${'$'}(pidof '$pkg')
        stopped=0
        for pid in ${'$'}pids; do
            actual=${'$'}(awk '/^Uid:/{print ${'$'}2}' /proc/"${'$'}pid"/status) || exit 1
            [ "${'$'}actual" = '$uid' ] || continue
            kill -9 "${'$'}pid" || exit 1
            stopped=1
        done
        if [ "${'$'}stopped" = 1 ]; then echo stopped; else echo idle; fi
    """.trimIndent()
}
