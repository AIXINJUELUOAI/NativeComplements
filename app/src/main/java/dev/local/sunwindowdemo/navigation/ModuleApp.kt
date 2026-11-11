package dev.local.sunwindowdemo.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.local.sunwindowdemo.*
import dev.local.sunwindowdemo.core.ui.*
import dev.local.sunwindowdemo.features.callblocking.CallBlockingScreen
import dev.local.sunwindowdemo.features.callblocking.CallBlockingStore
import dev.local.sunwindowdemo.features.edgetouch.EdgeTouchScreen
import dev.local.sunwindowdemo.features.floatingwindow.ui.*
import dev.local.sunwindowdemo.features.barrage.BarrageScreen
import dev.local.sunwindowdemo.features.iconlayers.IconDiagnosticsScreen
import dev.local.sunwindowdemo.features.iconlayers.IconLayerScreen
import dev.local.sunwindowdemo.features.motion.MotionScreen
import dev.local.sunwindowdemo.features.motion.MotionStore
import dev.local.sunwindowdemo.features.suggestions.SuggestionScreen
import dev.local.sunwindowdemo.home.AboutScreen
import dev.local.sunwindowdemo.home.HomeScreen
import dev.local.sunwindowdemo.home.LicensesScreen

enum class Page(val title: String) {
    Home("类原生补全计划"), Window("小窗"), WindowSettings("小窗设置"), Barrage("弹幕通知"), Suggestions("快捷建议"),
    IconLayers("图标适配"), Motion("加速度传感器控制"), EdgeTouch("边缘防误触"), Calls("来电拦截"), Records("拦截记录"),
    Apps("快捷应用"), Gestures("手势与菜单"), Behavior("窗口与多任务"), Prevention("小窗触发限制"), Blocked("禁用应用"),
    About("关于"), Licenses("开源许可"), Tools("开发者工具"), Diagnostics("小窗诊断与恢复"), IconDiagnostics("图标实验与诊断"),
}

@Composable
fun ModuleApp(controller: WindowController, page: Page, navigate: (Page) -> Unit, back: () -> Unit) {
    // Standard Activity navigation leaves predictive Back and its animation with Android.
    SettingsPage(
        title = page.title,
        onBack = if (page == Page.Home) null else back,
        help = helpFor(page),
        actions = {
            if (page == Page.Motion) MotionActions()
            if (page == Page.Window) {
                TextButton(onClick = controller::editMenu, enabled = controller.catalogLoaded && !controller.menuSaving) {
                    Text(if (controller.menuSaving) "保存中…" else if (controller.menuEditing) "完成" else "管理")
                }
                IconButton(onClick = { navigate(Page.WindowSettings) }) {
                    Icon(Icons.Default.Settings, contentDescription = "小窗设置")
                }
            }
        },
    ) { bottomInset ->
        when (page) {
            Page.Window -> WindowMenuScreen(controller, bottomInset)
            Page.Calls, Page.Records -> CallBlockingScreen(page == Page.Records, bottomInset, onRecords = { navigate(Page.Records) })
            Page.EdgeTouch -> EdgeTouchScreen(bottomInset)
            Page.Motion -> MotionScreen(controller.apps, controller.catalogLoaded, bottomInset)
            Page.Barrage -> BarrageScreen(controller.apps, controller.catalogLoaded, bottomInset)
            Page.IconLayers -> IconLayerScreen(controller.apps, bottomInset)
            Page.Apps, Page.Blocked -> AppPickerScreen(controller, blocked = page == Page.Blocked, bottomInset = bottomInset)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp + bottomInset)) {
                when (page) {
                    Page.Home -> HomeScreen(
                        onWindow = { navigate(Page.Window) }, onAbout = { navigate(Page.About) },
                        onCalls = { navigate(Page.Calls) }, onEdgeTouch = { navigate(Page.EdgeTouch) },
                        onSuggestions = { navigate(Page.Suggestions) }, onMotion = { navigate(Page.Motion) },
                        onIcons = { navigate(Page.IconLayers) }, onBarrage = { navigate(Page.Barrage) },
                    )
                    Page.WindowSettings -> WindowScreen(controller.preferences, navigate, controller::toggleControl)
                    Page.Suggestions -> SuggestionScreen(controller)
                    Page.Gestures -> GestureScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Behavior -> BehaviorScreen(controller.preferences, controller::boolean, controller::number)
                    Page.Prevention -> PreventionScreen(controller.preferences, controller::boolean, { navigate(Page.Blocked) })
                    Page.About -> AboutScreen(onLicenses = { navigate(Page.Licenses) }, onTools = { navigate(Page.Tools) })
                    Page.Licenses -> LicensesScreen()
                    Page.Tools -> DeveloperTools(onWindow = { navigate(Page.Diagnostics) }, onIcons = { navigate(Page.IconDiagnostics) })
                    Page.Diagnostics -> DiagnosticsScreen(controller)
                    Page.IconDiagnostics -> IconDiagnosticsScreen()
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun MotionActions() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "批量设置") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("全部允许使用", "全部前 7 秒限制", "全部始终限制").forEachIndexed { mode, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { MotionStore.all(context, mode); expanded = false })
            }
        }
    }
}

