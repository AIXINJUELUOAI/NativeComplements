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
    onCalls: () -> Unit = {},
    onEdgeTouch: () -> Unit = {},
    onSuggestions: () -> Unit = {},
    onMotion: () -> Unit = {},
    onIcons: () -> Unit = {},
    onBarrage: () -> Unit = {},
    onShade: () -> Unit = {},
    onAlive: (() -> Unit)? = null,
) {
    AdaptationNotice(onAbout)
    Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 16.dp, bottom = 20.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    SettingsGroup {
        FeatureRow("小窗", "window", onWindow)
        FeatureRow("弹幕通知", "barrage", onBarrage)
        FeatureRow("快捷建议", "spark", onSuggestions)
        FeatureRow("图标适配", "apps", onIcons)
        FeatureRow("来电拦截", "phone", onCalls)
        FeatureRow("加速度传感器控制", "shake", onMotion)
        FeatureRow("边缘防误触", "edge", onEdgeTouch)
        FeatureRow("外观与控制", "window", onShade)
        onAlive?.let { FeatureRow("Alive 壁纸", "alive", it, badge = "Beta") }
    }
    Spacer(Modifier.height(16.dp))
    SettingsGroup {
        FeatureRow("关于", "info", onAbout)
    }
}

@Composable
private fun FeatureRow(title: String, icon: String, onClick: () -> Unit, badge: String? = null) {
    SettingRow(title, onClick = onClick, leading = { Glyph(icon) }, trailing = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (badge != null) Surface(shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(badge, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}
