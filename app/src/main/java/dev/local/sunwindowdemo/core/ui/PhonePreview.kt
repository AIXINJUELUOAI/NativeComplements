package dev.local.sunwindowdemo.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/** The existing edge/corner setting preview frame, shared by both screen orientations. */
@Composable
internal fun PhonePreview(width: Int, height: Int, modifier: Modifier = Modifier,
                          content: DrawScope.(scale: Float, origin: Offset) -> Unit) {
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val border = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(240.dp).padding(20.dp).then(modifier)) {
        if (width <= 0 || height <= 0) return@Canvas
        val scale = minOf(size.width / width, size.height / height)
        val phoneSize = Size(width * scale, height * scale)
        val origin = Offset((size.width - phoneSize.width) / 2, (size.height - phoneSize.height) / 2)
        val corner = CornerRadius(12.dp.toPx())
        val outline = Path().apply {
            addRoundRect(RoundRect(origin.x, origin.y, origin.x + phoneSize.width, origin.y + phoneSize.height, corner))
        }
        drawRoundRect(background, origin, phoneSize, corner)
        clipPath(outline) { content(scale, origin) }
        drawRoundRect(border, origin, phoneSize, corner, style = Stroke(1.dp.toPx()))
    }
}