@Composable
private fun DeveloperTools(onWindow: () -> Unit, onIcons: () -> Unit) {
    val context = LocalContext.current
    var calls by remember { mutableStateOf(CallBlockingStore.readConfig(context)) }
    var motion by remember { mutableStateOf(MotionStore.read(context)) }
    SettingsGroup {
        SettingRow("小窗诊断与恢复", "连接信息、触摸测试与恢复窗口", onWindow)
        SettingRow("图标实验与诊断", "桌面连接信息与 Demo 分层对照", onIcons)
    }
    GroupTitle("其他服务")
    SettingsGroup {
        SettingRow("来电拦截", calls.getString("backend_error").orEmpty().ifBlank {
            if (calls.getLong("backend_seen_at") == 0L) "尚未连接" else "最近运行版本：${calls.getString("backend_version")}"
        })
        SettingRow("加速度传感器控制", motion.getString("backend_error").orEmpty().ifBlank {
            if (motion.getLong("backend_seen") == 0L) "尚未连接" else "最近运行版本：${motion.getString("backend_version")}"
        })
        SettingRow("刷新状态", onClick = { calls = CallBlockingStore.readConfig(context); motion = MotionStore.read(context) })
    }
}

private fun helpFor(page: Page): String? = when (page) {
    Page.Window -> "从屏幕底角向内斜滑打开菜单；从侧边长滑，震动后松手，将当前应用变成小窗。\n\n可同时运行一个普通小窗和一个迷你窗。普通窗上滑变为迷你窗，下拉回到全屏；横屏时向左变迷你窗、向右回到全屏。\n\n迷你窗单击还原、双击全屏，长按可静音或调整大小。拖到关闭圆环松手关闭，推过屏幕边缘阈值后松手贴边隐藏。\n\n关闭总开关会结束当前小窗。"
    Page.Gestures -> "快捷区可以添加多个应用，扇区按顺序显示前几项，另保留一个全部应用入口。设置更改后自动保存。"
    Page.Behavior -> "普通小窗竖屏固定默认位置，横屏固定靠左。竖屏键盘弹出时向上避让，横屏与悬浮键盘不调整位置。\n\n操作条固定浅色，背景压暗默认 20%，可调节为 0%～100%；0% 为关闭压暗。迷你窗在屏幕旋转后回到右上角。\n\n贴边锁屏继续运行是独立开关，关闭小窗后结束维持。结束应用会同时停止其播放和下载。"
    Page.Calls -> "仅需 SIM 2 上网时：启用来电拦截，关闭 SIM 2 的“跟随 SIM 1”，再开启该卡的“拦截全部来电”。移动数据和短信保留。\n\n规则中的 * 代表任意长度。952** 自动保存为 952*，匹配所有 952 开头的号码；不带 * 时精确匹配。关闭总开关保留原设置。"
    Page.Motion -> "可在应用进入前台的前 7 秒限制访问，或始终限制。当前系统接口还会影响所选应用的陀螺仪、重力等其他传感器，依赖体感的游戏请保持允许。系统自动旋转不受影响。\n\n右上角的批量设置覆盖现有规则，新安装应用继承该设置；之后可逐个调整。总开关开启后生效，关闭后恢复原生状态。"
    Page.IconLayers -> "导入本地图标包，支持按包名分目录的 0.png 背景和 1.png 透明前景。素材由用户自行提供。\n\n图标包优先，未匹配时保留原图。算法仅对手动选中的应用，在其启动后生成并缓存。彩色、主题、通知图标可以分别选择不处理。\n\n刷新图标保留桌面布局。莫奈配色由桌面主题决定。"
    Page.Suggestions -> "建议按钮根据手电筒、蓝牙、验证码与复制内容出现，点击轻震并执行一次操作。手电筒操作后保留 5 秒，可再次点击反向切换。\n\n长按可拖动，拖到底部 X 后松手隐藏本次建议，不会关闭设备功能。\n\n验证码保留前导零；先选中输入框再点击填充。纯分享口令会保留剪贴板并打开应用，由目标应用自行识别。"
    Page.Barrage -> "开启后，选择需要显示弹幕的前台应用。收到新通知时，在屏幕上方短暂显示标题和正文，原通知仍保留在通知栏。\n\n锁屏、键盘、系统面板或小窗菜单显示时暂停。私密通知、持续通知、分组摘要、通话、闹钟和媒体通知保持原生处理。\n\n弹幕通知独立于小窗总开关。"
    else -> null
}
