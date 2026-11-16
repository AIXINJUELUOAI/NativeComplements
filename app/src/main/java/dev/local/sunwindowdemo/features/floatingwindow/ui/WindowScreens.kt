package dev.local.sunwindowdemo.features.floatingwindow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.navigation.Page

@Composable
fun WindowScreen(p: WindowPreferences, navigate: (Page) -> Unit, toggleControl: () -> Unit,
                 enablePrompt: String? = null, enableChecking: Boolean = false, dismissEnablePrompt: () -> Unit = {},
                 confirmEnablePrompt: () -> Unit = {}) {
    SettingsGroup {
        ToggleRow("启用小窗", if (enableChecking) "正在核验系统 Freeform 能力…再次点击可取消" else "检查结果仅作提醒，可继续开启；不自动修改系统设置", value = p.enabled) { toggleControl() }
    }
    if (enablePrompt != null) AlertDialog(
        onDismissRequest = dismissEnablePrompt,
        title = { Text("开启前提醒") },
        text = { SelectionContainer { Text(enablePrompt) } },
        confirmButton = { TextButton(onClick = confirmEnablePrompt) { Text("继续开启") } },
        dismissButton = { TextButton(onClick = dismissEnablePrompt) { Text("取消") } },
    )
    GroupTitle("小窗设置")
    SettingsGroup {
        SettingRow("手势与菜单", onClick = { navigate(Page.Gestures) }, leading = { Glyph("gesture") })
        SettingRow("窗口与多任务", onClick = { navigate(Page.Behavior) }, leading = { Glyph("window") })
        SettingRow("小窗触发限制", onClick = { navigate(Page.Prevention) }, leading = { Glyph("check") })
    }
}

@Composable
fun GestureScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, number: (String, Int) -> Unit) {
    val context = LocalContext.current
    SettingsGroup {
        SettingRow("无障碍按钮呼出菜单", "可选：在系统设置中手动启用“小窗菜单按钮”并加入无障碍按钮，启用本身不呼出菜单；点击按钮沿用本版本的小窗限制，不支持借用另一版本后端。", onClick = {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { android.widget.Toast.makeText(context, "系统无障碍设置暂不可用", android.widget.Toast.LENGTH_SHORT).show() }
        })
        ToggleRow("长侧滑打开小窗", "从屏幕侧边向内长滑，松手后将当前应用转为小窗", value = p.longBackWindow) { boolean("longBackWindow", it) }
        ToggleRow("关闭顶部提示", value = p.hideTopHint) { boolean("hideTopHint", it) }
    }
    GroupTitle("触发与选中")
    SettingsGroup {
        SliderRow("角落热区边长", value = p.heat, range = 36..72, unit = " dp") { number("heat", it) }
        SliderRow("触发滑动距离", value = p.distance, range = 24..100, unit = " dp") { number("distance", it) }
        SliderRow("滑动选中距离", value = p.selectionDistance, range = 50..100, unit = "%") { number("selectionDistance", it) }
    }
    GroupTitle("菜单外观")
    SettingsGroup {
        ToggleRow("菜单背景模糊", value = p.menuBlur) { boolean("menuBlur", it) }
        SliderRow("扇区快捷项数量", value = p.fanCount, range = 1..5, unit = " 个") { number("fanCount", it) }
        SliderRow("扇形半径", value = p.radius, range = 120..260, unit = " dp") { number("radius", it) }
        SliderRow("图标直径", value = p.icon, range = 32..56, unit = " dp") { number("icon", it) }
    }
}

@Composable
fun BehaviorScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, number: (String, Int) -> Unit) {
    SettingsGroup {
        SliderRow("背景压暗", value = p.windowDim, range = 0..100, unit = "%") { number("windowDim", it) }
    }
    GroupTitle("窗口行为")
    SettingsGroup {
        ToggleRow("应用间跳转小窗打开", value = p.landscapeShareWindow) { boolean("landscapeShareWindow", it) }
        ToggleRow("通知小窗打开", value = p.notificationWindow) { boolean("notificationWindow", it) }
        ToggleRow("新开小窗时自动最小化", value = p.autoMinimizeOnOpen) { boolean("autoMinimizeOnOpen", it) }
        ToggleRow("双击空白处关闭", value = p.doubleClose) { boolean("doubleClose", it) }
        ToggleRow("贴边锁屏继续运行", value = p.keepEdgeAwake) { boolean("keepEdgeAwake", it) }
        ToggleRow("关闭小窗时结束应用", value = p.endOnClose) { boolean("endOnClose", it) }
        ToggleRow("在多任务中隐藏小窗应用", value = p.hideRecents) { boolean("hideRecents", it) }
    }
}

@Composable
fun PreventionScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, blocked: () -> Unit) {
    SettingsGroup {
        ToggleRow("横屏时禁用", value = p.landscapeOff) { boolean("landscape", it) }
        ToggleRow("键盘弹出时禁用", value = p.keyboardOff) { boolean("keyboard", it) }
        SettingRow("指定应用内禁用", "已选择 ${p.blocked.size} 个应用", blocked)
    }
}

@Composable
fun DiagnosticsScreen(controller: WindowController) {
    val runtime = controller.runtime
    SettingsGroup {
        ToggleRow("显示小窗触发热区", value = controller.preferences.showHeat) { controller.boolean("showHeat", it) }
    }
    GroupTitle("小窗连接")
    SettingsGroup {
        SettingRow("系统后端", when {
            !runtime.received -> "尚未连接"
            !runtime.compatible -> "版本不一致，需要重启"
            else -> "已连接 · 协议 16"
        })
        SettingRow("SystemUI 宿主", if (runtime.hostConnected) "已连接" else "未连接，请检查作用域并重启")
        SettingRow("重新检查", onClick = controller::check)
    }
    GroupTitle("测试与恢复")
    SettingsGroup {
        SettingRow("触摸与键盘诊断", onClick = controller::diagnostic)
        SettingRow("使用 Chrome 测试", onClick = controller::chrome)
        SettingRow("恢复原窗口", onClick = controller::restore)
        SettingRow("打开待恢复任务", onClick = controller::reopen)
        SettingRow("允许恢复通知", onClick = controller::notifications)
    }
    GroupTitle("诊断信息")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SelectionContainer { Text(runtime.report + "\n\n最近手势：" + runtime.lastTouch,
            Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
