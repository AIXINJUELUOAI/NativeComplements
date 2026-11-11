package dev.local.sunwindowdemo.features.edgetouch

import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun EdgeTouchScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var config by remember { mutableStateOf(EdgeTouchStore.read(context)) }
    DisposableEffect(context, lifecycle) {
        val prefs = EdgeTouchStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = EdgeTouchStore.read(context) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) config = EdgeTouchStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        SettingsGroup {
            ToggleRow("开启边缘防误触", "忽略指定屏幕边缘的触点，减少单手握持时的误触", config.getBoolean("enabled")) {
                EdgeTouchStore.setBoolean(context, "enabled", it)
            }
            ToggleRow("显示实际禁触区", "开启防误触后，在屏幕边缘按真实尺寸显示范围，便于调整大小", config.getBoolean("show_regions")) {
                EdgeTouchStore.setBoolean(context, "show_regions", it)
            }
        }
        GroupTitle("范围预览")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            RegionPreview(config)
        }
        Hint("小预览用于了解范围；打开“显示实际禁触区”可在屏幕边缘查看真实大小。灰色表示已关闭；固定禁区不会自动识别手掌。")
        GroupTitle("左侧")
        SettingsGroup {
            ToggleRow("左侧禁触区", "仅拦截从左侧设定范围内按下的触点", config.getBoolean("left_enabled")) {
                EdgeTouchStore.setBoolean(context, "left_enabled", it)
            }
            SliderRow("左侧宽度", "从屏幕左边缘向内", config.getInt("left_width_dp"), 1..48, " dp") {
                EdgeTouchStore.setNumber(context, "left_width_dp", it)
            }
        }
        GroupTitle("右侧")
        SettingsGroup {
            ToggleRow("右侧禁触区", "仅拦截从右侧设定范围内按下的触点", config.getBoolean("right_enabled")) {
                EdgeTouchStore.setBoolean(context, "right_enabled", it)
            }
            SliderRow("右侧宽度", "从屏幕右边缘向内", config.getInt("right_width_dp"), 1..48, " dp") {
                EdgeTouchStore.setNumber(context, "right_width_dp", it)
            }
        }
        GroupTitle("纵向范围")
        SettingsGroup {
            SliderRow("覆盖高度", "相对于状态栏与导航栏之间的可用高度", config.getInt("height_percent"), 20..100, "%") {
                EdgeTouchStore.setNumber(context, "height_percent", it)
            }
            SliderRow("纵向位置", "0% 靠顶部，50% 居中，100% 靠底部；覆盖整个高度时保持不变", config.getInt("position_percent"), 0..100, "%") {
                EdgeTouchStore.setNumber(context, "position_percent", it)
            }
        }
        Hint("不占用状态栏与底部导航区；锁屏、键盘或系统浮层出现时暂停。小窗手柄和菜单热区自动避让，屏幕旋转后重新计算范围。")
    }
}

@Composable
private fun RegionPreview(config: Bundle) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics = remember(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics
    }
    val bounds = metrics.bounds
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val rectangles = EdgeTouchGeometry.build(bounds.width(), bounds.height(), insets.top, insets.bottom,
        context.resources.displayMetrics.density, true, true, config.getInt("left_width_dp"), config.getInt("right_width_dp"),
        config.getInt("height_percent"), config.getInt("position_percent"), intArrayOf())
    val foreground = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(240.dp).padding(20.dp)) {
        val scale = minOf(size.width / bounds.width(), size.height / bounds.height())
        val width = bounds.width() * scale
        val height = bounds.height() * scale
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        drawRoundRect(surface, Offset(left, top), Size(width, height), CornerRadius(12.dp.toPx()))
        for (i in rectangles.indices step 4) {
            val enabled = config.getBoolean("enabled") && config.getBoolean(if (rectangles[i] == 0) "left_enabled" else "right_enabled")
            drawRect(if (enabled) foreground.copy(alpha = .7f) else neutral.copy(alpha = .35f),
                Offset(left + rectangles[i] * scale, top + rectangles[i + 1] * scale),
                Size((rectangles[i + 2] - rectangles[i]) * scale, (rectangles[i + 3] - rectangles[i + 1]) * scale))
        }
        drawRoundRect(neutral, Offset(left, top), Size(width, height), CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
}
