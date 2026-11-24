package dev.local.sunwindowdemo.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.core.adaptation.AdaptationNotice
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun HomeScreen(
    onWindow: () -> Unit,
    onAbout: () -> Unit,
    onHarassment: () -> Unit = {},
    onEdgeTouch: () -> Unit = {},
    onSuggestions: () -> Unit = {},
    onMotion: () -> Unit = {},
    onIcons: () -> Unit = {},
    onGameMode: () -> Unit = {},
    onShade: () -> Unit = {},
    onAlive: (() -> Unit)? = null,
    onBackground: (() -> Unit)? = null,
) {
    AdaptationNotice(onAbout)
    Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 16.dp, bottom = 20.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    SettingsGroup {
        FeatureRow("小窗", "window", onWindow)
        onBackground?.let { FeatureRow("后台管理", "apps", it) }
        FeatureRow("游戏模式", "game", onGameMode)
        FeatureRow("快捷建议", "spark", onSuggestions)
        FeatureRow("图标适配", "apps", onIcons)
        FeatureRow("骚扰拦截", "phone", onHarassment)
        FeatureRow("加速度传感器控制", "shake", onMotion)
        FeatureRow("边缘防误触", "edge", onEdgeTouch)
        FeatureRow("外观与控制", "window", onShade)
        onAlive?.let { FeatureRow("Alive 壁纸", "alive", it) }
    }
    Spacer(Modifier.height(16.dp))
    SettingsGroup {
        FeatureRow("关于", "info", onAbout)
    }
}

@Composable
private fun FeatureRow(title: String, icon: String, onClick: () -> Unit) {
    SettingRow(title, onClick = onClick, leading = { Glyph(icon) }, trailing = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}
