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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.MoreVert
import dev.local.sunwindowdemo.core.ui.SettingsPage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One settings page: phase cards edit a private draft; only explicit apply publishes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { AliveSettings.runtimePreferences(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") }
    var entered by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf<AliveSettings.Snapshot?>(null) }
    var scene by rememberSaveable { mutableIntStateOf(0) }
    var systemAod by remember { mutableIntStateOf(AliveSystemState.aod(context)) }
    val ambient = systemAod == 1
    var menu by remember { mutableStateOf(false) }
    var resources by rememberSaveable { mutableStateOf(false) }
    var pendingComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var cropping by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var hasPhoto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val previewErrors = remember { mutableStateMapOf<Int, String>() }
    val previewError = previewErrors.values.firstOrNull { it.isNotBlank() }.orEmpty()
    var backendError by remember { mutableStateOf(prefs.getString("backend_error", "").orEmpty()) }
    var resourcesRevision by remember { mutableLongStateOf(prefs.getLong("resources_revision", 0L)) }
    var refresh by remember { mutableIntStateOf(0) }
    val stages = listOf("息屏", "锁屏", "桌面")
    val configuration = LocalConfiguration.current
    val screenAspect = remember(context, configuration) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        bounds.width().coerceAtLeast(1).toFloat() / bounds.height().coerceAtLeast(1)
    }

    LaunchedEffect(context) {
        try {
            draft = withContext(Dispatchers.IO) {
                if (entered) AliveSettings.readDraft(context) else AliveSettings.beginEdit(context)
            }
            entered = true
            if (arm64) withContext(Dispatchers.IO) { AliveSettings.prepareRuntime(context) }
            refresh++
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
            if (key == "resources_revision") { resourcesRevision = prefs.getLong("resources_revision", 0L); refresh++ }
        }
        fun refreshSystem() { systemAod = AliveSystemState.aod(context); refresh++ }
        val settingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshSystem() }
        }
        val resolver = context.contentResolver
        val registered = try {
            resolver.registerContentObserver(Settings.Secure.getUriFor(AliveSystemState.AOD_SETTING), false, settingObserver)
            true
        } catch (_: RuntimeException) { false }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshSystem() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            if (registered) resolver.unregisterContentObserver(settingObserver)
            prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer)
        }
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
                previewErrors.clear()
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
                previewErrors.clear()
                resourcesRevision = prefs.getLong("resources_revision", 0L)
                message = if (photo) "草稿照片已更新，尚未设置壁纸" else "资源已校验并导入，尚未设置壁纸"
                if (photo) cropping = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "导入失败，当前壁纸未更改" }
            finally { busy = false; refresh++ }
        }
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri, false)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importAsset(uri, true)
    }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingComponent?.let(ComponentName::unflattenFromString)
        pendingComponent = null
        if (target == null) {
            message = "已取消设置，当前配置未更改；可继续编辑草稿"
        } else {
            busy = true
            scope.launch {
                try {
                    val applied = withContext(Dispatchers.IO) {
                        if (selectedAliveComponent(context, target)) AliveSettings.applyDraft(context) else null
                    }
                    if (applied != null) { draft = applied; appliedSuccessfully() }
                    else message = if (result.resultCode == Activity.RESULT_OK)
                        "未确认系统已选用 Alive 壁纸，未提交草稿；请重试"
                    else "已取消设置，当前配置未更改；可继续编辑草稿"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "确认失败，草稿未提交" }
                finally { busy = false; refresh++ }
            }
        }
    }
    val available = arm64 && ready && hasPhoto && draft != null && previewError.isBlank() && !busy && pendingComponent == null
    val editable = draft != null && !busy && pendingComponent == null
    LaunchedEffect(systemAod) {
        if (systemAod != 1 && scene == 0) { editing = false; scene = 1 }
    }
    fun applyWallpaper() {
        if (!available) return
        // Keep the AOD-capable component when the system switch is off; turning it back on must not require re-applying.
        val target = ComponentName(context, if (systemAod >= 0 || selectedAliveComponent(context, ComponentName(context, AliveAmbientWallpaperService::class.java)))
            AliveAmbientWallpaperService::class.java else AliveWallpaperService::class.java)
        busy = true
        scope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    if (selectedAliveComponent(context, target, requireUnmixed = true)) AliveSettings.applyDraft(context) else null
                }
                if (applied != null) { draft = applied; appliedSuccessfully(); return@launch }
                pendingComponent = target.flattenToString()
                val change = AliveWallpaperPicker.change(target)
                if (change.resolveActivity(context.packageManager) != null) {
                    try { wallpaperPicker.launch(change) }
                    catch (failure: Exception) { pendingComponent = null; message = failure.message ?: "无法打开系统选择器，未提交草稿" }
                    return@launch
                }
                // LineageOS' WallpaperPicker2 has no exported CHANGE_LIVE_WALLPAPER activity.
                // The hooked system service binds only this exact component; without the hook or
                // hidden-API access, open the native customization picker instead.
                if (withContext(Dispatchers.IO) { AliveWallpaperPicker.applyDirect(context, target) }) {
                    val after = withContext(Dispatchers.IO) {
                        if (selectedAliveComponent(context, target, requireUnmixed = true)) AliveSettings.applyDraft(context) else null
                    }
                    if (after != null) { draft = after; pendingComponent = null; appliedSuccessfully(); return@launch }
                }
                val chooser = AliveWallpaperPicker.chooser()
                if (chooser.resolveActivity(context.packageManager) != null) {
                    message = "当前系统不支持直接预览，请在壁纸选择器中选择 Alive 动态壁纸"
                    try { wallpaperPicker.launch(chooser) }
                    catch (failure: Exception) { pendingComponent = null; message = failure.message ?: "无法打开系统壁纸选择器，未提交草稿" }
                } else {
                    pendingComponent = null
                    message = "系统没有可用的动态壁纸选择器，未提交草稿"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "应用失败，草稿仍保留" }
            finally { busy = false }
        }
    }
    SettingsPage(title = "Alive 壁纸", onBack = onBack, expandedTitle = false, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("渲染资源") }, onClick = { menu = false; resources = true })
            }
        }
    }) { bottomInset ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            val input = draft
            val visible = if (ambient) listOf(0, 1, 2) else listOf(1, 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                visible.forEach { phase -> key(phase) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(screenAspect).clip(RoundedCornerShape(22.dp))
                            .background(Color.Black).semantics { contentDescription = "${stages[phase]}样式" }
                            .clickable(enabled = editable, role = Role.Button) { scene = phase; editing = true },
                            contentAlignment = Alignment.Center) {
                            if (input != null) {
                                DisposableEffect(phase) { onDispose { previewErrors.remove(phase) } }
                                AlivePreview(input, phase, resourcesRevision, Modifier.fillMaxSize(),
                                    renderEnabled = arm64 && ready && hasPhoto, onError = { previewErrors[phase] = it })
                            } else Text(stages[phase], color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        Text(stages[phase], style = MaterialTheme.typography.labelLarge)
                    }
                } }
            }
            SettingsGroup {
                SettingRow(if (hasPhoto) "更换照片" else "选择照片", onClick = if (editable) ({ photoPicker.launch("image/*") }) else null)
                if (hasPhoto) SettingRow("调整取景", onClick = if (editable) ({ cropping = true }) else null)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val status = when {
                previewError.isNotBlank() -> "壁纸渲染不可用：$previewError"
                message != null -> message.orEmpty()
                !arm64 -> "当前进程不支持 arm64 原生渲染"
                input == null -> "正在读取草稿"
                !ready -> "请从右上角导入官方渲染资源"
                !hasPhoto -> "请选择照片"
                systemAod < 0 -> "无法读取系统息屏显示设置；暂不显示息屏卡片，设置时沿用当前 Alive 组件"
                else -> ""
            }
            if (status.isNotBlank()) Text(status, color = if (previewError.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { applyWallpaper() }, enabled = available, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("设置壁纸") }
        }
    }
    if (cropping) draft?.let { input ->
        AliveCropScreen(input, screenAspect, cancel = { cropping = false }) { crop ->
            cropping = false
            if (crop != input.crop) updateDraft(input.withCrop(crop))
        }
    }
    if (editing) ModalBottomSheet(onDismissRequest = { editing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val input = draft
            val names = if (scene == 0) listOf("启航", "留光", "星月", "轻启", "山脉", "银河") else listOf("无", "长虹", "波浪", "雾花")
            val selected = when (scene) { 0 -> input?.aod ?: 0; 1 -> input?.lock ?: 0; else -> (input?.home ?: 6) - 6 }
            StyleGallery(scene, names, selected, editable, resourcesRevision) { value ->
                input?.let {
                    updateDraft(when (scene) {
                        0 -> AliveSettings.Snapshot(value, it.lock, it.home, it.photo, it.crop)
                        1 -> AliveSettings.Snapshot(it.aod, value, it.home, it.photo, it.crop)
                        else -> AliveSettings.Snapshot(it.aod, it.lock, value + 6, it.photo, it.crop)
                    })
                }
            }
        }
    }
    if (resources) ModalBottomSheet(onDismissRequest = { resources = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("渲染资源", style = MaterialTheme.typography.headlineSmall)
            Text(if (ready) "OpenAlive 0.4.41-preview 固定版本已校验" else "需导入官方 OpenAlive 0.4.41-preview APK；只读取校验后的素材与原生库，不安装 APK 或执行 DEX。")
            OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                enabled = arm64 && editable, modifier = Modifier.fillMaxWidth()) { Text(if (ready) "更新资源／缩略图" else "导入官方 APK") }
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Maga-King/OpenAlive/releases/tag/v0.4.41-preview"))) }
                catch (_: Exception) { message = "没有可打开上游发布说明的浏览器" }
            }) { Text("上游发布说明与许可") }
            if (backendError.isNotBlank()) Text(backendError, style = MaterialTheme.typography.bodySmall)
            Text("素材版权归原作者。照片防烧屏位移尚未实现，请勿长期常亮测试。首次切换壁纸组件仍需系统确认；退出或取消不会应用草稿。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StyleGallery(scene: Int, names: List<String>, selected: Int, enabled: Boolean, revision: Long, choose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        names.indices.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { index ->
                    val thumbnail = styleThumbnail(scene, index, revision)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { choose(index) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).semantics { this.selected = selected == index; contentDescription = names[index] },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(2.dp, if (selected == index) MaterialTheme.colorScheme.primary else Color.Transparent),
                            color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                if (thumbnail != null) Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else Text(names[index], style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(names[index], style = MaterialTheme.typography.labelMedium,
                            color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun styleThumbnail(scene: Int, index: Int, revision: Long): ImageBitmap? {
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
