package dev.local.sunwindowdemo.features.alive

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
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

/** Edits are private drafts until an explicit application is confirmed. */
@Composable
fun AliveScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("alive_wallpaper", 0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    val previewAspect = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1)
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") }
    var entered by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf<AliveSettings.Snapshot?>(null) }
    var scene by rememberSaveable { mutableIntStateOf(0) }
    var ambient by rememberSaveable { mutableStateOf(false) }
    var pendingComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var hasPhoto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf("") }
    var backendError by remember { mutableStateOf(prefs.getString("backend_error", "").orEmpty()) }
    var resourcesRevision by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

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
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }

    fun updateDraft(next: AliveSettings.Snapshot) {
        if (busy || pendingComponent != null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AliveSettings.saveDraft(context, next) }
                draft = next
                previewError = ""
                message = "草稿已更新，当前壁纸未更改"
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
                message = if (photo) "照片已加入草稿，当前壁纸未更改" else "资源已校验并导入，未应用壁纸"
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
            message = "未确认应用，当前配置未更改；草稿仍可继续编辑"
        } else {
            busy = true
            scope.launch {
                try {
                    val applied = withContext(Dispatchers.IO) {
                        if (selectedAliveComponent(context, target)) AliveSettings.applyDraft(context) else null
                    }
                    if (applied != null) { draft = applied; message = "系统已选择此组件，草稿已应用" }
                    else message = "未能核实系统已选用目标组件，未提交草稿；请重试系统选择器"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "确认失败，草稿未提交" }
                finally { busy = false; refresh++ }
            }
        }
    }
    val available = arm64 && ready && hasPhoto && draft != null && previewError.isBlank() && !busy && pendingComponent == null
    val editable = draft != null && !busy && pendingComponent == null
    val stages = listOf("息屏", "锁屏", "桌面")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset)) {
        item {
            Text("预览与编辑", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
            PrimaryTabRow(selectedTabIndex = scene) {
                stages.forEachIndexed { index, title ->
                    Tab(selected = scene == index, onClick = { scene = index }, text = { Text(title) })
                }
            }
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(28.dp)) {
                Box(Modifier.fillMaxWidth().height(380.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                    val input = draft
                    if (arm64 && ready && hasPhoto && input != null) {
                        AlivePreview(input, scene, resourcesRevision,
                            Modifier.fillMaxHeight().aspectRatio(previewAspect).clip(RoundedCornerShape(20.dp)),
                            onNextScene = { scene = (scene + 1) % stages.size },
                            onError = { previewError = it })
                        if (previewError.isNotBlank()) Surface(
                            Modifier.align(Alignment.BottomCenter).padding(8.dp),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
                        ) { Text("真实预览不可用：$previewError", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    } else Text(when {
                        !arm64 -> "当前进程不支持 arm64 原生渲染"
                        input == null -> "正在读取草稿"
                        !ready -> "导入已校验的官方资源后开启真实预览"
                        else -> "选择一张照片后开启真实预览"
                    }, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Text("${stages[scene]} · 点按画面播放下一阶段", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall)
                Text("同一原生渲染器播放息屏 → 锁屏 → 桌面 → 息屏；仅预览壁纸，不模拟系统时钟或解锁认证。",
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            message?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            GroupTitle("照片")
            SettingsGroup { SettingRow("共享照片", if (hasPhoto) "三个阶段使用这张草稿照片，最长边不超过 2048 像素" else "未选择照片；不会用占位图或渐变替代") }
            OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }, enabled = editable,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("选择草稿照片") }
        }
        item {
            val input = draft
            GroupTitle("息屏样式")
            StyleChoices(listOf("启航", "留光", "星月", "轻启", "山脉", "银河"), input?.aod ?: 0, editable) { value ->
                input?.let { updateDraft(AliveSettings.Snapshot(value, it.lock, it.home, it.photo)) }
            }
            GroupTitle("锁屏纹理")
            StyleChoices(listOf("无", "长虹", "波浪", "雾花"), input?.lock ?: 0, editable) { value ->
                input?.let { updateDraft(AliveSettings.Snapshot(it.aod, value, it.home, it.photo)) }
            }
            GroupTitle("桌面纹理")
            StyleChoices(listOf("无", "长虹", "波浪", "雾花"), (input?.home ?: 6) - 6, editable) { value ->
                input?.let { updateDraft(AliveSettings.Snapshot(it.aod, it.lock, value + 6, it.photo)) }
            }
            Text("样式与照片仅修改草稿。离开或取消不会改动已应用的配置。", Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GroupTitle("应用")
            SettingsGroup {
                ToggleRow("息屏实验版", "关闭使用普通版回退；开启仅选择本模块的 AOD 实验组件，需要系统框架与 SystemUI 作用域生效及原生 AOD 设置。尚未完成本轮真机验证。",
                    value = ambient, enabled = editable) { ambient = it }
                SettingRow("保留系统保护", "不修改认证或强制常亮。照片防烧屏位移尚未实现，请勿长期常亮测试。系统选择器决定应用到桌面或锁屏。")
            }
            Button(onClick = { confirm = true }, enabled = available, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("应用草稿 · ${if (ambient) "息屏实验版" else "普通版"}")
            }
        }
        item {
            GroupTitle("本地渲染资源")
            SettingsGroup {
                SettingRow("OpenAlive 0.4.41-preview", if (ready) "固定版本资源已校验，仅本机保存" else "需自备官方 APK（约 613 MiB）。仅导入固定效果资源和已校验原生库，不安装或执行其 DEX。")
                if (backendError.isNotBlank()) SettingRow("最近渲染器状态", backendError)
            }
            OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                enabled = arm64 && editable, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (ready) "重新校验资源" else "选择官方 APK 并导入") }
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Maga-King/OpenAlive/releases/tag/v0.4.41-preview"))) }
                catch (failure: Exception) { message = "没有可打开上游发布说明的浏览器" }
            }, enabled = !busy) { Text("上游发布说明与许可信息") }
            Text("原生库与素材由你本地导入，版权归原作者，请自行确认使用许可。导入约 613 MiB APK 时请保持页面打开。",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("确认应用草稿？") },
        text = { Text("已选用同一组件时会更新其壁纸配置；否则先打开系统选择器，仅在返回成功且核实选中目标组件后提交。取消不会提交草稿。") },
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
                    if (applied != null) { draft = applied; message = "草稿已应用到当前组件，未切换壁纸组件" }
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
        }) { Text("确认并继续") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

@Composable
private fun StyleChoices(names: List<String>, selected: Int, enabled: Boolean, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { index, title ->
            FilterChip(selected = selected == index, onClick = { choose(index) }, enabled = enabled, label = { Text(title) })
        }
    }
}

/** Check both destinations; the native picker, not this editor, chooses the target flags. */
private fun selectedAliveComponent(context: Context, target: ComponentName, requireUnmixed: Boolean = false): Boolean {
    val manager = WallpaperManager.getInstance(context)
    return try {
        val components = listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK).map { manager.getWallpaperInfo(it)?.component }
        val sibling = ComponentName(context, if (target.className == AliveWallpaperService::class.java.name)
            AliveAmbientWallpaperService::class.java else AliveWallpaperService::class.java)
        components.any { it == target } && (!requireUnmixed || components.none { it == sibling })
    } catch (_: SecurityException) { false }
}
