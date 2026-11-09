package dev.local.sunwindowdemo.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.BuildConfig
import dev.local.sunwindowdemo.WindowRuntime
import dev.local.sunwindowdemo.core.ui.*

@Composable
fun HomeScreen(runtime: WindowRuntime, onWindow: () -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit, onCalls: () -> Unit = {}, onEdgeTouch: () -> Unit = {}, onSuggestions: () -> Unit = {}) {
    Surface(onClick = onDiagnostics, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
    GroupTitle("功能")
    SettingsGroup {
        SettingRow("小窗", "角落启动、快捷应用与窗口操作", onWindow,
            leading = { Glyph("window", Color(0xFFB6EAFE), Color(0xFF00556A)) })
        SettingRow("边缘防误触", "减少单手握持误触，左右范围分别调整", onEdgeTouch,
            leading = { Glyph("edge", Color(0xFFF1DDF9), Color(0xFF6A3585)) })
        SettingRow("建议", "手电筒、蓝牙、验证码与复制内容快捷操作", onSuggestions,
            leading = { Glyph("gesture", Color(0xFFFFE4BC), Color(0xFF795500)) })
        SettingRow("电话拦截", "双卡来电控制、号码规则与拦截记录", onCalls,
            leading = { Glyph("phone", Color(0xFFD7F0D2), Color(0xFF245328)) })
    }
    GroupTitle("应用")
    SettingsGroup {
        SettingRow("关于", "类原生补全 · ${BuildConfig.VERSION_NAME}", onAbout, leading = { Glyph("info") })
    }
}
