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
fun WindowScreen(p: WindowPreferences, navigate: (Page) -> Unit, toggleControl: () -> Unit) {
    SettingsGroup {
        ToggleRow("启用小窗", "通过角落菜单或长侧滑打开应用小窗", p.enabled) { toggleControl() }
    }
    GroupTitle("小窗设置")
    SettingsGroup {
        SettingRow("快捷应用", "已选择 ${p.apps.size} 项 · 扇区显示前 ${p.fanCount} 项", { navigate(Page.Apps) }, leading = { Glyph("apps") })
        SettingRow("手势与菜单", "触发热区、滑动距离、扇形外观", { navigate(Page.Gestures) }, leading = { Glyph("gesture") })
        SettingRow("窗口与多任务", "背景压暗、空白处关闭、最近任务中的显示", { navigate(Page.Behavior) }, leading = { Glyph("window") })
        SettingRow("小窗触发限制", "横屏、键盘与指定应用", { navigate(Page.Prevention) }, leading = { Glyph("check") })
    }
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
        ToggleRow("菜单背景模糊", "模糊应用菜单背后的画面；系统停用模糊时使用实色背景", p.menuBlur) { boolean("menuBlur", it) }
        SliderRow("扇区快捷项数量", "按快捷区顺序显示前几项，另保留一个列表入口", p.fanCount, 1..5, " 个") { number("fanCount", it) }
        SliderRow("扇形半径", "图标较多时自动适配间距，左右镜像", p.radius, 120..260, " dp") { number("radius", it) }
        SliderRow("图标直径", "应用列表入口固定排在最下面", p.icon, 32..56, " dp") { number("icon", it) }
    }
}

@Composable
fun BehaviorScreen(p: WindowPreferences, boolean: (String, Boolean) -> Unit, number: (String, Int) -> Unit) {
    SettingsGroup {
        SliderRow("背景压暗", "普通小窗后方的压暗程度，0% 为关闭", p.windowDim, 0..100, "%") { number("windowDim", it) }
    }
    GroupTitle("窗口行为")
    SettingsGroup {
        ToggleRow("应用间跳转小窗打开", "分享、链接及打开其他应用时，以小窗显示目标页面，保留原有返回关系", p.landscapeShareWindow) { boolean("landscapeShareWindow", it) }
        ToggleRow("通知小窗打开", "点击普通应用通知时，用小窗打开对应页面；关闭后沿用系统方式", p.notificationWindow) { boolean("notificationWindow", it) }
        ToggleRow("双击空白处关闭", "开启时双击关闭普通小窗，关闭时单击关闭", p.doubleClose) { boolean("doubleClose", it) }
        ToggleRow("贴边锁屏继续运行", "贴边隐藏后保持运行，支持锁屏听视频与下载；关闭小窗后结束维持", p.keepEdgeAwake) { boolean("keepEdgeAwake", it) }
        ToggleRow("关闭小窗时结束应用", "明确关闭或拖到关闭圆环后结束应用，播放和下载也会停止；仍有同应用小窗时保留；切换、贴边与转全屏不清理", p.endOnClose) { boolean("endOnClose", it) }
        ToggleRow("在多任务中隐藏小窗应用", "关闭或切换后继续隐藏；真正全屏打开并完成绘制后重新显示", p.hideRecents) { boolean("hideRecents", it) }
    }
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
        ToggleRow("显示小窗触发热区", "显示角落范围、按中与取消状态", controller.preferences.showHeat) { controller.boolean("showHeat", it) }
    }
    GroupTitle("小窗连接")
    SettingsGroup {
        SettingRow("系统后端", when {
            !runtime.received -> "尚未连接"
            !runtime.compatible -> "版本不一致，需要重启"
            else -> "已连接 · 协议 16"
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
