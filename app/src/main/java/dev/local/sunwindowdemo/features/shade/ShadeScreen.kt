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
import dev.local.sunwindowdemo.features.iconlayers.IconLayerStore
import dev.local.sunwindowdemo.features.navbar.NavbarStore

@Composable
fun ShadeScreen(bottomInset: Dp = 0.dp, desktopExtras: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ShadeStore.read(context).getBoolean("enabled")) }
    var drawerMonet by remember { mutableStateOf(ShadeStore.drawerMonet(context)) }
    var coverMonet by remember { mutableStateOf(ShadeStore.coverMonet(context)) }
    var desktopDoubleTapSleep by remember { mutableStateOf(ShadeStore.desktopDoubleTapSleep(context)) }
    var systemThemed by remember { mutableStateOf(IconLayerStore.systemThemed(context)) }
    var offset by remember { mutableIntStateOf(ShadeStore.offset(context)) }
    var notificationIconScale by remember { mutableIntStateOf(ShadeStore.iconScale(context, true)) }
    var systemIconScale by remember { mutableIntStateOf(ShadeStore.iconScale(context, false)) }
    var signalDisplay by remember { mutableStateOf(ShadeStore.read(context).getBoolean("signalDisplay")) }
    var navbarImmersive by remember { mutableStateOf(NavbarStore.preferences(context).getBoolean("enabled", false)) }
    DisposableEffect(context) {
        val prefs = ShadeStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = ShadeStore.read(context).getBoolean("enabled")
            drawerMonet = ShadeStore.drawerMonet(context)
            coverMonet = ShadeStore.coverMonet(context)
            desktopDoubleTapSleep = ShadeStore.desktopDoubleTapSleep(context)
            offset = ShadeStore.offset(context)
            notificationIconScale = ShadeStore.iconScale(context, true)
            systemIconScale = ShadeStore.iconScale(context, false)
            signalDisplay = ShadeStore.read(context).getBoolean("signalDisplay")
        }
        val icons = IconLayerStore.prefs(context)
        val themeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            systemThemed = IconLayerStore.systemThemed(context)
        }
        val navbar = NavbarStore.preferences(context)
        val navbarListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            navbarImmersive = navbar.getBoolean("enabled", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        icons.registerOnSharedPreferenceChangeListener(themeListener)
        navbar.registerOnSharedPreferenceChangeListener(navbarListener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            icons.unregisterOnSharedPreferenceChangeListener(themeListener)
            navbar.unregisterOnSharedPreferenceChangeListener(navbarListener)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
        GroupTitle("桌面")
        SettingsGroup {
            ToggleRow("双击桌面锁屏", value = desktopDoubleTapSleep) {
                ShadeStore.setDesktopDoubleTapSleep(context, it)
            }
            desktopExtras()
            val hint = when {
                systemThemed -> null
                dev.local.sunwindowdemo.core.LauncherTargets.home(context) == "com.android.launcher3" ->
                    "需先在“壁纸与样式”中将应用图标样式设为 Minimal"
                else -> "需先在“壁纸与样式”中打开主题图标"
            }
            ToggleRow("抽屉莫奈图标", hint ?: "应用抽屉与推荐行使用和桌面相同的主题图标",
                value = drawerMonet && systemThemed, enabled = systemThemed) { ShadeStore.setDrawerMonet(context, it) }
            ToggleRow("遮罩莫奈图标", value = coverMonet && systemThemed, enabled = systemThemed) { ShadeStore.setCoverMonet(context, it) }
            ToggleRow("小白条沉浸", value = navbarImmersive) {
                NavbarStore.setEnabled(context, it)
            }
        }
        GroupTitle("状态栏与控制中心")
        SettingsGroup {
            ToggleRow("横屏双栏", value = enabled) { ShadeStore.setEnabled(context, it) }
            ToggleRow("信号显示调整", value = signalDisplay) {
                ShadeStore.setSignalDisplay(context, it)
            }
            SliderRow("通知图标大小",
                value = notificationIconScale, range = 80..120, unit = "%") {
                ShadeStore.setIconScale(context, true, it)
            }
            SliderRow("系统状态图标大小",
                value = systemIconScale, range = 80..120, unit = "%") {
                ShadeStore.setIconScale(context, false, it)
            }
            SliderRow("图标垂直位置", value = offset, range = -32..32, unit = " dp") {
                ShadeStore.setOffset(context, it)
            }
        }
    }
}
