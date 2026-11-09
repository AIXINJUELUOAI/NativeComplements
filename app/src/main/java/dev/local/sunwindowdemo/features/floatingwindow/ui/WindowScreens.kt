package dev.local.sunwindowdemo.features.floatingwindow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.navigation.Page

@Composable
fun WindowScreen(p: WindowPreferences, runtime: WindowRuntime, navigate: (Page) -> Unit, toggleControl: () -> Unit) {
    Surface(onClick = toggleControl, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (!p.enabled) "小窗控制已关闭" else if (runtime.hostConnected) "小窗控制已开启" else "等待 SystemUI 宿主", style = MaterialTheme.typography.titleLarge)
            Text(if (!p.enabled) "点击开启 · 由 LSPosed 运行，无需无障碍" else "点击关闭 · 由 SystemUI 承载角落菜单与窗口控制", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Hint("从屏幕底角向内斜滑打开菜单；侧边返回长滑，震动后松手，将当前应用变成小窗。")
    GroupTitle("小窗设置")
    SettingsGroup {
        SettingRow("快捷应用", "已选择 ${p.apps.size} / 5 · 选择与排序", { navigate(Page.Apps) }, leading = { Glyph("apps") })
        SettingRow("手势与菜单", "触发热区、滑动距离、扇形外观", { navigate(Page.Gestures) }, leading = { Glyph("gesture") })
        SettingRow("窗口与多任务", "空白处关闭、最近任务中的显示", { navigate(Page.Behavior) }, leading = { Glyph("window") })
        SettingRow("小窗触发限制", "横屏、键盘与指定应用", { navigate(Page.Prevention) }, leading = { Glyph("check") })
    }
    GroupTitle("帮助")
    SettingsGroup { SettingRow("诊断与恢复", "检查连接、测试触摸、恢复原窗口", { navigate(Page.Diagnostics) }, leading = { Glyph("info") }) }
    Hint("单个活动小窗，无定时退出。关闭控制会结束当前小窗；禁用模块前请先恢复窗口。")
}

@Composable
fun GestureScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, number: (String, Int) -> Unit) {
    SettingsGroup {
        ToggleRow("关闭顶部提示", "隐藏侧边长滑提示，保留阈值震动", p.hideTopHint) { boolean("hideTopHint", it) }
    }
    GroupTitle("触发与选中")
    SettingsGroup {
        SliderRow("角落热区边长", "屏幕底部左右角的手势起始范围", p.heat, 36..72, " dp") { number("heat", it) }
        SliderRow("触发滑动距离", "从热区滑出多远后展开菜单", p.distance, 24..100, " dp") { number("distance", it) }
        SliderRow("滑动选中距离", "以图标圆弧为准，90% 在图标附近选中", p.selectionDistance, 50..100, "%") { number("selectionDistance", it) }
    }
    GroupTitle("菜单外观")
    SettingsGroup {
        SliderRow("扇形半径", "图标较多时自动适配间距，左右镜像", p.radius, 120..260, " dp") { number("radius", it) }
        SliderRow("图标直径", "应用列表入口固定排在最后", p.icon, 32..56, " dp") { number("icon", it) }
    }
    GroupTitle("调试")
    SettingsGroup {
        ToggleRow("显示触发热区", "蓝色表示范围，绿色表示按中，橙色表示触摸取消", p.showHeat) { boolean("showHeat", it) }
    }
    Hint("设置保存后立即由原有手势服务读取。震动与动画沿用当前小窗实现。")
}

@Composable
fun BehaviorScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit) {
    var showCloseChoice by remember { mutableStateOf(false) }
    SettingsGroup {
        SettingRow("空白处关闭方式", if (p.doubleClose) "双击" else "单击", { showCloseChoice = true })
        ToggleRow("在多任务中隐藏小窗应用", "关闭或切换后继续隐藏；真正全屏打开并完成绘制后重新显示", p.hideRecents) { boolean("hideRecents", it) }
    }
    GroupTitle("窗口操作")
    SettingsGroup {
        SettingRow("普通小窗", "白条向内推缩成迷你窗，向外拉进入全屏；中间区间松手回到默认比例。")
        SettingRow("迷你窗", "单击还原、双击全屏；拖到顶部关闭区后松手关闭。默认缩到右上角。")
        SettingRow("返回与截图", "返回先交给应用；应用根页面返回时关闭小窗。截图工具栏出现时优先响应截图操作。")
    }
    if (showCloseChoice) AlertDialog(onDismissRequest = { showCloseChoice = false }, title = { Text("空白处关闭方式") },
        text = { Column {
            listOf(false to "单击", true to "双击").forEach { (value, title) ->
                SettingRow(title, onClick = { boolean("doubleClose", value); showCloseChoice = false },
                    trailing = { RadioButton(selected = p.doubleClose == value, onClick = { boolean("doubleClose", value); showCloseChoice = false }) })
            }
        } }, confirmButton = { TextButton(onClick = { showCloseChoice = false }) { Text("取消") } })
}

@Composable
fun PreventionScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, blocked: () -> Unit) {
    SettingsGroup {
        ToggleRow("横屏时禁用", "横屏时暂停新的角落唤起和长滑唤起", p.landscapeOff) { boolean("landscape", it) }
        ToggleRow("键盘弹出时禁用", "输入时暂停新的手势唤起", p.keyboardOff) { boolean("keyboard", it) }
        SettingRow("指定应用内禁用", "已选择 ${p.blocked.size} 个应用", blocked)
    }
    Hint("这些条件限制新的手势唤起；已经打开的小窗仍保留白条和迷你窗操作。")
}

@Composable
fun DiagnosticsScreen(controller: WindowController) {
    val runtime = controller.runtime
    SettingsGroup {
        SettingRow("系统后端", when {
            !runtime.received -> "尚未连接"
            !runtime.compatible -> "版本不一致，需要重启"
            else -> "已连接 · 协议 15"
        })
        SettingRow("SystemUI 宿主", if (runtime.hostConnected) "已连接 · 不依赖无障碍" else "未连接，请检查作用域并重启")
        SettingRow("重新检查", "刷新后端与恢复状态", controller::check)
    }
    Hint("在 LSPosed 中启用本模块，勾选系统框架（system）、系统界面和 Pixel 启动器。覆盖安装后重启手机，再检查连接。普通应用无需加入作用域；窗口配置由系统后端统一下发。")
    GroupTitle("测试与恢复")
    SettingsGroup {
        SettingRow("触摸与键盘诊断", "打开原有五点触摸、方向与输入测试页", controller::diagnostic)
        SettingRow("使用 Chrome 测试", "复用已有任务打开小窗", controller::chrome)
        SettingRow("恢复原窗口", "结束当前小窗，恢复原窗口配置", controller::restore)
        SettingRow("打开待恢复任务", "仅用于后端保存的恢复记录", controller::reopen)
        SettingRow("允许恢复通知", "可选，方便从通知返回恢复窗口", controller::notifications)
    }
    GroupTitle("诊断信息")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SelectionContainer { Text(runtime.report + "\n\n最近手势：" + runtime.lastTouch,
            Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodySmall) }
    }
    Hint("系统后端日志标签：SunWindowDemo5。系统窗口配置日志：VIEWPORT_READY。连接状态不代表真机效果验收。")
}
