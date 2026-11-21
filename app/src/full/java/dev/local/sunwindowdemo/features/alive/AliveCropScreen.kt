package dev.local.sunwindowdemo.features.alive

import android.graphics.ImageDecoder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.sunwindowdemo.core.ui.SettingsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AliveCropScreen(
    snapshot: AliveSettings.Snapshot,
    screenAspect: Float,
    cancel: () -> Unit,
    confirm: (AliveCrop) -> Unit,
) {
    val context = LocalContext.current
    var x by rememberSaveable(snapshot.photo) { mutableFloatStateOf(snapshot.crop.x()) }
    var y by rememberSaveable(snapshot.photo) { mutableFloatStateOf(snapshot.crop.y()) }
    var zoom by rememberSaveable(snapshot.photo) { mutableFloatStateOf(snapshot.crop.zoom()) }
    var loadFailed by remember(snapshot.photo) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, context, snapshot.photo) {
        try {
            value = withContext(Dispatchers.IO) {
                AlivePhoto.decodeFit(ImageDecoder.createSource(snapshot.photoFile(context)), 1536).asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    Dialog(onDismissRequest = cancel, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        SettingsPage(title = "调整取景", onBack = cancel, expandedTitle = false, actions = {
            TextButton(enabled = image != null, onClick = { x = .5f; y = .5f; zoom = 1f }) { Text("复位") }
        }) { bottomInset ->
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = bottomInset + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val photo = image
                    if (photo == null) {
                        if (loadFailed) Text("无法读取照片") else CircularProgressIndicator()
                    } else {
                        val frameWidth = minOf(maxWidth, maxHeight * screenAspect)
                        val sourceAspect = photo.width.toFloat() / photo.height
                        Canvas(Modifier.width(frameWidth).aspectRatio(screenAspect)
                            .semantics { contentDescription = "壁纸取景区域" }
                            .pointerInput(photo, screenAspect) {
                                detectTransformGestures { focus, pan, scale, _ ->
                                    val next = AliveCrop(x, y, zoom).gesture(sourceAspect, screenAspect,
                                        focus.x / size.width, focus.y / size.height,
                                        pan.x / size.width, pan.y / size.height, scale)
                                    x = next.x()
                                    y = next.y()
                                    zoom = next.zoom()
                                }
                            }) {
                            val view = AliveCrop(x, y, zoom).window(sourceAspect, screenAspect)
                            val left = (view.left() * photo.width).roundToInt().coerceIn(0, photo.width - 1)
                            val top = (view.top() * photo.height).roundToInt().coerceIn(0, photo.height - 1)
                            val right = ((view.left() + view.width()) * photo.width).roundToInt().coerceIn(left + 1, photo.width)
                            val bottom = ((view.top() + view.height()) * photo.height).roundToInt().coerceIn(top + 1, photo.height)
                            drawImage(photo, srcOffset = IntOffset(left, top), srcSize = IntSize(right - left, bottom - top),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                        }
                    }
                }
                if (image != null) Text("拖动调整位置，双指缩放")
                Button(onClick = { confirm(AliveCrop(x, y, zoom)) }, enabled = image != null,
                    modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}
