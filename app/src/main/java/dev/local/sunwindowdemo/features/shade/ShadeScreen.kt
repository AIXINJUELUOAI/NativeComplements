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
    var offset by remember { mutableIntStateOf(ShadeStore.offset(context)) }
    DisposableEffect(context) {
        val prefs = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = ShadeStore.read(context).getBoolean("enabled")
            offset = ShadeStore.offset(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        SettingsGroup {
            ToggleRow("横屏双栏", value = enabled) { ShadeStore.setEnabled(context, it) }
        }
        GroupTitle("状态栏")
        SettingsGroup {
            SliderRow("图标垂直位置", value = offset, range = -32..32, unit = " dp") {
                ShadeStore.setOffset(context, it)
            }
        }
    }
}
