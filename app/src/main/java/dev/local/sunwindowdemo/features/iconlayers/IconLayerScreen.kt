package dev.local.sunwindowdemo.features.iconlayers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun IconLayerScreen(apps: List<LaunchableApp>) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(IconLayerStore.read(context)) }
    val installed = apps
    var library by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var fraction by remember { mutableFloatStateOf(-1f) }
    var pending by remember { mutableStateOf<IconArchive?>(null) }
    var removeDialog by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<LaunchableApp?>(null) }
    var diagnostics by remember { mutableStateOf(false) }
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
    val matched = remember(installed, query) {
        installed.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("图标补全", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 28.dp), style = MaterialTheme.typography.headlineLarge)
                SettingsGroup {
                    ToggleRow("启用图标补全", "图标包优先，未匹配时保留应用原图", state.getBoolean("enabled")) {
                        IconLayerStore.enabled(context, it); refreshState()
                    }
                    ToggleRow("通知图标替换", "默认不处理；开启后仍按逐应用的通知设置生效", state.getBoolean("notifications")) {
                        IconLayerStore.notifications(context, it); refreshState()
                    }
                    SettingRow(if (state.getInt("count") > 0) state.getString("title") ?: "当前图标库" else "尚未导入图标库",
                        if (state.getInt("count") > 0) "${state.getInt("count")} 个图标 · 已安装匹配 ${installed.count { it.packageName in library }} 个" else "未导入素材时保留原图，算法需要逐应用开启")
                    SettingRow("导入本地 ZIP", "包名/0.png 为背景，1.png 为透明前景", onClick = if (busy || pending != null) null else { { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) } })
                    SettingRow("导入本地文件夹", "素材由用户自行提供，模块不内置第三方素材", onClick = if (busy || pending != null) null else { { folderPicker.launch(null) } })
                    SettingRow("刷新图标", "重新生成桌面图标并刷新系统宿主，保留桌面排列", onClick = { IconLayerStore.refreshAll(context); message = "已请求刷新，宿主正在重新加载" })
                    if (state.getInt("count") > 0) SettingRow("移除图标库", "保留逐应用设置、上传图片和算法补全", onClick = if (busy) null else { { removeDialog = true } })
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
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索已安装应用") })
                Hint("仅列出有桌面入口的应用。算法仅对手动选中的应用，在其启动后生成并缓存；彩色、莫奈、通知可分别选择不处理。")
            }
            items(matched, key = { it.packageName }) { app ->
                val policy = remember(app.packageName, state.getString("policies"), state.getStringArrayList("originals").toString()) { IconPolicy.read(context, app.packageName) }
                SettingsGroup {
                    SettingRow(app.label, when (policy.color) {
                        IconPolicy.MANUAL -> "手动前后景"
                        IconPolicy.ALGORITHM -> "算法补全"
                        IconPolicy.ORIGINAL -> "彩色图标不处理"
                        else -> if (app.packageName in library) "自动 · 素材库" else "自动 · 应用原图"
                    }, onClick = { editor = app })
                }
                Spacer(Modifier.height(2.dp))
            }
            item {
                if (matched.isEmpty()) Hint("未找到应用")
                GroupTitle("连接与诊断")
                SettingsGroup {
                    SettingRow("桌面连接状态", "${state.getString("status")} · 已加载 ${state.getInt("loaded")} 个")
                    SettingRow("实验与诊断", "Demo 分层对照", onClick = { diagnostics = !diagnostics })
                }
                if (!state.getString("error").isNullOrBlank()) Hint(state.getString("error")!!)
                if (diagnostics) SettingsGroup {
                    listOf("保留 Demo 原图", "粉色双层 Demo", "薄荷色双层 Demo").forEachIndexed { index, title ->
                        SettingRow(title, onClick = { IconLayerStore.save(context, index); refreshState() }, trailing = { RadioButton(state.getInt("mode") == index, null) })
                    }
                }
                Hint("沿用现有三个系统作用域。莫奈颜色由桌面主题决定；系统启动图不包含应用自行绘制的品牌页面。")
            }
        }
    }
    editor?.let { app -> IconEditor(app, state.getString("generation", ""), app.packageName in library) { editor = null; refreshState() } }
    pending?.let { archive ->
        fun dismiss() { pending = null; scope.launch(Dispatchers.IO) { archive.close() } }
        AlertDialog(onDismissRequest = { dismiss() }, title = { Text("选择图标源") },
            text = { LazyColumn { items(archive.sources) { source -> TextButton(onClick = { install(archive, source) }) { Text("${source.title} · ${source.completeCount()} 个", Modifier.fillMaxWidth()) } } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { dismiss() }) { Text("取消") } })
    }
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text("移除当前图标库？") },
        text = { Text("保留手动图片和逐应用设置，自动模式将恢复应用原图。") }, confirmButton = { TextButton(onClick = {
            removeDialog = false; scope.launch { withContext(Dispatchers.IO) { IconLibrary.remove(context) }; refreshState() }
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("取消") } })
}

