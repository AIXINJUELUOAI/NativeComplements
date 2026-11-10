package dev.local.sunwindowdemo.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.WindowRuntime
import dev.local.sunwindowdemo.core.ui.*

/** Module health, derived once so the card colour, title and subtitle always agree. */
private enum class Health { Checking, Attention, Active, Ready }

private fun health(runtime: WindowRuntime) = when {
    !runtime.received -> Health.Checking
    !runtime.compatible || runtime.state == "recovery" || !runtime.controlEnabled || !runtime.hostConnected -> Health.Attention
    runtime.state == "active" -> Health.Active
    else -> Health.Ready
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun HomeScreen(runtime: WindowRuntime, onWindow: () -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit, onCalls: () -> Unit = {}, onEdgeTouch: () -> Unit = {}, onSuggestions: () -> Unit = {}, onMotion: () -> Unit = {}, onIcons: () -> Unit = {}, onLicenses: () -> Unit = {}) {
    val state = health(runtime)
    val scheme = MaterialTheme.colorScheme
    val container = when (state) {
        Health.Attention -> scheme.errorContainer
        Health.Active -> scheme.tertiaryContainer
        Health.Ready -> scheme.primaryContainer
        Health.Checking -> scheme.secondaryContainer
    }
    val content = when (state) {
        Health.Attention -> scheme.onErrorContainer
        Health.Active -> scheme.onTertiaryContainer
        Health.Ready -> scheme.onPrimaryContainer
        Health.Checking -> scheme.onSecondaryContainer
    }
    Surface(onClick = onDiagnostics, shape = RoundedCornerShape(28.dp), color = container, contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(14.dp).background(content, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when {
                    !runtime.received -> "检查模块状态"
                    !runtime.compatible -> "需要重启手机"
                    runtime.state == "recovery" -> "有窗口等待恢复"
                    runtime.state == "active" -> "小窗正在运行"
                    !runtime.controlEnabled -> "小窗控制已关闭"
                    !runtime.hostConnected -> "开启小窗控制"
                    else -> "小窗已就绪"
                }, style = MaterialTheme.typography.titleLarge)
                Text(when {
                    !runtime.received -> "查看模块启用与服务连接情况"
                    !runtime.compatible -> "点击查看启用步骤"
                    runtime.state == "recovery" -> "点击检查并恢复原窗口"
                    runtime.state == "active" -> "点击查看状态或恢复原窗口"
                    !runtime.controlEnabled -> "在小窗页面点击顶部卡片开启"
                    !runtime.hostConnected -> "后端已连接 · SystemUI 宿主未连接"
                    else -> "系统后端与 SystemUI 宿主已连接"
                }, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
    GroupTitle("窗口与输入")
    SettingsGroup {
        SettingRow("小窗", "角落启动、快捷应用与窗口操作", onWindow,
            leading = { Glyph("window", Color(0xFFB6EAFE), Color(0xFF00556A)) }, trailing = { Chevron() })
        SettingRow("边缘防误触", "减少单手握持误触，左右范围分别调整", onEdgeTouch,
            leading = { Glyph("edge", Color(0xFFF1DDF9), Color(0xFF6A3585)) }, trailing = { Chevron() })
        SettingRow("防摇一摇", "按应用控制开屏与持续传感器访问", onMotion,
            leading = { Glyph("shake", Color(0xFFFFDAD4), Color(0xFF8C3022)) }, trailing = { Chevron() })
    }
    GroupTitle("智能与通信")
    SettingsGroup {
        SettingRow("建议", "手电筒、蓝牙、验证码与复制内容快捷操作", onSuggestions,
            leading = { Glyph("spark", Color(0xFFFFE4BC), Color(0xFF795500)) }, trailing = { Chevron() })
        SettingRow("电话拦截", "双卡来电控制、号码规则与拦截记录", onCalls,
            leading = { Glyph("phone", Color(0xFFD7F0D2), Color(0xFF245328)) }, trailing = { Chevron() })
    }
    GroupTitle("外观")
    SettingsGroup {
        SettingRow("图标补全", "导入本地双层素材，补全桌面图标与原生动画", onIcons,
            leading = { Glyph("apps", Color(0xFFFCE4EC), Color(0xFF743A58)) }, trailing = { Chevron() })
    }
    GroupTitle("应用")
    SettingsGroup {
        SettingRow("关于", "类原生补全计划 · ${BuildConfig.VERSION_NAME}", onAbout, leading = { Glyph("info") }, trailing = { Chevron() })
        SettingRow("开源许可", "参考项目与依赖库的来源和许可", onLicenses, leading = { Glyph("check") }, trailing = { Chevron() })
    }
}
