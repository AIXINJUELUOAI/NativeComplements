package dev.local.sunwindowdemo.features.iconlayers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun IconLayerScreen(apps: List<LaunchableApp>) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(IconLayerStore.read(context)) }
    var packages by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var fraction by remember { mutableFloatStateOf(-1f) }
    var pending by remember { mutableStateOf<IconArchive?>(null) }
    var removeDialog by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { cancel.set(true); if (!busy) pending?.close() } }
    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { IconLayerStore.read(context) }
            packages = withContext(Dispatchers.IO) { IconLibrary.packages(context, state.getString("generation", "")).toSet() }
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
                    try { IconLibrary.install(context, archive, source, cancel, reporter) }
                    finally { archive.close() }
                }
                message = result.toString(); refreshState()
                packages = withContext(Dispatchers.IO) { IconLibrary.packages(context, state.getString("generation", "")).toSet() }
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
                withContext(Dispatchers.IO) { archive = IconLibrary.prepare(context, uri, tree, cancel, reporter) }
                val ready = archive!!
                busy = false
                if (ready.sources.size == 1) install(ready, ready.sources.first()) else pending = ready
            } catch (_: CancellationException) { archive?.close(); message = "导入已取消，原图标库已保留"; busy = false }
            catch (e: Exception) { archive?.close(); message = e.message ?: "读取失败"; busy = false }
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) prepare(it, false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) prepare(it, true) }
    val originals = state.getStringArrayList("originals")?.toSet().orEmpty()
    val matched = remember(apps, packages, query) {
        apps.filter { it.packageName in packages && (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }.sortedBy { it.label }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("图标补全", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 28.dp), style = MaterialTheme.typography.headlineLarge)
                SettingsGroup {
                    ToggleRow("使用导入图标", "匹配本地图标库；未覆盖的应用保持原图", state.getBoolean("enabled")) {
                        IconLayerStore.enabled(context, it); refreshState()
                    }
                    SettingRow(if (state.getInt("count") > 0) state.getString("title") ?: "当前图标库" else "尚未导入图标库",
                        if (state.getInt("count") > 0) "${state.getInt("count")} 个图标 · 已安装匹配 ${apps.count { it.packageName in packages }} 个" else "素材由用户自行获取并导入，模块不内置第三方素材")
                    SettingRow("导入本地 ZIP", "支持包名目录、基础图标和主题选择", onClick = if (busy || pending != null) null else { { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) } })
                    SettingRow("导入本地文件夹", "选择含包名子目录的文件夹", onClick = if (busy || pending != null) null else { { folderPicker.launch(null) } })
                }
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    if (fraction < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(progress, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { cancel.set(true) }) { Text("取消导入") }
                }
                if (message.isNotBlank()) Hint(message)
                Hint("包名/0.png 为背景，包名/1.png 为透明前景。可保留 icons 或仓库外层目录；多主题会先让你选择。仅导入静态双层 PNG。")
                GroupTitle("图标管理")
                if (packages.isNotEmpty()) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索已匹配的应用") })
                    Spacer(Modifier.height(12.dp))
                    Hint("预览显示导入效果。关闭单个开关即可让该应用使用原图。")
                }
            }
            items(matched, key = { it.packageName }) { app ->
                val using = app.packageName !in originals
                SettingsGroup {
                    SettingRow(app.label, app.packageName, leading = { ImportedPreview(state.getString("generation", ""), app, using) },
                        trailing = { Switch(using, onCheckedChange = { IconLayerStore.original(context, app.packageName, !it); refreshState() }) })
                }
                Spacer(Modifier.height(2.dp))
            }
            item {
                if (packages.isNotEmpty() && matched.isEmpty()) Hint(if (query.isBlank()) "当前没有已安装应用匹配此图标库" else "未找到匹配的应用")
                GroupTitle("桌面与素材库")
                SettingsGroup {
                    SettingRow("刷新图标", "重新生成桌面图标，保留排列、文件夹和小组件", onClick = {
                        IconLayerStore.refreshAll(context); message = "已请求刷新全部图标，桌面可能需要稍等片刻"
                    })
                    if (state.getInt("count") > 0) SettingRow("移除图标库", "恢复应用原始图标", onClick = if (busy) null else { { removeDialog = true } })
                    SettingRow("桌面连接状态", if (state.getString("version") == BuildConfig.VERSION_NAME)
                        "${state.getString("status")}\n已启用 ${state.getInt("loaded")} 个 · 读取失败 ${state.getInt("loadFailures")} 个"
                        else "等待本版 Hook。更新 APK 后需重启桌面进程或手机。")
                    SettingRow("实验与诊断", "显示 Demo 分层对照和动画计数", onClick = { diagnostics = !diagnostics })
                }
                if (!state.getString("error").isNullOrBlank()) Hint(state.getString("error")!!)
                if (diagnostics) {
                    GroupTitle("Demo 图标对照")
                    SettingsGroup {
                        listOf("保留 Demo 原图", "粉色双层 Demo", "薄荷色双层 Demo").forEachIndexed { index, title ->
                            SettingRow(title, onClick = { IconLayerStore.save(context, index); refreshState() }, trailing = { RadioButton(state.getInt("mode") == index, null) })
                        }
                    }
                    Hint("图标替换 ${state.getInt("replacements")} 次 · 原生动画分层确认 ${state.getInt("animations")} 次。视觉衔接仍需观察。")
                }
                Hint("沿用已验证的 Pixel Launcher Hook，仅处理当前个人用户的桌面图标。关闭桌面主题图标，避免单色显示影响对照。其他图标模块可能覆盖效果。")
            }
        }
    }
    pending?.let { archive ->
        fun dismiss() { pending = null; scope.launch(Dispatchers.IO) { archive.close() } }
        AlertDialog(onDismissRequest = { dismiss() }, title = { Text("选择一个图标源") },
            text = { LazyColumn { items(archive.sources) { source ->
                TextButton(onClick = { install(archive, source) }) { Text("${source.title}\n${source.completeCount()} 个完整图标", Modifier.fillMaxWidth()) }
            } } }, confirmButton = {}, dismissButton = { TextButton(onClick = { dismiss() }) { Text("取消") } })
    }
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text("移除当前图标库？") },
        text = { Text("已导入的图标将恢复为原图。") }, confirmButton = { TextButton(onClick = {
            removeDialog = false
            scope.launch { withContext(Dispatchers.IO) { IconLibrary.remove(context) }; refreshState(); packages = emptySet() }
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("取消") } })
}

@Composable
private fun ImportedPreview(generation: String, app: LaunchableApp, imported: Boolean) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, generation, app.packageName, imported) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = if (imported && generation.isNotBlank()) {
                    val folder = File(IconLibrary.generation(context, generation), app.packageName)
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bg = BitmapFactory.decodeFile(File(folder, "0.png").path, options)
                    val fg = BitmapFactory.decodeFile(File(folder, "1.png").path, options)
                    if (bg != null && fg != null) ImportedIcon(context.resources, bg, fg) else app.icon
                } else app.icon
                drawable?.let {
                    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    synchronized(it) {
                        val previous = Rect(it.bounds)
                        it.setBounds(0, 0, 96, 96); it.draw(Canvas(bitmap)); it.bounds = previous
                    }
                    bitmap.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }
    preview?.let { Image(it, null, Modifier.size(44.dp)) } ?: Spacer(Modifier.size(44.dp))
}