@Composable
private fun IconEditor(app: LaunchableApp, generation: String, matched: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policy = remember(app.packageName) { IconPolicy.read(context, app.packageName) }
    var revision by remember { mutableIntStateOf(0) }
    var field by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun save() { IconPolicy.save(context, app.packageName, policy); revision++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                val id = withContext(Dispatchers.IO) { IconOverrides.importImage(context, uri) }
                when (field) {
                    0 -> { policy.background = id; policy.color = IconPolicy.MANUAL }
                    1 -> { policy.foreground = id; policy.color = IconPolicy.MANUAL }
                    2 -> { policy.monoImage = id; policy.monochrome = IconPolicy.UPLOAD }
                    3 -> { policy.notificationImage = id; policy.notification = IconPolicy.UPLOAD }
                }
                error = ""; save()
            } catch (e: Exception) { error = e.message ?: "导入图片失败" } finally { loading = false }
        }
    }
    fun upload(which: Int) { field = which; picker.launch(arrayOf("image/*")) }
    val entry by produceState<IconAssets.Entry?>(null, app.packageName, generation, revision) {
        value = withContext(Dispatchers.IO) { runCatching { IconAssets.resolve(context, app.packageName, policy, if (matched) generation else "", 192, true) }.getOrNull() }
    }
    val primary = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val previews by produceState<List<ImageBitmap?>>(listOf(null,null,null), entry, primary, revision) {
        value = withContext(Dispatchers.IO) {
            fun draw(drawable: Drawable?): ImageBitmap? = drawable?.let { IconImages.render(it, 192).asImageBitmap() }
            val current = entry
            listOf(
                draw(if (current != null && current.colorChanged() && !current.nativeColor()) ImportedIcon(context.resources, current.background(), current.foreground(), current.mono()) else context.packageManager.getApplicationIcon(app.packageName)),
                draw(current?.mono()?.let { BitmapDrawable(context.resources, it).apply { setColorFilter(primary, PorterDuff.Mode.SRC_IN) } }),
                draw(current?.notification()?.let { BitmapDrawable(context.resources, it).apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) } })
            )
        }
    }
    AlertDialog(onDismissRequest = { if (!loading) close() }, title = { Text(app.label) }, text = {
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("彩色", "莫奈", "通知").forEachIndexed { index, title ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(color = if (index == 2) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                                Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                                    previews[index]?.let { Image(it, null, Modifier.size(if (index == 2) 28.dp else 54.dp)) } ?: Text("—")
                                }
                            }
                            Text(title, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("莫奈颜色为当前主题示意；通知留空表示保留原图。选择后自动保存。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                Text("彩色图标", style = MaterialTheme.typography.titleSmall)
                ModeRow(listOf("自动", "手动前后景", "算法补全", "不处理"), policy.color, !loading) { policy.color = it; save() }
                if (policy.color == IconPolicy.ALGORITHM) Text("下次启动该应用后生成并缓存；生成前保留原图。", style = MaterialTheme.typography.bodySmall)
                if (policy.color == IconPolicy.MANUAL) {
                    TextButton(onClick = { upload(0) }, enabled = !loading) { Text(if (policy.background.isEmpty()) "上传背景图片" else "更换背景图片") }
                    TextButton(onClick = { upload(1) }, enabled = !loading) { Text(if (policy.foreground.isEmpty()) "上传透明前景" else "更换前景图片") }
                    if (policy.background.isEmpty() || policy.foreground.isEmpty()) Text("前后景选齐后生效，当前暂用原图", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("莫奈图标", style = MaterialTheme.typography.titleSmall)
                ModeRow(listOf("跟随前景", "上传单色图片", "不处理"), policy.monochrome, !loading) { policy.monochrome = it; save() }
                if (policy.monochrome == IconPolicy.UPLOAD) TextButton(onClick = { upload(2) }, enabled = !loading) { Text(if (policy.monoImage.isEmpty()) "选择图片" else "更换图片") }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("状态栏通知小图标", style = MaterialTheme.typography.titleSmall)
                ModeRow(listOf("跟随前景", "上传图片", "不处理"), policy.notification, !loading) { policy.notification = it; save() }
                if (policy.notification == IconPolicy.UPLOAD) TextButton(onClick = { upload(3) }, enabled = !loading) { Text(if (policy.notificationImage.isEmpty()) "选择图片" else "更换图片") }
                Text("透明图会转换为单色轮廓，适用于状态栏、锁屏/AOD 的应用身份小图标；会话头像、系统状态图标及展开通知卡片头部保持原样。", style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = { TextButton(onClick = close, enabled = !loading) { Text("完成") } })
}

@Composable
private fun ModeRow(labels: List<String>, selected: Int, enabled: Boolean, choose: (Int) -> Unit) {
    Column {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == index, onClick = { choose(index) }, enabled = enabled)
                TextButton(onClick = { choose(index) }, enabled = enabled) { Text(label) }
            }
        }
    }
}
