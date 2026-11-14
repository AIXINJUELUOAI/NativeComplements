package dev.local.sunwindowdemo.features.shade

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.core.ui.GroupTitle
import dev.local.sunwindowdemo.core.ui.SettingsGroup
import dev.local.sunwindowdemo.core.ui.SliderRow
import dev.local.sunwindowdemo.core.ui.ToggleRow

@Composable
fun ShadeScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ShadeStore.read(context).getBoolean("enabled")) }
    var drawerMonet by remember { mutableStateOf(ShadeStore.drawerMonet(context)) }
    var offset by remember { mutableIntStateOf(ShadeStore.offset(context)) }
    var notificationIconScale by remember { mutableIntStateOf(ShadeStore.iconScale(context, true)) }
    var systemIconScale by remember { mutableIntStateOf(ShadeStore.iconScale(context, false)) }
    var signalDisplay by remember { mutableStateOf(ShadeStore.read(context).getBoolean("signalDisplay")) }
    DisposableEffect(context) {
        val prefs = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = ShadeStore.read(context).getBoolean("enabled")
            drawerMonet = ShadeStore.drawerMonet(context)
            offset = ShadeStore.offset(context)
            notificationIconScale = ShadeStore.iconScale(context, true)
            systemIconScale = ShadeStore.iconScale(context, false)
            signalDisplay = ShadeStore.read(context).getBoolean("signalDisplay")
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        GroupTitle("应用抽屉")
        SettingsGroup {
            ToggleRow("抽屉莫奈图标", "仅应用抽屉网格使用原生主题图标，无莫奈素材时保留原图标，不影响桌面与搜索",
                value = drawerMonet) { ShadeStore.setDrawerMonet(context, it) }
        }
        GroupTitle("通知与控制中心")
        SettingsGroup {
            ToggleRow("横屏双栏", value = enabled) { ShadeStore.setEnabled(context, it) }
        }
        GroupTitle("状态栏")
        SettingsGroup {
            ToggleRow("信号显示调整", "调整 5G 信号格分档，仅影响显示，不增强信号", value = signalDisplay) {
                ShadeStore.setSignalDisplay(context, it)
            }
            SliderRow("通知图标大小", "左侧通知图标，100% 为原生大小",
                value = notificationIconScale, range = 80..120, unit = "%") {
                ShadeStore.setIconScale(context, true, it)
            }
            SliderRow("系统状态图标大小", "右侧系统状态图标，不含 5G、信号、电池和时间；100% 为原生大小",
                value = systemIconScale, range = 80..120, unit = "%") {
                ShadeStore.setIconScale(context, false, it)
            }
            SliderRow("图标垂直位置", value = offset, range = -32..32, unit = " dp") {
                ShadeStore.setOffset(context, it)
            }
        }
    }
}
