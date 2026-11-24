package dev.local.sunwindowdemo.features.gamemode

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.PhonePreview
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.ToggleRow
import kotlin.math.roundToInt

/** Content only; the existing SettingsActivity provides the title and native back navigation. */
@Composable
fun GameHandlePositionScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val model = remember(context, configuration) { GameHandleGeometry.read(context) }
    val config = gameConfig()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        GameHandlePositionSettings(config, model)
    }
}

@Composable
internal fun GameHandlePositionSettings(config: Bundle, model: GameHandleGeometry) {
    val context = LocalContext.current
    var landscape by rememberSaveable { mutableStateOf(model.landscape) }
    val preview = remember(model, landscape) { if (model.landscape == landscape) model else model.rotated() }
    val right = GameModeStore.handleSide(config, landscape) == GameModeStore.SIDE_RIGHT
    val saved = GameModeStore.handlePosition(config, landscape)
    var position by remember(preview, saved) { mutableFloatStateOf(saved) }
    val handle = preview.bounds(right, position)
    val displayed = if (position.isFinite()) position else preview.position(handle.top.toFloat())
    val save = { if (position.isFinite()) GameModeStore.handlePosition(context, landscape, position) }
    Column(Modifier.fillMaxWidth()) {
        TabRow(if (landscape) 1 else 0) {
            Tab(!landscape, onClick = { landscape = false }, text = { Text("竖屏") })
            Tab(landscape, onClick = { landscape = true }, text = { Text("横屏") })
        }
        GroupTitle("位置预览")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            HandlePreview(preview, right, position, { position = it }, save)
        }
        GroupTitle("呼出位置")
        SettingsGroup {
            ToggleRow("在右侧呼出", value = right) {
                GameModeStore.handleSide(context, landscape, if (it) GameModeStore.SIDE_RIGHT else GameModeStore.SIDE_LEFT)
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("上下位置", style = MaterialTheme.typography.titleMedium)
                    Text("${(displayed * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = displayed, onValueChange = { position = it }, onValueChangeFinished = save,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "游戏条上下位置" })
            }
        }
        TextButton(onClick = { position = Float.NaN; GameModeStore.resetHandlePosition(context, landscape) },
            modifier = Modifier.padding(top = 8.dp)) {
            Text("重置${if (landscape) "横屏" else "竖屏"}默认位置")
        }
    }
}

@Composable
private fun HandlePreview(model: GameHandleGeometry, right: Boolean, position: Float,
                          onPosition: (Float) -> Unit, onFinished: () -> Unit) {
    val currentPosition by rememberUpdatedState(onPosition)
    val currentFinished by rememberUpdatedState(onFinished)
    val barOutline = MaterialTheme.colorScheme.outline
    PhonePreview(model.width, model.height, Modifier.testTag("game-handle-position-preview")
        .semantics { contentDescription = "游戏条位置预览，拖动上下移动" }
        .pointerInput(model, right) {
            var active = false
            fun move(point: Offset) {
                val scale = minOf(size.width.toFloat() / model.width.coerceAtLeast(1), size.height.toFloat() / model.height.coerceAtLeast(1))
                val top = (size.height - model.height * scale) / 2
                currentPosition(model.position((point.y - top) / scale - model.handleHeight / 2f))
            }
            detectDragGestures(onDragStart = { point ->
                val scale = minOf(size.width.toFloat() / model.width.coerceAtLeast(1), size.height.toFloat() / model.height.coerceAtLeast(1))
                val left = (size.width - model.width * scale) / 2
                val top = (size.height - model.height * scale) / 2
                active = point.x in left..(left + model.width * scale) && point.y in top..(top + model.height * scale)
                if (active) move(point)
            }, onDrag = { change, _ -> if (active) { change.consume(); move(change.position) } },
                onDragEnd = { if (active) currentFinished(); active = false },
                onDragCancel = { if (active) currentFinished(); active = false })
        }) { scale, origin ->
        val handle = model.bounds(right, position)
        if (!handle.isEmpty) {
            val barWidth = minOf(GameHandleGeometry.BAR_WIDTH_DP * model.density, handle.width().toFloat())
            val barHeight = minOf(GameHandleGeometry.BAR_HEIGHT_DP * model.density, handle.height().toFloat())
            // Keep the scaled bar legible; the runtime target and saved position are unchanged.
            val barSize = Size(maxOf(barWidth * scale, 3.dp.toPx()), maxOf(barHeight * scale, 18.dp.toPx()))
            // This schematic has no system-bar gutters: anchor to its frame, not rotated safe.left/right.
            val x = if (right) model.width * scale - barSize.width else 0f
            val y = (handle.top + handle.height() / 2f) * scale - barSize.height / 2
            val barOrigin = origin + Offset(x, y)
            drawRoundRect(barOutline, barOrigin - Offset(.5.dp.toPx(), .5.dp.toPx()),
                Size(barSize.width + 1.dp.toPx(), barSize.height + 1.dp.toPx()), CornerRadius(3 * model.density * scale))
            drawRoundRect(Color.White, barOrigin, barSize, CornerRadius(3 * model.density * scale))
        }
    }
}
