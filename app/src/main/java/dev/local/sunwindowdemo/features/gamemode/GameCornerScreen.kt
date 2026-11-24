package dev.local.sunwindowdemo.features.gamemode

import android.content.SharedPreferences
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.ui.PhonePreview
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import kotlin.math.roundToInt

/** The edge-touch phone preview, laid horizontally; this page never changes orientation. */
@Composable
fun GameCornerScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(GameModeStore.read(context)) }
    DisposableEffect(context) {
        val prefs = GameModeStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = GameModeStore.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var percent by remember(config.getFloat("corner_radius_percent", 10f)) {
        mutableFloatStateOf(config.getFloat("corner_radius_percent", 10f))
    }
    val configuration = LocalConfiguration.current
    val displayBounds = remember(context, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    }
    val modelWidth = maxOf(displayBounds.width(), displayBounds.height()).coerceAtLeast(1)
    val modelHeight = minOf(displayBounds.width(), displayBounds.height()).coerceAtLeast(1)
    // Convert the existing stored ratio to actual display dp, without changing saved configurations.
    val shortSideDp = modelHeight / context.resources.displayMetrics.density
    val radiusDp = shortSideDp * percent / 100f
    val sizeLabel = radiusDp.roundToInt()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        GroupTitle("范围预览")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CornerPreview(modelWidth, modelHeight, percent)
        }
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("大小", style = MaterialTheme.typography.bodyLarge)
                    Text("$sizeLabel dp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = radiusDp, onValueChange = {
                    val next = it.coerceIn(GameCornerGeometry.MIN_DP.toFloat(), GameCornerGeometry.MAX_DP.toFloat()) / shortSideDp * 100f
                    if (next != percent) { percent = next; GameModeStore.cornerRadius(context, next) }
                }, valueRange = GameCornerGeometry.MIN_DP.toFloat()..GameCornerGeometry.MAX_DP.toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "大小" })
            }
        }
    }
}

private data class TrialTouch(val position: Offset, val blocked: Boolean)

@Composable
private fun CornerPreview(width: Int, height: Int, percent: Float) {
    val touches = remember(width, height) { mutableStateMapOf<PointerId, TrialTouch>() }
    val currentPercent by rememberUpdatedState(percent)
    val sector = MaterialTheme.colorScheme.primary
    PhonePreview(width, height, Modifier.testTag("game-corner-preview")
        .semantics { contentDescription = "底部左右双扇区预览" }
        .pointerInput(width, height) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scale = minOf(size.width.toFloat() / width, size.height.toFloat() / height)
                        val left = (size.width - width * scale) / 2
                        val top = (size.height - height * scale) / 2
                        for (change in event.changes) {
                            if (!change.pressed) { touches.remove(change.id); continue }
                            val previous = touches[change.id]
                            if (previous == null && change.previousPressed) continue
                            val x = (change.position.x - left) / scale
                            val y = (change.position.y - top) / scale
                            if (previous == null && (x < 0 || y < 0 || x >= width || y >= height)) continue
                            val blocked = previous?.blocked ?: GameCornerGeometry.inside(x, y, width, height, currentPercent)
                            touches[change.id] = TrialTouch(change.position, blocked)
                            change.consume()
                        }
                    }
                }
            } finally { touches.clear() }
        }) { scale, origin ->
        val radius = GameCornerGeometry.radius(width, height, percent) * scale
        for (center in listOf(origin + Offset(0f, height * scale), origin + Offset(width * scale, height * scale))) {
            drawCircle(sector.copy(alpha = .3f), radius, center)
            drawCircle(sector, radius, center, style = Stroke(1.dp.toPx()))
        }
        touches.values.forEach { touch ->
            val color = if (touch.blocked) Color(0xffd32f2f) else Color(0xff218739)
            drawCircle(color.copy(alpha = .25f), 15.dp.toPx(), touch.position)
            drawCircle(color, 7.dp.toPx(), touch.position)
        }
    }
}
