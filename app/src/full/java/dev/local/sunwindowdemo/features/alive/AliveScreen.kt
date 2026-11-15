package dev.local.sunwindowdemo.features.alive

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Content for the shared SettingsPage; imports never apply wallpaper automatically. */
@Composable
fun AliveScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("alive_wallpaper", 0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val arm64 = remember { Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") }
    var style by remember { mutableIntStateOf(prefs.getInt("style", 1).coerceIn(1, 5)) }
    var ready by remember { mutableStateOf(false) }
    var hasPhoto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var backendError by remember { mutableStateOf(prefs.getString("backend_error", "").orEmpty()) }
    var revision by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh, busy) {
        if (busy) return@LaunchedEffect
        val request = revision
        ready = false
        try {
            val result = withContext(Dispatchers.IO) {
                val available = AliveAssets.ready(context)
                (available && AliveAssets.library(context).isFile) to AliveAssets.photo(context).isFile
            }
            if (request == revision) { ready = result.first; hasPhoto = result.second }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (request == revision) { ready = false; message = failure.message ?: "资源检查失败" }
        }
    }
    DisposableEffect(prefs, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            style = prefs.getInt("style", 1).coerceIn(1, 5)
            backendError = prefs.getString("backend_error", "").orEmpty()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { revision++; refresh++ }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    fun importAsset(uri: Uri, photo: Boolean) {
        if (busy) return
        busy = true; revision++; message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (photo) {
                        AliveAssets.importPhoto(context, uri)
                    } else AliveAssets.prepare(context, uri)
                }
                message = if (photo) "照片已导入" else "资源校验与导入完成"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "导入失败，现有资源未被替换"
            } finally { busy = false; revision++; refresh++ }
        }
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri, false)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAsset(uri, true)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottomInset)) {
        item {
            GroupTitle("本地资源")
            SettingsGroup {
                SettingRow("Alive 动态壁纸", "使用自己的照片与真实原生渲染器；不是原版 Flyme 系统壁纸。仅支持 arm64。")
                SettingRow("OpenAlive 0.4.41-preview", if (ready) "官方资源已校验" else "请自行准备官方 APK（643,140,270 字节，约 613 MiB）。仅导入固定效果资源与已校验原生渲染库，不安装或加载其 DEX。")
            }
            OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = arm64 && !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(if (ready) "重新校验资源" else "选择官方 APK 并导入")
            }
            TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Maga-King/OpenAlive/releases/tag/v0.4.41-preview"))) }
                catch (failure: Exception) { message = "没有可打开上游发布说明的浏览器" }
            }, enabled = !busy) { Text("上游发布说明与许可信息") }
            if (!arm64) Text("当前进程不支持 arm64，无法使用原生渲染器。", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text("正在读取、校验或导入。APK 约 613 MiB，可能较慢，请保持页面打开；不会自动应用壁纸。", style = MaterialTheme.typography.bodyMedium)
            }
            message?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
            GroupTitle("照片与样式")
            SettingsGroup { SettingRow("壁纸照片", if (hasPhoto) "已保存本地照片，最长边不超过 2048 像素" else "尚未选择照片；不会使用占位图或渐变替代") }
            OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("选择照片") }
            SettingsGroup {
                listOf("留光", "星月", "轻启", "山脉", "银河").forEachIndexed { index, name ->
                    SettingRow(name, onClick = if (busy) null else ({ prefs.edit().putInt("style", index + 1).apply() }), trailing = {
                        RadioButton(selected = style == index + 1, onClick = null, enabled = !busy)
                    })
                }
            }
            GroupTitle("系统预览")
            SettingsGroup {
                SettingRow("使用限制", "AOD 暂不支持：系统签名权限与原生黑屏行为不修改。锁屏呈现与过渡以系统实际支持为准。")
                if (backendError.isNotBlank()) SettingRow("渲染器状态", backendError)
            }
            Button(onClick = {
                try {
                    context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, AliveWallpaperService::class.java)))
                } catch (failure: Exception) { message = failure.message ?: "无法打开系统壁纸预览" }
            }, enabled = arm64 && ready && hasPhoto && !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("打开系统预览并选择应用") }
            Text("仅在系统预览中由你确认应用本模块的壁纸服务。选择资源或样式不会替你确认；资源与照片只保存在本机。上游素材版权归原作者，请自行确认使用许可。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
