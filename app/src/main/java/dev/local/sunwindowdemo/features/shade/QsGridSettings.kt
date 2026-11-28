package dev.local.sunwindowdemo.features.shade

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.SliderRow
import dev.local.sunwindowdemo.core.ui.ToggleRow
import dev.local.sunwindowdemo.features.shade.QsGridPreferences.Option
import dev.local.sunwindowdemo.features.shade.QsGridPreferences.Option.*

@Composable
internal fun QsGridSettings() {
    val context = LocalContext.current
    var values by remember(context) { mutableStateOf(Option.entries.associateWith { QsGridPreferences.read(context, it) }) }
    DisposableEffect(context) {
        val preferences = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            values = Option.entries.associateWith { QsGridPreferences.read(context, it) }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val update = { option: Option, value: Int -> QsGridPreferences.set(context, option, value) }
    GroupTitle("磁贴外观")
    SettingsGroup {
        ToggleRow("原生圆形磁贴", "将单格磁贴居中显示为圆形，沿用系统图标和按压动效。",
            value = values.getValue(ROUND_TILES) != 0) { update(ROUND_TILES, if (it) 1 else 0) }
        SliderRow("磁贴标签字号", value = values.getValue(LABEL_SIZE), range = LABEL_SIZE.min..LABEL_SIZE.max, unit = "%") {
            update(LABEL_SIZE, it)
        }
        SliderRow("磁贴副标签字号", value = values.getValue(SECONDARY_LABEL_SIZE),
            range = SECONDARY_LABEL_SIZE.min..SECONDARY_LABEL_SIZE.max, unit = "%") {
            update(SECONDARY_LABEL_SIZE, it)
        }
    }
    GridOrientation("竖屏磁贴数量", PORTRAIT_COLUMNS, PORTRAIT_ROWS, PORTRAIT_COMPACT_ROWS, values, update)
    GridOrientation("横屏磁贴数量", LANDSCAPE_COLUMNS, LANDSCAPE_ROWS, LANDSCAPE_COMPACT_ROWS, values, update)
}

@Composable
private fun GridOrientation(title: String, columns: Option, rows: Option, compactRows: Option,
                            values: Map<Option, Int>, update: (Option, Int) -> Unit) {
    GroupTitle(title)
    SettingsGroup {
        val columnCounts = listOf(0) + (2..columns.max)
        SliderRow("每行列数", "修改行列数后重载系统界面。", value = columnCounts.indexOf(values.getValue(columns)),
            range = columnCounts.indices, unit = "", steps = columnCounts.size - 2,
            valueLabel = { if (it == 0) "系统默认" else "${columnCounts[it]} 列" }) { update(columns, columnCounts[it]) }
        RowCount("完整面板每页行数", rows, values.getValue(rows), update)
        RowCount("下拉一次时的行数", compactRows, values.getValue(compactRows), update)
    }
}

@Composable
private fun RowCount(title: String, option: Option, value: Int, update: (Option, Int) -> Unit) {
    SliderRow(title, value = value, range = 0..option.max, unit = "", steps = option.max - 1,
        valueLabel = { if (it == 0) "系统默认" else "$it 行" }) { update(option, it) }
}
