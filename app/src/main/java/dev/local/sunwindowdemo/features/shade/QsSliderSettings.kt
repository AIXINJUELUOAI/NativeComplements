package dev.local.sunwindowdemo.features.shade

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.R
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.SliderRow
import dev.local.sunwindowdemo.core.ui.ToggleRow
import dev.local.sunwindowdemo.features.shade.QsSliderPreferences.Option
import dev.local.sunwindowdemo.features.shade.QsSliderPreferences.Option.*

@Composable
internal fun QsSliderSettings() {
    val context = LocalContext.current
    var values by remember(context) { mutableStateOf(Option.entries.associateWith { QsSliderPreferences.read(context, it) }) }
    DisposableEffect(context) {
        val preferences = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            values = Option.entries.associateWith { QsSliderPreferences.read(context, it) }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun change(option: Option, enabled: Boolean) = QsSliderPreferences.set(context, option, if (enabled) 1 else 0)
    val volume = values.getValue(MEDIA_SLIDER) != 0
    val brightness = values.getValue(BRIGHTNESS_VISIBILITY)
    val brightnessModes = listOf("完整展开时", "下拉一次就显示", "隐藏")
    GroupTitle("快捷设置滑杆")
    SettingsGroup {
        SliderRow("亮度条显示", value = brightness, range = 0..2, unit = "",
            steps = 1, valueLabel = { brightnessModes[it] }) { QsSliderPreferences.set(context, BRIGHTNESS_VISIBILITY, it) }
        ToggleRow("自适应亮度快捷按钮", "亮度条右侧的 A 按钮可切换自适应亮度，长按打开显示设置。",
            value = values.getValue(ADAPTIVE_BRIGHTNESS_SHORTCUT) != 0, enabled = brightness != 2) {
            change(ADAPTIVE_BRIGHTNESS_SHORTCUT, it)
        }
        ToggleRow("媒体音量条", "在原生快捷设置中实时调节媒体音量。", value = volume) { change(MEDIA_SLIDER, it) }
        ToggleRow("音量条常驻", "下拉一次就显示在磁贴上方。",
            value = values.getValue(VOLUME_COMPACT) != 0, enabled = volume) { change(VOLUME_COMPACT, it) }
        ToggleRow("亮度和音量分行显示", "关闭时两个滑杆并排。", value = values.getValue(SLIDER_LAYOUT) != 0,
            enabled = volume && brightness != 2) { change(SLIDER_LAYOUT, it) }
        SettingRow("添加媒体音量磁贴", "点按磁贴静音或恢复到设定音量。", onClick = {
            context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(context, QsMediaVolumeTile::class.java), context.getString(R.string.qs_media_volume_tile),
                Icon.createWithResource(context, R.drawable.ic_game_volume_up), context.mainExecutor,
            ) {}
        })
        SliderRow("取消静音后的音量", value = values.getValue(VOLUME_UNMUTE), range = 1..100, unit = "%") {
            QsSliderPreferences.set(context, VOLUME_UNMUTE, it)
        }
    }
}
