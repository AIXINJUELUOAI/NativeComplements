package dev.local.sunwindowdemo.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun GroupTitle(title: String) {
    Text(title, Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
               leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .toggleable(value, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
fun SliderRow(title: String, subtitle: String, value: Int, range: IntRange, unit: String, onCommit: (Int) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${pending.roundToInt()}$unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = pending, onValueChange = { pending = it.roundToInt().toFloat() }, onValueChangeFinished = { onCommit(pending.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Glyph(kind: String, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
          foreground: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(44.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val line = Stroke(width = 2.dp.toPx())
            fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
            when (kind) {
                "window" -> {
                    drawRoundRect(foreground, point(.08f,.12f), Size(size.width*.76f,size.height*.66f), CornerRadius(3.dp.toPx()), style=line)
                    drawRoundRect(container, point(.4f,.44f), Size(size.width*.55f,size.height*.46f), CornerRadius(3.dp.toPx()))
                    drawRoundRect(foreground, point(.4f,.44f), Size(size.width*.55f,size.height*.46f), CornerRadius(3.dp.toPx()), style=line)
                }
                "apps" -> for (x in 0..2) for (y in 0..2) drawCircle(foreground, 2.3.dp.toPx(), point(.2f+x*.3f,.2f+y*.3f))
                "gesture" -> {
                    drawLine(foreground,point(.16f,.84f),point(.8f,.2f),2.5.dp.toPx())
                    drawLine(foreground,point(.4f,.2f),point(.8f,.2f),2.5.dp.toPx())
                    drawLine(foreground,point(.8f,.6f),point(.8f,.2f),2.5.dp.toPx())
                    drawCircle(foreground,2.5.dp.toPx(),point(.16f,.84f))
                }
                "tune" -> for (y in 0..2) {
                    val py=.2f+y*.3f
                    drawLine(foreground,point(.1f,py),point(.9f,py),2.dp.toPx())
                    drawCircle(container,3.dp.toPx(),point(if(y==1).65f else .35f,py))
                    drawCircle(foreground,3.dp.toPx(),point(if(y==1).65f else .35f,py),style=line)
                }
                "check" -> {
                    drawCircle(foreground,size.width*.39f,style=line)
                    drawLine(foreground,point(.27f,.5f),point(.44f,.67f),2.dp.toPx())
                    drawLine(foreground,point(.44f,.67f),point(.75f,.34f),2.dp.toPx())
                }
                else -> {
                    drawCircle(foreground,size.width*.39f,style=line)
                    drawLine(foreground,point(.5f,.43f),point(.5f,.73f),2.5.dp.toPx())
                    drawCircle(foreground,1.4.dp.toPx(),point(.5f,.28f))
                }
            }
        }
    }
}
