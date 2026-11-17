package dev.local.sunwindowdemo.features.barrage

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.LaunchableApp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun BarrageScreen(bottomInset: Dp=0.dp,onHistory:()->Unit={}) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,32.dp+bottomInset),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        item { BarrageSettings(onHistory) }
    }
}

/** Detail controls; BarrageScreen owns scrolling and the shared page owns the toolbar. */
@Composable
private fun BarrageSettings(onHistory: () -> Unit = {}) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(BarrageStore.read(context)) }
    var historyCount by remember { mutableStateOf(BarrageStore.historyCount(context)) }
    DisposableEffect(context) {
        val prefs = BarrageStore.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            config = BarrageStore.read(context)
            historyCount = BarrageStore.historyCount(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsGroup {
        ToggleRow("启用弹幕通知", value = config.getBoolean("enabled")) { BarrageStore.enabled(context, it) }
        SliderRow("同时显示条数",
            value = when (config.getInt("lines", 3)) { 5 -> 1; 0 -> 2; else -> 0 }, range = 0..2, unit = "", steps = 1,
            valueLabel = { listOf("三条", "五条", "无限")[it] }) { BarrageStore.lines(context, listOf(3, 5, 0)[it]) }
        BarrageColorRow(config.getInt("color", android.graphics.Color.WHITE)) { BarrageStore.color(context, it) }
        SliderRow("滚动速度", null, value = config.getInt("speed", 100), range = 40..240, unit = "x",
            valueLabel = { String.format(java.util.Locale.ROOT, if(it % 10 == 0) "%.1fx" else "%.2fx", it / 100.0) }) { BarrageStore.speed(context, it) }
        SliderRow("文字大小", null, value = config.getInt("size", 18), range = 12..32, unit = " sp") { BarrageStore.size(context, it) }
        SettingRow("弹幕记录", "$historyCount 条", onHistory)
    }
}

@Composable
internal fun BarrageAppIcon(app: LaunchableApp?) {
    val bitmap = remember(app?.packageName, app?.icon) {
        app?.icon?.let { drawable ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(Canvas(it))
                drawable.bounds = old
            }.asImageBitmap()
        }
    }
    if (bitmap == null) Glyph("apps") else Image(bitmap, null, Modifier.size(42.dp))
}

@Composable
private fun BarrageColorRow(value: Int, onChange: (Int) -> Unit) {
    val colors=listOf("白色" to 0xFFFFFFFF,"黄色" to 0xFFFFE082,"橙色" to 0xFFFFAB91,"紫色" to 0xFFB39DDB,
        "青色" to 0xFF80DEEA,"绿色" to 0xFFA5D6A7,"蓝色" to 0xFF90CAF9,"粉色" to 0xFFF48FB1)
    val shape=CircleShape
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).selectableGroup()
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal=16.dp,vertical=14.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("文字颜色", style=MaterialTheme.typography.titleMedium)
        colors.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { (name, argb) ->
                    val color=argb.toInt()
                    val selected=color==value
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                        Box(Modifier.size(48.dp).clip(shape).background(Color(color))
                            .border(if(selected) 3.dp else 1.dp,
                                if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
                            .selectable(selected,role=Role.RadioButton) {onChange(color)}
                            .semantics {contentDescription=name},contentAlignment=Alignment.Center) {
                            if(selected) Icon(Icons.Default.Check,null,tint=Color(0xFF202124))
                        }
                    }
                }
            }
        }
    }
}
