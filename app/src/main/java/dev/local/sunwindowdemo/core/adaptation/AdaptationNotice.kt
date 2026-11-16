package dev.local.sunwindowdemo.core.adaptation

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home-only, one notice per community record; no capability or permission decisions. */
@Composable
fun AdaptationNotice(onAbout: () -> Unit) {
    val record = remember { CommunityAdaptation.current() } ?: return
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by rememberSaveable(record.id()) { mutableStateOf(false) }
    LaunchedEffect(record, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val seen = withContext(Dispatchers.IO) {
                context.getSharedPreferences("community_adaptation", Context.MODE_PRIVATE)
                    .getBoolean(record.seenKey(), false)
            }
            if (!seen) visible = true
        }
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text("社区适配") },
            text = {
                Text("${record.systemName()} 由 ${record.maintainer()} 提供适配。" +
                    if (record.maintainerFeedbackUrl() == null)
                        "\n暂无已确认的适配者反馈入口，问题请反馈至主项目。"
                    else "\n该系统的兼容性问题，请优先向适配者反馈。")
            },
            confirmButton = { TextButton(onClick = { visible = false }) { Text("知道了") } },
            dismissButton = { TextButton(onClick = { visible = false; onAbout() }) { Text("查看适配者") } },
        )
        // Record only once the notice actually enters composition, not when detection runs.
        // App upgrades keep this stable record key; neither versionCode nor ROM build date is used.
        DisposableEffect(record) {
            context.getSharedPreferences("community_adaptation", Context.MODE_PRIVATE)
                .edit().putBoolean(record.seenKey(), true).apply()
            onDispose { }
        }
    }
}
