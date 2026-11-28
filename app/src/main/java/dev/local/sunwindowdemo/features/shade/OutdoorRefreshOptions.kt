package dev.local.sunwindowdemo.features.shade

import android.content.SharedPreferences
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingRow
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.SliderRow
import dev.local.sunwindowdemo.core.ui.ToggleRow
import dev.local.sunwindowdemo.features.shade.OutdoorRefreshSettings.Options
import dev.local.sunwindowdemo.features.shade.OutdoorRefreshSettings.Trigger

@Composable
internal fun OutdoorRefreshOptions() {
    val context = LocalContext.current
    if (!remember(context) { OutdoorRefreshSettings.supportedDevice() && PixelOsShade.supports(context) }) return
    var options by remember(context) { mutableStateOf(OutdoorRefreshSettings.read(context)) }
    DisposableEffect(context) {
        val prefs = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            options = OutdoorRefreshSettings.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save(next: Options) = OutdoorRefreshSettings.save(context, next)

    GroupTitle("户外固定刷新率")
    SettingsGroup {
        ToggleRow("户外关闭 LTPO", "触发时固定在 120 Hz，退出后恢复系统刷新率策略。",
            options.enabled()) { save(Options(it, options.trigger(), options.brightness(), options.light())) }
        if (options.enabled()) {
            Trigger.entries.forEach { trigger ->
                val (title, detail) = when (trigger) {
                    Trigger.MANUAL -> "手动触发" to "开启后在亮屏期间生效。"
                    Trigger.BRIGHTNESS -> "按亮度百分比触发" to "屏幕亮度达到设定阈值时生效。"
                    Trigger.LIGHT -> "按环境光触发" to "光线传感器读数达到设定阈值时生效。"
                }
                SettingRow(title, detail, onClick = {
                    save(Options(true, trigger, options.brightness(), options.light()))
                }, trailing = { RadioButton(selected = options.trigger() == trigger, onClick = null) })
            }
            when (options.trigger()) {
                Trigger.MANUAL -> Unit
                Trigger.BRIGHTNESS -> SliderRow("屏幕亮度阈值", value = options.brightness(), range = 1..100, unit = "%") {
                    save(Options(true, options.trigger(), it, options.light()))
                }
                Trigger.LIGHT -> SliderRow("环境光照度阈值", value = options.light() / 100, range = 1..300, unit = " lx",
                    valueLabel = { "${it * 100} lx" }) { save(Options(true, options.trigger(), options.brightness(), it * 100)) }
            }
        }
    }
}
