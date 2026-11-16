package dev.local.sunwindowdemo.features.alive

import android.app.Activity
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.ToggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen renderer; editing and native picker cancellation never publish the draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("alive_wallpaper", 0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") }
    var entered by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf<AliveSettings.Snapshot?>(null) }
    var scene by rememberSaveable { mutableIntStateOf(0) }
    var ambient by rememberSaveable { mutableStateOf(false) }
    var pendingComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var hasPhoto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf("") }
    var backendError by remember { mutableStateOf(prefs.getString("backend_error", "").orEmpty()) }
    var resourcesRevision by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val stages = listOf("息屏", "锁屏", "桌面")

    LaunchedEffect(context) {
        try {
            draft = withContext(Dispatchers.IO) {
                if (entered) AliveSettings.readDraft(context) else AliveSettings.beginEdit(context)
            }
            entered = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message ?: "无法读取壁纸草稿" }
    }
    LaunchedEffect(draft, refresh, busy) {
        val input = draft ?: return@LaunchedEffect
        if (busy) return@LaunchedEffect
        try {
            val status = withContext(Dispatchers.IO) {
                (AliveAssets.ready(context) && AliveAssets.library(context).isFile) to input.photoFile(context).isFile
            }
            ready = status.first
            hasPhoto = status.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ready = false; message = failure.message ?: "资源检查失败" }
    }
    DisposableEffect(prefs, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "backend_error") backendError = prefs.getString("backend_error", "").orEmpty()
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }

    fun appliedSuccessfully() {
        try {
            val activity = context.aliveActivity()
            val ownTask = activity?.let { owner -> context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo?.taskId == owner.taskId } }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (ownTask != null) ownTask.finishAndRemoveTask() else activity?.finishAndRemoveTask()
        } catch (failure: Exception) { message = "壁纸已应用，但无法返回桌面：${failure.message.orEmpty()}" }
    }
    fun updateDraft(next: AliveSettings.Snapshot) {
        if (busy || pendingComponent != null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AliveSettings.saveDraft(context, next) }
                draft = next
                previewError = ""
                message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "草稿保存失败" }
            finally { busy = false }
        }
    }
    fun importAsset(uri: Uri, photo: Boolean) {
        if (busy || draft == null || pendingComponent != null) return
        busy = true
        message = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    if (photo) AliveSettings.importDraftPhoto(context, uri)
                    else { AliveAssets.prepare(context, uri); AliveSettings.readDraft(context) }
                }
                draft = next
                previewError = ""
                resourcesRevision++
                message = if (photo) "草稿照片已更新，尚未设置壁纸" else "资源已校验并导入，尚未设置壁纸"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "导入失败，当前壁纸未更改" }
            finally { busy = false; refresh++ }
        }
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri, false)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri, true)
    }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingComponent?.let(ComponentName::unflattenFromString)
        pendingComponent = null
        if (result.resultCode != Activity.RESULT_OK || target == null) {
            message = "已取消设置，当前配置未更改；可继续编辑草稿"
        } else {
            busy = true
            scope.launch {
                try {
                    val applied = withContext(Dispatchers.IO) {
                        if (selectedAliveComponent(context, target)) AliveSettings.applyDraft(context) else null
                    }
                    if (applied != null) { draft = applied; appliedSuccessfully() }
                    else message = "未能核实系统已选用目标组件，未提交草稿；请重试"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "确认失败，草稿未提交" }
                finally { busy = false; refresh++ }
            }
        }
    }
    val available = arm64 && ready && hasPhoto && draft != null && previewError.isBlank() && !busy && pendingComponent == null
    val editable = draft != null && !busy && pendingComponent == null
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val input = draft
            if (arm64 && ready && hasPhoto && input != null) {
                AlivePreview(input, scene, resourcesRevision, Modifier.fillMaxSize(),
                    onNextScene = { scene = (scene + 1) % stages.size }, onError = { previewError = it })
            } else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    !arm64 -> "当前进程不支持 arm64 原生渲染"
                    input == null -> "正在读取草稿"
                    !ready -> "请先导入官方渲染资源"
                    else -> "选择照片，开始真实预览"
                }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { editing = true }) { Text("打开编辑") }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        Text("Alive", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "编辑") }
                        TextButton(onClick = { confirm = true }, enabled = available) { Text("设置壁纸") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (previewError.isNotBlank() || message != null) Surface(color = if (previewError.isNotBlank()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(if (previewError.isNotBlank()) "真实预览不可用：$previewError" else message.orEmpty(), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        stages.forEachIndexed { index, title ->
                            TextButton(onClick = { scene = index }) { Text(if (scene == index) "● $title" else title) }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("点按背景切换阶段 · 编辑仅修改草稿", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (editing) ModalBottomSheet(onDismissRequest = { editing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.72f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("编辑${stages[scene]}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
                PrimaryTabRow(selectedTabIndex = scene) {
                    stages.forEachIndexed { index, title -> Tab(selected = scene == index, onClick = { scene = index }, text = { Text(title) }) }
                }
            }
            item {
                val input = draft
                val names = if (scene == 0) listOf("启航", "留光", "星月", "轻启", "山脉", "银河") else listOf("无", "长虹", "波浪", "雾花")
                val selected = when (scene) { 0 -> input?.aod ?: 0; 1 -> input?.lock ?: 0; else -> (input?.home ?: 6) - 6 }
                StyleGallery(scene, names, selected, editable, resourcesRevision) { value ->
                    input?.let {
                        updateDraft(when (scene) {
                            0 -> AliveSettings.Snapshot(value, it.lock, it.home, it.photo)
                            1 -> AliveSettings.Snapshot(it.aod, value, it.home, it.photo)
                            else -> AliveSettings.Snapshot(it.aod, it.lock, value + 6, it.photo)
                        })
                    }
                }
                Text("缩略图为已导入的官方样式示意，背景使用你的照片实时渲染；不是系统时钟或认证界面。", Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                GroupTitle("照片")
                SettingsGroup { SettingRow("共享照片", if (hasPhoto) "三个阶段共用草稿照片，最长边不超过 2048 像素" else "尚未选择照片，不使用占位图替代") }
                OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }, enabled = editable, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("选择照片") }
            }
            item {
                GroupTitle("壁纸组件")
                SettingsGroup {
                    ToggleRow("息屏实验版", "关闭使用普通版回退。开启需模块 SystemUI 与系统框架作用域生效、系统 AOD 已启用；不改变认证、强制常亮或系统保护。",
                        value = ambient, enabled = editable) { ambient = it }
                    SettingRow("短时预览", "照片防烧屏位移尚未实现，请勿长期常亮测试。真机息屏、转场效果仍待验证。")
                }
            }
            item {
                GroupTitle("本地资源")
                SettingsGroup {
                    SettingRow("OpenAlive 0.4.41-preview", if (ready) "固定版本原生资源已校验，仅本机保存" else "需自备官方 APK（约 613 MiB）；仅导入固定素材与校验后的原生库，不安装或执行 DEX。")
                    if (backendError.isNotBlank()) SettingRow("最近渲染器状态", backendError)
                }
                OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    enabled = arm64 && editable, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (ready) "更新已校验资源／缩略图" else "导入官方 APK") }
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Maga-King/OpenAlive/releases/tag/v0.4.41-preview"))) }
                    catch (failure: Exception) { message = "没有可打开上游发布说明的浏览器" }
                }, enabled = !busy) { Text("上游发布说明与许可") }
                Text("素材版权归原作者；导入时请保持页面打开。离开或取消不提交草稿，设置成功后返回桌面。", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("设置${if (ambient) "息屏实验版" else "普通版"}壁纸？") },
        text = { Text("已选用同一组件且未混用两个版本时更新其配置；否则由系统选择器决定桌面／锁屏目标，返回成功且核实组件后才提交。成功后返回桌面并关闭本模块任务，取消保留草稿。") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (!available) return@TextButton
            val target = ComponentName(context, if (ambient) AliveAmbientWallpaperService::class.java else AliveWallpaperService::class.java)
            busy = true
            scope.launch {
                try {
                    val applied = withContext(Dispatchers.IO) {
                        if (selectedAliveComponent(context, target, requireUnmixed = true)) AliveSettings.applyDraft(context) else null
                    }
                    if (applied != null) { draft = applied; appliedSuccessfully() }
                    else {
                        pendingComponent = target.flattenToString()
                        try {
                            wallpaperPicker.launch(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, target))
                        } catch (failure: Exception) { pendingComponent = null; message = failure.message ?: "无法打开系统选择器，未提交草稿" }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "应用失败，草稿仍保留" }
                finally { busy = false }
            }
        }) { Text("确认设置") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
private fun StyleGallery(scene: Int, names: List<String>, selected: Int, enabled: Boolean, revision: Int, choose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        names.indices.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { index ->
                    val thumbnail = styleThumbnail(scene, index, revision)
                    OutlinedCard(onClick = { choose(index) }, enabled = enabled, modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(if (selected == index) 2.dp else 1.dp,
                            if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        if (thumbnail != null) Image(thumbnail, names[index], Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Fit)
                        else Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                            Text("未导入缩略图\n可重新导入官方 APK 补齐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(names[index], Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            RadioButton(selected = selected == index, onClick = null, enabled = enabled)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun styleThumbnail(scene: Int, index: Int, revision: Int): ImageBitmap? {
    val context = LocalContext.current
    val path = thumbnailPath(scene, index)
    return produceState<ImageBitmap?>(null, context, path, revision) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                AliveAssets.open(context, path).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                AliveAssets.open(context, path).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }.value
}

private fun thumbnailPath(scene: Int, index: Int): String {
    val name = if (scene == 0) "aod_" + listOf("set_sail", "leave_light", "moon_and_star", "light_start", "mountain", "galaxy")[index]
        else "lockscreen_" + listOf("source", "straight_line", "curve", "frosted_glass")[index]
    return "editor/alive_photo_wallpaper_$name.png"
}

/** Check both destinations; only the native picker chooses target flags or switches components. */
private fun selectedAliveComponent(context: Context, target: ComponentName, requireUnmixed: Boolean = false): Boolean {
    val manager = WallpaperManager.getInstance(context)
    return try {
        val components = listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK).map { manager.getWallpaperInfo(it)?.component }
        val sibling = ComponentName(context, if (target.className == AliveWallpaperService::class.java.name)
            AliveAmbientWallpaperService::class.java else AliveWallpaperService::class.java)
        components.any { it == target } && (!requireUnmixed || components.none { it == sibling })
    } catch (_: SecurityException) { false }
}

private fun Context.aliveActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.aliveActivity()
    else -> null
}
