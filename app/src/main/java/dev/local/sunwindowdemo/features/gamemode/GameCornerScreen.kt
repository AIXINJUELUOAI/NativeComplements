package dev.local.sunwindowdemo.features.gamemode

import android.app.Activity
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Preview only: no real input suppression is installed over this page or its controls. */
@Composable
fun GameCornerScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    // Save the pre-preview policy across the Activity recreation caused by rotating into landscape.
    val previousOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity != null && !activity.isChangingConfigurations) activity.requestedOrientation = previousOrientation
        }
    }
    var config by remember { mutableStateOf(GameModeStore.read(context)) }
    DisposableEffect(context) {
        val prefs = GameModeStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = GameModeStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var percent by remember(config.getInt("corner_radius_percent", 10)) {
        mutableIntStateOf(config.getInt("corner_radius_percent", 10).coerceIn(4, 24))
    }
    val configuration = LocalConfiguration.current
    val displayBounds = remember(context, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    }
    // Model the full display in landscape, not the padded SettingsPage or this preview's bounds.
    val modelWidth = maxOf(displayBounds.width(), displayBounds.height()).coerceAtLeast(1)
    val modelHeight = minOf(displayBounds.width(), displayBounds.height()).coerceAtLeast(1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp + bottomInset)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val controls: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("扇区大小：短边的 $percent%", style = MaterialTheme.typography.titleMedium)
                    Slider(value = percent.toFloat(), onValueChange = {
                        val next = it.roundToInt().coerceIn(4, 24)
                        if (next != percent) { percent = next; GameModeStore.cornerRadius(context, next) }
                    }, valueRange = 4f..24f, steps = 19,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "扇区大小" })
                    Text("四角使用相同半径，范围 4%～24%。", style = MaterialTheme.typography.bodySmall)
                    Text("红色：内起手，全程屏蔽\n绿色：外起手，全程放行", style = MaterialTheme.typography.bodyMedium)
                    Text("在预览中按下并拖动，可同时测试多根手指。这里只模拟判断，不会屏蔽本页控件。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (maxWidth >= 560.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CornerPreview(modelWidth, modelHeight, percent, Modifier.weight(1f))
                Box(Modifier.width(220.dp)) { controls() }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CornerPreview(modelWidth, modelHeight, percent, Modifier.fillMaxWidth())
                controls()
            }
        }
    }
}

private data class TrialTouch(val position: Offset, val blocked: Boolean)

@Composable
private fun CornerPreview(width: Int, height: Int, percent: Int, modifier: Modifier = Modifier) {
    val touches = remember(width, height) { mutableStateMapOf<PointerId, TrialTouch>() }
    val currentPercent by rememberUpdatedState(percent)
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val sector = MaterialTheme.colorScheme.primary
    val border = MaterialTheme.colorScheme.outline
    val blockedColor = Color(0xffd32f2f)
    val allowedColor = Color(0xff218739)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("横屏范围与触摸预览", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().aspectRatio(width.toFloat() / height).testTag("game-corner-preview")
            .semantics { contentDescription = "四角扇区预览，半径为屏幕短边的 $percent%，触点内起手红色、外起手绿色" }
            .pointerInput(width, height) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (!change.pressed) { touches.remove(change.id); continue }
                                val previous = touches[change.id]
                                val blocked = previous?.blocked ?: GameCornerGeometry.inside(
                                    change.position.x * width / size.width,
                                    change.position.y * height / size.height, width, height, currentPercent)
                                touches[change.id] = TrialTouch(change.position, blocked)
                                // Consume only the miniature test surface so its drags do not scroll the page.
                                change.consume()
                            }
                        }
                    }
                } finally { touches.clear() }
            }) {
            drawRect(background)
            val scale = size.width / width
            val radius = GameCornerGeometry.radius(width, height, percent) * scale
            clipRect {
                for (center in listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))) {
                    drawCircle(sector.copy(alpha = .3f), radius, center)
                    drawCircle(sector, radius, center, style = Stroke(1.dp.toPx()))
                }
                touches.values.forEach { touch ->
                    val color = if (touch.blocked) blockedColor else allowedColor
                    drawCircle(color.copy(alpha = .25f), 15.dp.toPx(), touch.position)
                    drawCircle(color, 7.dp.toPx(), touch.position)
                }
            }
            drawRect(border, Offset.Zero, Size(size.width, size.height), style = Stroke(1.dp.toPx()))
        }
    }
}
