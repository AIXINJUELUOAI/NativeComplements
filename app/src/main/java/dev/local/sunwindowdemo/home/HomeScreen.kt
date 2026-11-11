package dev.local.sunwindowdemo.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
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
) {
    Text("为类原生 Android 补充日常功能", Modifier.padding(start = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 16.dp, bottom = 20.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    SettingsGroup {
        FeatureRow("小窗", "手势唤起与多窗口操作", "window", onWindow)
        FeatureRow("弹幕通知", "在选定应用中显示新通知", "barrage", onBarrage)
        FeatureRow("快捷建议", "根据当前情境提供快捷操作", "spark", onSuggestions)
        FeatureRow("图标适配", "分层图标、主题图标与通知图标", "apps", onIcons)
        FeatureRow("来电拦截", "按 SIM 卡与号码规则管理来电", "phone", onCalls)
        FeatureRow("加速度传感器控制", "按应用限制摇动广告触发", "shake", onMotion)
        FeatureRow("边缘防误触", "自定义屏幕两侧的触控限制范围", "edge", onEdgeTouch)
    }
    Spacer(Modifier.height(16.dp))
    SettingsGroup {
        FeatureRow("关于", "软件介绍、开发者与开源信息", "info", onAbout)
    }
}

@Composable
private fun FeatureRow(title: String, summary: String, icon: String, onClick: () -> Unit) {
    SettingRow(title, summary, onClick, leading = { Glyph(icon) }, trailing = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}
