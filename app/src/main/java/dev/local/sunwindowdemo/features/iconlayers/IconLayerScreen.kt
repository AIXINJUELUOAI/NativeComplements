package dev.local.sunwindowdemo.features.iconlayers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconLayerScreen(apps: List<LaunchableApp>, bottomInset: Dp = 0.dp, onApp: (String) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(IconLayerStore.read(context)) }
    val installed = apps
    var library by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var fraction by remember { mutableFloatStateOf(-1f) }
    var pending by remember { mutableStateOf<IconArchive?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { cancel.set(true); if (!busy) pending?.close() } }
    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { IconLayerStore.read(context) }
            library = withContext(Dispatchers.IO) { IconLibrary.packages(context, state.getString("generation", "")).toSet() }
            delay(1500)
        }
    }
    val reporter = remember(scope) { IconArchive.Progress { text, done, total ->
        scope.launch { progress = text; fraction = if (total > 0) done.toFloat() / total else -1f }
    } }
    fun refreshState() { state = IconLayerStore.read(context) }
    fun install(archive: IconArchive, source: IconArchive.Source) {
        pending = null; busy = true; cancel.set(false)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try { IconLibrary.install(context, archive, source, cancel, reporter) } finally { archive.close() }
                }
                message = result.toString(); refreshState()
            } catch (_: CancellationException) { message = "导入已取消，原图标库已保留" }
            catch (e: Exception) { message = e.message ?: "导入失败，原图标库已保留" }
            finally { busy = false }
        }
    }
    fun prepare(uri: Uri, tree: Boolean) {
        busy = true; cancel.set(false); message = ""; fraction = -1f
        scope.launch {
            var archive: IconArchive? = null
            try {
                archive = withContext(Dispatchers.IO) { IconLibrary.prepare(context, uri, tree, cancel, reporter) }
                val ready = archive!!; busy = false
                if (ready.sources.size == 1) install(ready, ready.sources.first()) else pending = ready
            } catch (_: CancellationException) { archive?.close(); message = "导入已取消，原图标库已保留"; busy = false }
            catch (e: Exception) { archive?.close(); message = e.message ?: "读取失败"; busy = false }
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) prepare(it, false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) prepare(it, true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 32.dp + bottomInset)) {
            item {
                SettingsGroup {
                    ToggleRow("启用图标适配", value = state.getBoolean("enabled")) {
                        IconLayerStore.enabled(context, it); refreshState()
                    }
                    ToggleRow("通知图标替换", value = state.getBoolean("notifications")) {
                        IconLayerStore.notifications(context, it); refreshState()
                    }
                    SettingRow("导入图标库",
                        if (state.getInt("count") > 0) "${state.getInt("count")} 个图标 · 已安装匹配 ${installed.count { it.packageName in library }} 个" else "ZIP 或文件夹",
                        onClick = if (busy || pending != null) null else { { showImport = true } },
                        leading = { Icon(Icons.Default.Add, null) })
                }
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    if (fraction < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(progress, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { cancel.set(true) }) { Text("取消导入") }
                }
                if (message.isNotBlank()) Hint(message)
                GroupTitle("逐应用设置")
            }
            items(installed, key = { it.packageName }) { app ->
                val policy = remember(app.packageName, state.getString("policies"), state.getStringArrayList("originals").toString()) { IconPolicy.read(context, app.packageName) }
                SettingsGroup {
                    SettingRow(app.label, when (policy.color) {
                        IconPolicy.MANUAL -> "手动前后景"
                        IconPolicy.ALGORITHM -> "算法补全"
                        IconPolicy.ORIGINAL -> "彩色图标不处理"
                        else -> if (app.packageName in library) "自动 · 素材库" else "自动 · 应用原图"
                    }, onClick = { onApp(app.packageName) }, leading = { IconRowAppIcon(app) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                }
                Spacer(Modifier.height(2.dp))
            }
            item {
                if (installed.isEmpty()) Hint("暂无可显示的应用")
                if (!state.getString("error").isNullOrBlank()) Hint(state.getString("error")!!)
            }
        }
    }
    if (showImport) ModalBottomSheet(onDismissRequest = { showImport = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("导入图标库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            SettingsGroup {
                SettingRow("选择 ZIP 文件", onClick = {
                    showImport = false
                    zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                })
                SettingRow("选择文件夹", onClick = {
                    showImport = false
                    folderPicker.launch(null)
                })
            }
        }
    }
    pending?.let { archive ->
        fun dismiss() { pending = null; scope.launch(Dispatchers.IO) { archive.close() } }
        AlertDialog(onDismissRequest = { dismiss() }, title = { Text("选择图标源") },
            text = { LazyColumn { items(archive.sources) { source -> TextButton(onClick = { install(archive, source) }) { Text("${source.title} · ${source.completeCount()} 个", Modifier.fillMaxWidth()) } } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { dismiss() }) { Text("取消") } })
    }
}

@Composable
fun IconDiagnosticsScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(IconLayerStore.read(context)) }
    SettingsGroup {
        SettingRow("桌面连接状态", "${state.getString("status")} · 已加载 ${state.getInt("loaded")} 个")
        SettingRow("刷新状态", onClick = { state = IconLayerStore.read(context) })
    }
    GroupTitle("图标分层实验")
    SettingsGroup {
        listOf("保留 Demo 原图", "粉色双层 Demo", "薄荷色双层 Demo").forEachIndexed { index, title ->
            SettingRow(title, onClick = { IconLayerStore.save(context, index); state = IconLayerStore.read(context) },
                trailing = { RadioButton(state.getInt("mode") == index, null) })
        }
    }
    if (!state.getString("error").isNullOrBlank()) Hint(state.getString("error")!!)
}

@Composable
private fun IconRowAppIcon(app: LaunchableApp) {
    val bitmap = remember(app.packageName, app.icon) {
        app.icon?.let { drawable ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(Canvas(it))
                drawable.bounds = old
            }.asImageBitmap()
        }
    }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}
