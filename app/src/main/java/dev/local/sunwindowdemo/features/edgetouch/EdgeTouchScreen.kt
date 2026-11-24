package dev.local.sunwindowdemo.features.edgetouch

import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EdgeTouchScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var config by remember { mutableStateOf(EdgeTouchStore.read(context)) }
    var nativeBusy by remember { mutableStateOf(false) }
    var nativeMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext
    fun nativeAction(success: String, operation: () -> Unit) {
        if (nativeBusy) return
        nativeBusy = true
        scope.launch {
            try {
                nativeMessage = withContext(Dispatchers.IO) {
                    runCatching(operation).fold({ success }, { failure ->
                        failure.message?.takeIf { failure is IllegalStateException } ?: "操作未完成，请检查模块 App 的 root 授权"
                    })
                }
            } finally {
                nativeBusy = false
            }
        }
    }
    DisposableEffect(context, lifecycle) {
        val prefs = EdgeTouchStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = EdgeTouchStore.read(context) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) config = EdgeTouchStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    val nativeManaged = config.getBoolean("native_managed")
    val nativeAvailable = config.getBoolean("native_available")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        GroupTitle("原厂防误触")
        SettingsGroup {
            SettingRow("检验原厂防误触", subtitle = when {
                nativeBusy -> "正在处理…"
                nativeMessage != null -> nativeMessage
                nativeAvailable -> "已检验通过"
                else -> "未检验"
            }, onClick = if (nativeBusy) null else ({ nativeAction("检验通过") { NativeEdgeTouch.check(appContext) } }))
            if (nativeAvailable || nativeManaged) {
                ToggleRow("启用原厂防误触", value = nativeManaged, enabled = !nativeBusy) { enabled ->
                    nativeAction(if (enabled) "已下发原厂规则" else "已清除原厂规则") {
                        NativeEdgeTouch.set(appContext, enabled)
                    }
                }
            }
        }
        GroupTitle("固定禁触区")
        SettingsGroup {
            ToggleRow("开启边缘防误触", subtitle = if (nativeManaged) "已暂停" else null,
                value = config.getBoolean("enabled"), enabled = !nativeManaged) {
                EdgeTouchStore.setBoolean(context, "enabled", it)
            }
        }
        if (nativeManaged) return@Column
        GroupTitle("范围预览")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            RegionPreview(config)
        }
        GroupTitle("左侧")
        SettingsGroup {
            ToggleRow("左侧禁触区", value = config.getBoolean("left_enabled")) {
                EdgeTouchStore.setBoolean(context, "left_enabled", it)
            }
            SliderRow("左侧宽度", value = config.getInt("left_width_dp"), range = 1..48, unit = " dp") {
                EdgeTouchStore.setNumber(context, "left_width_dp", it)
            }
        }
        GroupTitle("右侧")
        SettingsGroup {
            ToggleRow("右侧禁触区", value = config.getBoolean("right_enabled")) {
                EdgeTouchStore.setBoolean(context, "right_enabled", it)
            }
            SliderRow("右侧宽度", value = config.getInt("right_width_dp"), range = 1..48, unit = " dp") {
                EdgeTouchStore.setNumber(context, "right_width_dp", it)
            }
        }
        GroupTitle("纵向范围")
        SettingsGroup {
            SliderRow("覆盖高度", value = config.getInt("height_percent"), range = 20..100, unit = "%") {
                EdgeTouchStore.setNumber(context, "height_percent", it)
            }
            SliderRow("纵向位置", value = config.getInt("position_percent"), range = 0..100, unit = "%") {
                EdgeTouchStore.setNumber(context, "position_percent", it)
            }
            SliderRow("握持侧角落范围",value=config.getInt("corner_dp"),range=0..64,unit=" dp") {
                EdgeTouchStore.setNumber(context,"corner_dp",it)
            }
        }
    }
}

@Composable
private fun RegionPreview(config: Bundle) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val rotation = LocalView.current.display?.rotation ?: 0
    val metrics = remember(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics
    }
    val bounds = metrics.bounds
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val rectangles = EdgeTouchGeometry.buildSuppression(bounds.width(), bounds.height(), insets.top, insets.bottom,
        context.resources.displayMetrics.density,config.getBoolean("left_enabled"),config.getBoolean("right_enabled"),config.getInt("left_width_dp"),config.getInt("right_width_dp"),
        config.getInt("height_percent"),config.getInt("position_percent"),intArrayOf(),rotation,config.getInt("corner_dp"))
    val foreground = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outline
    PhonePreview(bounds.width(), bounds.height()) { scale, origin ->
        for (i in rectangles.indices step 4) {
            val enabled = config.getBoolean("enabled")
            drawRect(if (enabled) foreground.copy(alpha = .7f) else neutral.copy(alpha = .35f),
                origin + Offset(rectangles[i] * scale, rectangles[i + 1] * scale),
                Size((rectangles[i + 2] - rectangles[i]) * scale, (rectangles[i + 3] - rectangles[i + 1]) * scale))
        }
    }
}
